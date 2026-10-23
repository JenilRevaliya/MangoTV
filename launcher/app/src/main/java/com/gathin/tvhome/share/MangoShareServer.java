package com.gathin.tvhome.share;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight native LocalSend v2 compatible receiver for Mango TV.
 *
 * Runs a UDP discovery listener/announcer and a compact HTTP streaming server
 * with 64KB fixed buffers, ensuring rock-solid low-RAM performance (<1MB heap)
 * without any external Flutter, Dart, or Rust dependencies.
 */
public class MangoShareServer {
    private static final String TAG = "MangoShareServer";
    public static final int DEFAULT_PORT = 53317;
    public static final String MULTICAST_ADDRESS = "224.0.0.167";

    public interface Listener {
        void onIncomingTransferRequested(TransferSession session);
        void onTransferProgress(TransferSession session, FileItem item, long bytesReceived, long totalBytes);
        void onTransferComplete(TransferSession session);
        void onTransferError(TransferSession session, String reason);
    }

    public static class FileItem {
        public final String id;
        public final String fileName;
        public final long size;
        public final String fileType;
        public final String token;
        public File destinationFile;

        public FileItem(String id, String fileName, long size, String fileType, String token) {
            this.id = id;
            this.fileName = fileName;
            this.size = size;
            this.fileType = fileType;
            this.token = token;
        }
    }

    public static class TransferSession {
        public final String sessionId;
        public final String senderAlias;
        public final String senderModel;
        public final String senderDeviceType;
        public final List<FileItem> files = new ArrayList<>();
        public final Map<String, FileItem> fileMap = new HashMap<>();
        public long totalBytes = 0L;
        public boolean isAccepted = false;
        public boolean isFinished = false;

        private final CountDownLatch decisionLatch = new CountDownLatch(1);

        public TransferSession(String sessionId, String senderAlias, String senderModel, String senderDeviceType) {
            this.sessionId = sessionId;
            this.senderAlias = senderAlias;
            this.senderModel = senderModel;
            this.senderDeviceType = senderDeviceType;
        }

        public void addFile(FileItem item) {
            files.add(item);
            fileMap.put(item.id, item);
            totalBytes += item.size;
        }

        public void accept() {
            isAccepted = true;
            decisionLatch.countDown();
        }

        public void decline() {
            isAccepted = false;
            decisionLatch.countDown();
        }

        public boolean awaitDecision(long timeout, TimeUnit unit) {
            try {
                return decisionLatch.await(timeout, unit);
            } catch (InterruptedException e) {
                return false;
            }
        }
    }

    private final Context context;
    private final Handler mainHandler;
    private final String deviceName;
    private final String fingerprint;
    private Listener listener;

    private boolean isRunning = false;
    private ServerSocket serverSocket;
    private MulticastSocket multicastSocket;
    private ExecutorService threadPool;

    private final Map<String, TransferSession> activeSessions = new ConcurrentHashMap<>();

    public MangoShareServer(Context context, String deviceName) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.deviceName = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "Mango TV";
        this.fingerprint = UUID.randomUUID().toString();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        threadPool = Executors.newCachedThreadPool();

        startHttpServer();
        startMulticastDiscovery();
        broadcastAnnouncement();
        Log.i(TAG, "Mango Share server started on port " + DEFAULT_PORT);
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;

        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (Throwable ignored) {}

        try {
            if (multicastSocket != null) {
                multicastSocket.leaveGroup(InetAddress.getByName(MULTICAST_ADDRESS));
                multicastSocket.close();
                multicastSocket = null;
            }
        } catch (Throwable ignored) {}

        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }

        activeSessions.clear();
        Log.i(TAG, "Mango Share server stopped");
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void startHttpServer() {
        threadPool.execute(() -> {
            try {
                serverSocket = new ServerSocket(DEFAULT_PORT);
                while (isRunning && !serverSocket.isClosed()) {
                    Socket clientSocket = serverSocket.accept();
                    if (!isRunning) {
                        clientSocket.close();
                        break;
                    }
                    threadPool.execute(() -> handleClientSocket(clientSocket));
                }
            } catch (Throwable t) {
                if (isRunning) {
                    Log.e(TAG, "Error in HTTP server loop", t);
                }
            }
        });
    }

    private void handleClientSocket(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                socket.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                socket.close();
                return;
            }

            String method = parts[0].toUpperCase(Locale.ROOT);
            String fullPath = parts[1];
            String path = fullPath;
            String queryString = "";
            int queryIdx = fullPath.indexOf('?');
            if (queryIdx >= 0) {
                path = fullPath.substring(0, queryIdx);
                queryString = fullPath.substring(queryIdx + 1);
            }

            Map<String, String> headers = new HashMap<>();
            String headerLine;
            while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
                int colonIdx = headerLine.indexOf(':');
                if (colonIdx > 0) {
                    headers.put(headerLine.substring(0, colonIdx).trim().toLowerCase(Locale.ROOT),
                            headerLine.substring(colonIdx + 1).trim());
                }
            }

            long contentLength = 0;
            String clHeader = headers.get("content-length");
            if (clHeader != null) {
                try {
                    contentLength = Long.parseLong(clHeader);
                } catch (NumberFormatException ignored) {}
            }

            if ("OPTIONS".equals(method)) {
                sendResponse(out, 200, "OK", "text/plain", "OK");
                return;
            }

            if ("GET".equals(method) && path.equals("/api/localsend/v2/info")) {
                handleGetInfo(out);
            } else if ("POST".equals(method) && path.equals("/api/localsend/v2/register")) {
                handleGetInfo(out);
            } else if ("POST".equals(method) && path.equals("/api/localsend/v2/prepare-upload")) {
                handlePrepareUpload(in, out, contentLength);
            } else if ("POST".equals(method) && path.equals("/api/localsend/v2/upload")) {
                handleUpload(in, out, queryString, contentLength);
            } else if ("POST".equals(method) && path.equals("/api/localsend/v2/cancel")) {
                handleCancel(out, queryString);
            } else {
                sendResponse(out, 404, "Not Found", "application/json", "{\"message\":\"Endpoint not found\"}");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Client socket handling exception", t);
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {}
        }
    }

    private void handleGetInfo(OutputStream out) throws Exception {
        JSONObject json = new JSONObject();
        json.put("alias", deviceName);
        json.put("version", "2.1");
        json.put("deviceModel", "Android TV");
        json.put("deviceType", "headless");
        json.put("fingerprint", fingerprint);
        json.put("download", false);
        sendResponse(out, 200, "OK", "application/json", json.toString());
    }

    private void handlePrepareUpload(InputStream in, OutputStream out, long contentLength) throws Exception {
        byte[] bodyBytes = readExactBytes(in, (int) contentLength);
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(body);

        JSONObject info = root.optJSONObject("info");
        String senderAlias = info != null ? info.optString("alias", "Unknown Sender") : "Unknown Sender";
        String senderModel = info != null ? info.optString("deviceModel", "Nearby Device") : "Nearby Device";
        String senderDeviceType = info != null ? info.optString("deviceType", "mobile") : "mobile";

        String sessionId = UUID.randomUUID().toString();
        TransferSession session = new TransferSession(sessionId, senderAlias, senderModel, senderDeviceType);

        JSONObject filesObj = root.optJSONObject("files");
        if (filesObj != null) {
            for (java.util.Iterator<String> it = filesObj.keys(); it.hasNext(); ) {
                String fileId = it.next();
                JSONObject fObj = filesObj.getJSONObject(fileId);
                String fileName = fObj.optString("fileName", "file_" + fileId);
                long size = fObj.optLong("size", 0L);
                String fileType = fObj.optString("fileType", "application/octet-stream");
                String fileToken = UUID.randomUUID().toString();
                session.addFile(new FileItem(fileId, fileName, size, fileType, fileToken));
            }
        }

        // Check storage availability
        File downloadDir = getStorageDestination();
        if (downloadDir != null) {
            StatFs stat = new StatFs(downloadDir.getAbsolutePath());
            long availableBytes = stat.getAvailableBytes();
            if (availableBytes < session.totalBytes + (50L * 1024 * 1024)) { // 50MB safety reserve
                sendResponse(out, 403, "Forbidden", "application/json", "{\"message\":\"Insufficient TV storage space\"}");
                return;
            }
        }

        activeSessions.put(sessionId, session);

        // Notify UI to present tvOS remote prompt
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onIncomingTransferRequested(session);
            }
        });

        // Block request thread waiting for user acceptance on the remote (30s timeout)
        boolean decided = session.awaitDecision(30, TimeUnit.SECONDS);
        if (decided && session.isAccepted) {
            JSONObject resp = new JSONObject();
            resp.put("sessionId", sessionId);
            JSONObject filesMap = new JSONObject();
            for (FileItem item : session.files) {
                filesMap.put(item.id, item.token);
            }
            resp.put("files", filesMap);
            sendResponse(out, 200, "OK", "application/json", resp.toString());
        } else {
            activeSessions.remove(sessionId);
            sendResponse(out, 403, "Forbidden", "application/json", "{\"message\":\"Transfer declined or timed out\"}");
        }
    }

    private void handleUpload(InputStream in, OutputStream out, String queryString, long contentLength) throws Exception {
        Map<String, String> query = parseQueryString(queryString);
        String sessionId = query.get("sessionid");
        String fileId = query.get("fileid");
        String token = query.get("token");

        if (sessionId == null || fileId == null || token == null) {
            sendResponse(out, 400, "Bad Request", "text/plain", "Missing query parameters");
            return;
        }

        TransferSession session = activeSessions.get(sessionId);
        if (session == null || !session.isAccepted) {
            sendResponse(out, 403, "Forbidden", "text/plain", "Session invalid or unapproved");
            return;
        }

        FileItem item = session.fileMap.get(fileId);
        if (item == null || !token.equals(item.token)) {
            sendResponse(out, 403, "Forbidden", "text/plain", "Invalid file token");
            return;
        }

        File targetDir = getStorageDestination();
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        // Directory traversal defense: strictly sanitize filename
        String safeName = sanitizeFileName(item.fileName);
        File destFile = new File(targetDir, safeName);
        int dedupeCounter = 1;
        while (destFile.exists()) {
            int extIdx = safeName.lastIndexOf('.');
            if (extIdx > 0) {
                destFile = new File(targetDir, safeName.substring(0, extIdx) + " (" + dedupeCounter + ")" + safeName.substring(extIdx));
            } else {
                destFile = new File(targetDir, safeName + " (" + dedupeCounter + ")");
            }
            dedupeCounter++;
        }
        item.destinationFile = destFile;

        // Stream binary upload to disk using a single 64KB fixed buffer (zero RAM bloat)
        byte[] buffer = new byte[65536];
        long totalToRead = (contentLength > 0) ? contentLength : item.size;
        long totalRead = 0;

        try (FileOutputStream fos = new FileOutputStream(destFile)) {
            while (totalRead < totalToRead) {
                int toRead = (int) Math.min(buffer.length, totalToRead - totalRead);
                int read = in.read(buffer, 0, toRead);
                if (read == -1) break;
                fos.write(buffer, 0, read);
                totalRead += read;

                final long finalTotalRead = totalRead;
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onTransferProgress(session, item, finalTotalRead, totalToRead);
                    }
                });
            }
            fos.flush();
        }

        sendResponse(out, 200, "OK", "text/plain", "OK");

        // Check if all files in session have been uploaded
        boolean allComplete = true;
        for (FileItem f : session.files) {
            if (f.destinationFile == null || !f.destinationFile.exists()) {
                allComplete = false;
                break;
            }
        }

        if (allComplete && !session.isFinished) {
            session.isFinished = true;
            activeSessions.remove(sessionId);
            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onTransferComplete(session);
                }
            });
        }
    }

    private void handleCancel(OutputStream out, String queryString) throws Exception {
        Map<String, String> query = parseQueryString(queryString);
        String sessionId = query.get("sessionid");
        if (sessionId != null) {
            TransferSession session = activeSessions.remove(sessionId);
            if (session != null) {
                session.decline();
                for (FileItem item : session.files) {
                    if (item.destinationFile != null && item.destinationFile.exists()) {
                        item.destinationFile.delete();
                    }
                }
            }
        }
        sendResponse(out, 200, "OK", "text/plain", "OK");
    }

    private void startMulticastDiscovery() {
        threadPool.execute(() -> {
            try {
                multicastSocket = new MulticastSocket(DEFAULT_PORT);
                InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
                multicastSocket.joinGroup(group);

                byte[] buf = new byte[2048];
                while (isRunning && !multicastSocket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    multicastSocket.receive(packet);
                    if (!isRunning) break;

                    String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    try {
                        JSONObject json = new JSONObject(message);
                        String peerFingerprint = json.optString("fingerprint", "");
                        if (!fingerprint.equals(peerFingerprint)) {
                            // If peer sent an announcement query, reply with our beacon
                            if (json.optBoolean("announcement", false) || json.optBoolean("announce", false)) {
                                broadcastAnnouncement();
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                if (isRunning) {
                    Log.w(TAG, "Multicast discovery loop error", t);
                }
            }
        });
    }

    public void broadcastAnnouncement() {
        if (!isRunning || threadPool == null) return;
        threadPool.execute(() -> {
            try {
                JSONObject beacon = new JSONObject();
                beacon.put("alias", deviceName);
                beacon.put("version", "2.1");
                beacon.put("deviceModel", "Android TV");
                beacon.put("deviceType", "headless");
                beacon.put("fingerprint", fingerprint);
                beacon.put("port", DEFAULT_PORT);
                beacon.put("protocol", "http");
                beacon.put("download", false);
                beacon.put("announcement", true);
                beacon.put("announce", true);

                byte[] bytes = beacon.toString().getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(
                        bytes, bytes.length, InetAddress.getByName(MULTICAST_ADDRESS), DEFAULT_PORT);
                try (MulticastSocket sock = new MulticastSocket()) {
                    sock.send(packet);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Announcement broadcast failed", t);
            }
        });
    }

    public File getStorageDestination() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MangoShare");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        if (!dir.canWrite()) {
            File appDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            dir = new File(appDir, "MangoShare");
            if (!dir.exists()) dir.mkdirs();
        }
        return dir;
    }

    public static String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) return "unnamed_file";
        String clean = name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        while (clean.contains("..")) {
            clean = clean.replace("..", "_");
        }
        while (clean.startsWith(".")) {
            clean = "_" + clean.substring(1);
        }
        return clean.isEmpty() ? "unnamed_file" : clean;
    }

    private static Map<String, String> parseQueryString(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                map.put(pair.substring(0, idx).toLowerCase(Locale.ROOT),
                        pair.substring(idx + 1));
            }
        }
        return map;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (baos.size() == 0 && b == -1) return null;
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    private static byte[] readExactBytes(InputStream in, int count) throws IOException {
        byte[] data = new byte[count];
        int read = 0;
        while (read < count) {
            int r = in.read(data, read, count - read);
            if (r == -1) break;
            read += r;
        }
        return data;
    }

    private static void sendResponse(OutputStream out, int statusCode, String statusText, String contentType, String body) throws IOException {
        byte[] bodyBytes = (body != null) ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                "Content-Type: " + contentType + "; charset=utf-8\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        if (bodyBytes.length > 0) {
            out.write(bodyBytes);
        }
        out.flush();
    }
}

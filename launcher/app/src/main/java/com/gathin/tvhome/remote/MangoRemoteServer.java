package com.gathin.tvhome.remote;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ultra-lightweight HTTP & RFC 6455 WebSocket Server for Mango Remote.
 * Runs on port 8090 with zero external libraries.
 */
public class MangoRemoteServer {
    private static final String TAG = "MangoRemoteServer";
    private static final String WEBSOCKET_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public interface RemoteServerListener {
        void onServerStarted(int port, String localIp);
        void onClientConnected(String clientIp);
        void onClientDisconnected(String clientIp);
        void onCommandReceived(String action, JSONObject payload);
    }

    private final Context context;
    private final int port;
    private final MangoRemotePairingManager pairingManager;
    private final MangoRemoteKeyInjector keyInjector;
    private final ExecutorService connectionPool = Executors.newCachedThreadPool();
    private final Map<String, WebSocketClient> activeClients = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    private RemoteServerListener listener;

    public MangoRemoteServer(Context context, int port, MangoRemotePairingManager pairingManager, MangoRemoteKeyInjector keyInjector) {
        this.context = context.getApplicationContext();
        this.port = port;
        this.pairingManager = pairingManager;
        this.keyInjector = keyInjector;
    }

    public void setListener(RemoteServerListener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        connectionPool.execute(this::runServerLoop);
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Throwable ignored) {}

        for (WebSocketClient client : activeClients.values()) {
            client.close();
        }
        activeClients.clear();
        Log.i(TAG, "Mango Remote Server stopped");
    }

    public boolean isRunning() {
        return isRunning;
    }

    public int getPort() {
        return port;
    }

    public String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error determining local IP", t);
        }
        return "127.0.0.1";
    }

    private void runServerLoop() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
            String localIp = getLocalIpAddress();
            Log.i(TAG, "Mango Remote Server active at http://" + localIp + ":" + port);
            if (listener != null) listener.onServerStarted(port, localIp);

            while (isRunning) {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                connectionPool.execute(() -> handleClientSocket(clientSocket));
            }
        } catch (SocketException se) {
            if (isRunning) Log.e(TAG, "ServerSocket error", se);
        } catch (Throwable t) {
            Log.e(TAG, "Server loop encountered exception", t);
        } finally {
            isRunning = false;
        }
    }

    private void handleClientSocket(Socket socket) {
        String clientIp = socket.getInetAddress().getHostAddress();
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            // Read HTTP request line and headers
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

            String method = parts[0].toUpperCase();
            String path = parts[1];

            Map<String, String> headers = new java.util.HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                }
            }

            // Check for WebSocket Upgrade
            String upgrade = headers.get("upgrade");
            String connection = headers.get("connection");
            if ("websocket".equalsIgnoreCase(upgrade) && connection != null && connection.toLowerCase().contains("upgrade")) {
                handleWebSocketUpgrade(socket, in, out, headers, path, clientIp);
                return;
            }

            // HTTP Request handling
            handleHttpRequest(socket, in, out, method, path, headers);
        } catch (Throwable t) {
            Log.w(TAG, "Client socket closed: " + clientIp, t);
        }
    }

    private void handleWebSocketUpgrade(Socket socket, InputStream in, OutputStream out, Map<String, String> headers, String path, String clientIp) throws Exception {
        String wsKey = headers.get("sec-websocket-key");
        if (wsKey == null) {
            sendHttpError(out, 400, "Missing Sec-WebSocket-Key");
            socket.close();
            return;
        }

        // Calculate Sec-WebSocket-Accept
        String acceptInput = wsKey + WEBSOCKET_MAGIC_GUID;
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] hash = md.digest(acceptInput.getBytes(StandardCharsets.UTF_8));
        String acceptKey = Base64.encodeToString(hash, Base64.NO_WRAP);

        // Send 101 Switching Protocols response
        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + acceptKey + "\r\n" +
                "\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();

        WebSocketClient client = new WebSocketClient(socket, in, out, clientIp);
        activeClients.put(clientIp, client);
        if (listener != null) listener.onClientConnected(clientIp);

        client.runMessageLoop();

        activeClients.remove(clientIp);
        if (listener != null) listener.onClientDisconnected(clientIp);
    }

    private void handleHttpRequest(Socket socket, InputStream in, OutputStream out, String method, String path, Map<String, String> headers) throws Exception {
        // Strip query string for path routing
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        // Handle CORS Pre-flight OPTIONS
        if ("OPTIONS".equals(method)) {
            sendCorsResponse(out, 204, "No Content", "text/plain", new byte[0]);
            socket.close();
            return;
        }

        // API Endpoint: /api/info
        if ("/api/info".equals(cleanPath)) {
            JSONObject info = new JSONObject();
            info.put("tv_name", "Mango TV");
            info.put("device_model", Build.MODEL);
            info.put("android_version", Build.VERSION.RELEASE);
            info.put("protocol_version", MangoRemoteProtocol.PROTOCOL_VERSION);
            info.put("pairing_active", pairingManager.isPairingActive());
            info.put("paired_devices", pairingManager.getPairedDeviceCount());
            sendJsonResponse(out, 200, info);
            socket.close();
            return;
        }

        // API Endpoint: /api/pair (POST)
        if ("/api/pair".equals(cleanPath) && "POST".equals(method)) {
            int contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            byte[] bodyBytes = readExact(in, contentLength);
            JSONObject req = new JSONObject(new String(bodyBytes, StandardCharsets.UTF_8));

            String pin = req.optString("pin", "");
            String clientName = req.optString("client_name", "Web Remote");
            String clientId = req.optString("client_id", "client");

            String token = pairingManager.attemptPair(pin, clientName, clientId);
            JSONObject res = new JSONObject();
            if (token != null) {
                res.put("status", "ok");
                res.put("auth_token", token);
                res.put("tv_name", "Mango TV");
                res.put("device_model", Build.MODEL);
                sendJsonResponse(out, 200, res);
            } else {
                res.put("status", "error");
                res.put("message", "Invalid PIN or pairing expired");
                sendJsonResponse(out, 401, res);
            }
            socket.close();
            return;
        }

        // API Endpoint: /api/apps (GET)
        if ("/api/apps".equals(cleanPath)) {
            JSONArray appsArray = new JSONArray();
            PackageManager pm = context.getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> pkgAppsList = pm.queryIntentActivities(mainIntent, 0);

            for (ResolveInfo ri : pkgAppsList) {
                JSONObject appObj = new JSONObject();
                appObj.put("package", ri.activityInfo.packageName);
                appObj.put("name", ri.loadLabel(pm).toString());
                appsArray.put(appObj);
            }
            JSONObject res = new JSONObject();
            res.put("apps", appsArray);
            sendJsonResponse(out, 200, res);
            socket.close();
            return;
        }

        // Serve Static Web Assets for Mango Remote PWA
        serveStaticPwaAsset(out, cleanPath);
        socket.close();
    }

    private void serveStaticPwaAsset(OutputStream out, String path) throws IOException {
        String assetPath = path.equals("/") || path.isEmpty() ? "remote/index.html" : "remote" + (path.startsWith("/") ? path : "/" + path);

        // Security check: path traversal prevention
        if (assetPath.contains("..")) {
            sendHttpError(out, 403, "Forbidden");
            return;
        }

        try (InputStream is = context.getAssets().open(assetPath)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) buffer.write(buf, 0, n);
            byte[] fileBytes = buffer.toByteArray();

            String mime = getMimeType(assetPath);
            sendCorsResponse(out, 200, "OK", mime, fileBytes);
        } catch (Throwable notFound) {
            // SPA fallback: return index.html for unrecognized routes
            try (InputStream is = context.getAssets().open("remote/index.html")) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) buffer.write(buf, 0, n);
                byte[] fileBytes = buffer.toByteArray();
                sendCorsResponse(out, 200, "OK", "text/html; charset=utf-8", fileBytes);
            } catch (Throwable fallbackFailed) {
                sendHttpError(out, 404, "Mango Remote Asset Not Found");
            }
        }
    }

    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json") || lower.endsWith(".webmanifest")) return "application/manifest+json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private void sendJsonResponse(OutputStream out, int status, JSONObject json) throws IOException {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        sendCorsResponse(out, status, status == 200 ? "OK" : "Error", "application/json; charset=utf-8", bytes);
    }

    private void sendCorsResponse(OutputStream out, int status, String statusText, String contentType, byte[] body) throws IOException {
        String header = "HTTP/1.1 " + status + " " + statusText + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Authorization, Sec-WebSocket-Key\r\n" +
                "Cache-Control: no-cache\r\n" +
                "\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        if (body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private void sendHttpError(OutputStream out, int status, String message) throws IOException {
        byte[] body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        sendCorsResponse(out, status, "Error", "application/json", body);
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buffer.write(b);
        }
        if (b == -1 && buffer.size() == 0) return null;
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private byte[] readExact(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int total = 0;
        while (total < length) {
            int n = in.read(data, total, length - total);
            if (n < 0) throw new EOFException("Premature EOF");
            total += n;
        }
        return data;
    }

    public void broadcastTvState(JSONObject state) {
        String msg = state.toString();
        for (WebSocketClient client : activeClients.values()) {
            if (client.isAuthenticated) {
                client.sendText(msg);
            }
        }
    }

    // ==========================================
    // RFC 6455 WebSocket Client Connection
    // ==========================================
    private class WebSocketClient {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final String ip;
        private boolean isAuthenticated = false;

        WebSocketClient(Socket socket, InputStream in, OutputStream out, String ip) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.ip = ip;
        }

        void runMessageLoop() {
            try {
                while (isRunning && !socket.isClosed()) {
                    int b0 = in.read();
                    if (b0 == -1) break;

                    boolean fin = (b0 & 0x80) != 0;
                    int opcode = b0 & 0x0F;

                    int b1 = in.read();
                    if (b1 == -1) break;

                    boolean isMasked = (b1 & 0x80) != 0;
                    long payloadLength = b1 & 0x7F;

                    if (payloadLength == 126) {
                        int b2 = in.read();
                        int b3 = in.read();
                        if (b2 == -1 || b3 == -1) break;
                        payloadLength = ((b2 & 0xFF) << 8) | (b3 & 0xFF);
                    } else if (payloadLength == 127) {
                        payloadLength = 0;
                        for (int i = 0; i < 8; i++) {
                            int b = in.read();
                            if (b == -1) break;
                            payloadLength = (payloadLength << 8) | (b & 0xFF);
                        }
                    }

                    byte[] mask = new byte[4];
                    if (isMasked) {
                        mask = readExact(in, 4);
                    }

                    byte[] payload = readExact(in, (int) payloadLength);

                    // Unmask payload
                    if (isMasked) {
                        for (int i = 0; i < payload.length; i++) {
                            payload[i] ^= mask[i % 4];
                        }
                    }

                    // Handle opcodes
                    if (opcode == 0x8) {
                        // Close frame
                        break;
                    } else if (opcode == 0x9) {
                        // Ping -> Send Pong
                        sendFrame(0xA, payload);
                    } else if (opcode == 0x1) {
                        // Text frame
                        String message = new String(payload, StandardCharsets.UTF_8);
                        handleClientJsonCommand(message);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "runMessageLoop exception", t);
            } finally {
                close();
            }
        }

        private void handleClientJsonCommand(String jsonText) {
            try {
                JSONObject json = new JSONObject(jsonText);
                String id = json.optString("id", "");
                String type = json.optString("type", "");
                String action = json.optString("action", "");
                String token = json.optString("auth_token", "");
                JSONObject payload = json.optJSONObject("payload");

                // Authentication Check:
                if (!isAuthenticated) {
                    if (MangoRemoteProtocol.TYPE_PAIR.equals(type) || "pair".equals(action)) {
                        String pin = payload != null ? payload.optString("pin", "") : json.optString("pin", "");
                        String clientName = payload != null ? payload.optString("client_name", "Remote") : "Remote";
                        String clientId = payload != null ? payload.optString("client_id", ip) : ip;

                        String issuedToken = pairingManager.attemptPair(pin, clientName, clientId);
                        JSONObject res = new JSONObject();
                        res.put("id", id);
                        res.put("type", MangoRemoteProtocol.TYPE_RESPONSE);
                        if (issuedToken != null) {
                            isAuthenticated = true;
                            res.put("status", "ok");
                            JSONObject result = new JSONObject();
                            result.put("auth_token", issuedToken);
                            result.put("tv_name", "Mango TV");
                            res.put("result", result);
                        } else {
                            res.put("status", "error");
                            res.put("message", "Invalid PIN or pairing expired");
                        }
                        sendText(res.toString());
                        return;
                    } else if (pairingManager.isTokenValid(token)) {
                        isAuthenticated = true;
                    } else {
                        JSONObject err = new JSONObject();
                        err.put("id", id);
                        err.put("status", "error");
                        err.put("message", "Unauthorized. Valid auth_token or PIN pairing required.");
                        sendText(err.toString());
                        return;
                    }
                }

                // If authenticated, execute command
                if (listener != null) listener.onCommandReceived(action, payload);

                boolean success = false;
                switch (action) {
                    case MangoRemoteProtocol.ACTION_NAVIGATE:
                        String dir = payload != null ? payload.optString("direction", "") : "";
                        success = keyInjector.handleNavigation(dir);
                        break;
                    case MangoRemoteProtocol.ACTION_MEDIA:
                        String mediaAction = payload != null ? payload.optString("media_action", "") : "";
                        success = keyInjector.handleMedia(mediaAction);
                        break;
                    case MangoRemoteProtocol.ACTION_VOLUME:
                        String volAction = payload != null ? payload.optString("volume_action", "") : "";
                        success = keyInjector.handleVolume(volAction);
                        break;
                    case MangoRemoteProtocol.ACTION_LAUNCH_APP:
                        String pkg = payload != null ? payload.optString("package", "") : "";
                        success = keyInjector.handleAppLaunch(pkg);
                        break;
                    case MangoRemoteProtocol.ACTION_TEXT_INPUT:
                        String text = payload != null ? payload.optString("text", "") : "";
                        success = keyInjector.handleTextInput(text);
                        break;
                    case MangoRemoteProtocol.ACTION_PING:
                        success = true;
                        break;
                    default:
                        Log.w(TAG, "Unknown action: " + action);
                        break;
                }

                JSONObject res = new JSONObject();
                res.put("id", id);
                res.put("status", success ? "ok" : "error");
                sendText(res.toString());

            } catch (Throwable t) {
                Log.e(TAG, "Error handling client JSON command", t);
            }
        }

        synchronized void sendText(String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            sendFrame(0x1, bytes);
        }

        synchronized void sendFrame(int opcode, byte[] data) {
            try {
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(0x80 | (opcode & 0x0F)); // FIN + opcode

                int len = data.length;
                if (len <= 125) {
                    frame.write(len); // Server to client frames are unmasked
                } else if (len <= 65535) {
                    frame.write(126);
                    frame.write((len >> 8) & 0xFF);
                    frame.write(len & 0xFF);
                } else {
                    frame.write(127);
                    for (int i = 7; i >= 0; i--) {
                        frame.write((int) ((len >> (i * 8)) & 0xFF));
                    }
                }

                frame.write(data);
                out.write(frame.toByteArray());
                out.flush();
            } catch (Throwable t) {
                close();
            }
        }

        void close() {
            try {
                socket.close();
            } catch (Throwable ignored) {}
        }
    }
}

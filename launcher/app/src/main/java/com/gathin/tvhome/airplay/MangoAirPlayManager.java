package com.gathin.tvhome.airplay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight native AirPlay receiver for Mango TV.
 *
 * Implements Apple mDNS discovery (_airplay._tcp and _raop._tcp) via Android's
 * native NsdManager and handles AirPlay HTTP/RTSP casting (photos, web video streams,
 * and playback controls) with zero native C++ runtime bloat.
 */
public class MangoAirPlayManager {
    private static final String TAG = "MangoAirPlayManager";
    public static final int AIRPLAY_PORT = 7000;

    public interface Listener {
        void onAirPlayClientConnected(String clientIp, String deviceName);
        void onPhotoReceived(Bitmap photo);
        void onVideoPlayRequested(String videoUrl, float startPosition);
        void onPlaybackRateChanged(float rate);
        void onPlaybackScrub(float position);
        void onAirPlayStopped();
    }

    private final Context context;
    private final Handler mainHandler;
    private final String serverName;
    private final String hardwareMac;
    private Listener listener;

    private boolean isRunning = false;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener airplayRegListener;
    private NsdManager.RegistrationListener raopRegListener;

    public MangoAirPlayManager(Context context, String serverName) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.serverName = (serverName != null && !serverName.isEmpty()) ? serverName : "Mango TV";
        this.hardwareMac = generateStableMac();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        threadPool = Executors.newCachedThreadPool();

        startHttpServer();
        registerMdnsServices();
        Log.i(TAG, "Mango AirPlay active on port " + AIRPLAY_PORT);
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;

        unregisterMdnsServices();

        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (Throwable ignored) {}

        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }

        Log.i(TAG, "Mango AirPlay stopped");
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void startHttpServer() {
        threadPool.execute(() -> {
            try {
                serverSocket = new ServerSocket(AIRPLAY_PORT);
                while (isRunning && !serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    if (!isRunning) {
                        socket.close();
                        break;
                    }
                    threadPool.execute(() -> handleConnection(socket));
                }
            } catch (Throwable t) {
                if (isRunning) {
                    Log.e(TAG, "AirPlay server error", t);
                }
            }
        });
    }

    private void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
            OutputStream out = socket.getOutputStream();

            while (isRunning && !socket.isClosed()) {
                String requestLine = readLine(in);
                if (requestLine == null || requestLine.isEmpty()) {
                    break;
                }

                String[] parts = requestLine.split(" ");
                if (parts.length < 2) break;

                String method = parts[0].toUpperCase(Locale.ROOT);
                String uri = parts[1];
                String protocol = (parts.length > 2) ? parts[2] : "HTTP/1.1";

                Map<String, String> headers = new HashMap<>();
                String headerLine;
                while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
                    int colon = headerLine.indexOf(':');
                    if (colon > 0) {
                        headers.put(headerLine.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                                headerLine.substring(colon + 1).trim());
                    }
                }

                long contentLength = 0;
                String cl = headers.get("content-length");
                if (cl != null) {
                    try {
                        contentLength = Long.parseLong(cl);
                    } catch (NumberFormatException ignored) {}
                }

                String userAgent = headers.get("user-agent");
                String clientName = (userAgent != null) ? userAgent : "Apple Device";
                String clientIp = socket.getInetAddress().getHostAddress();

                if ("OPTIONS".equals(method)) {
                    handleOptions(out, protocol, headers.get("cseq"));
                } else if ("GET".equals(method) && (uri.contains("/server-info") || uri.contains("/info"))) {
                    handleServerInfo(out, protocol);
                } else if ("POST".equals(method) && uri.startsWith("/photo") || "PUT".equals(method) && uri.startsWith("/photo")) {
                    handlePhoto(in, out, protocol, contentLength, clientIp, clientName);
                } else if ("POST".equals(method) && uri.startsWith("/play")) {
                    handlePlay(in, out, protocol, contentLength, clientIp, clientName);
                } else if ("POST".equals(method) && uri.startsWith("/scrub")) {
                    handleScrub(out, protocol, uri);
                } else if ("POST".equals(method) && uri.startsWith("/rate")) {
                    handleRate(out, protocol, uri);
                } else if ("POST".equals(method) && uri.startsWith("/stop")) {
                    handleStop(out, protocol);
                } else if ("SETUP".equals(method) || "RECORD".equals(method)) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onAirPlayClientConnected(clientIp, clientName);
                    });
                    sendGenericResponse(out, protocol, 200, "OK", headers.get("cseq"), null, null);
                } else if ("TEARDOWN".equals(method)) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onAirPlayStopped();
                    });
                    sendGenericResponse(out, protocol, 200, "OK", headers.get("cseq"), null, null);
                    break;
                } else {
                    sendGenericResponse(out, protocol, 200, "OK", headers.get("cseq"), null, null);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "AirPlay socket session exception: " + t.getMessage());
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {}
        }
    }

    private void handleOptions(OutputStream out, String protocol, String cseq) throws IOException {
        String resp = protocol + " 200 OK\r\n" +
                "Server: AirTunes/220.68\r\n" +
                (cseq != null ? "CSeq: " + cseq + "\r\n" : "") +
                "Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET, PUT\r\n" +
                "Content-Length: 0\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void handleServerInfo(OutputStream out, String protocol) throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n" +
                "<dict>\n" +
                "<key>deviceid</key>\n<string>" + hardwareMac + "</string>\n" +
                "<key>features</key>\n<integer>1518338039</integer>\n" +
                "<key>model</key>\n<string>AppleTV3,2</string>\n" +
                "<key>protovers</key>\n<string>1.0</string>\n" +
                "<key>srcvers</key>\n<string>220.68</string>\n" +
                "</dict>\n</plist>";
        sendGenericResponse(out, protocol, 200, "OK", null, "text/x-apple-plist+xml", xml);
    }

    private void handlePhoto(InputStream in, OutputStream out, String protocol, long contentLength, String clientIp, String clientName) throws IOException {
        if (contentLength > 0 && contentLength < 35 * 1024 * 1024) { // Up to 35MB photo
            byte[] photoData = readExactBytes(in, (int) contentLength);
            Bitmap bitmap = BitmapFactory.decodeByteArray(photoData, 0, photoData.length);
            if (bitmap != null) {
                mainHandler.post(() -> {
                    if (listener != null) {
                        listener.onAirPlayClientConnected(clientIp, clientName);
                        listener.onPhotoReceived(bitmap);
                    }
                });
            }
        }
        sendGenericResponse(out, protocol, 200, "OK", null, null, null);
    }

    private void handlePlay(InputStream in, OutputStream out, String protocol, long contentLength, String clientIp, String clientName) throws IOException {
        String body = "";
        if (contentLength > 0) {
            byte[] bytes = readExactBytes(in, (int) contentLength);
            body = new String(bytes, StandardCharsets.UTF_8);
        }

        String videoUrl = null;
        float startPos = 0.0f;

        if (body.contains("Content-Location:")) {
            for (String line : body.split("\n")) {
                if (line.startsWith("Content-Location:")) {
                    videoUrl = line.substring("Content-Location:".length()).trim();
                } else if (line.startsWith("Start-Position:")) {
                    try {
                        startPos = Float.parseFloat(line.substring("Start-Position:".length()).trim());
                    } catch (Exception ignored) {}
                }
            }
        } else if (body.contains("<string>http")) {
            int start = body.indexOf("<string>http");
            int end = body.indexOf("</string>", start);
            if (start > 0 && end > start) {
                videoUrl = body.substring(start + 8, end).trim();
            }
        }

        if (videoUrl != null) {
            final String finalUrl = videoUrl;
            final float finalPos = startPos;
            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onAirPlayClientConnected(clientIp, clientName);
                    listener.onVideoPlayRequested(finalUrl, finalPos);
                }
            });
        }

        sendGenericResponse(out, protocol, 200, "OK", null, null, null);
    }

    private void handleScrub(OutputStream out, String protocol, String uri) throws IOException {
        int posIdx = uri.indexOf("position=");
        if (posIdx > 0) {
            try {
                float pos = Float.parseFloat(uri.substring(posIdx + 9));
                mainHandler.post(() -> {
                    if (listener != null) listener.onPlaybackScrub(pos);
                });
            } catch (Exception ignored) {}
        }
        sendGenericResponse(out, protocol, 200, "OK", null, null, null);
    }

    private void handleRate(OutputStream out, String protocol, String uri) throws IOException {
        int rateIdx = uri.indexOf("value=");
        if (rateIdx > 0) {
            try {
                float rate = Float.parseFloat(uri.substring(rateIdx + 6));
                mainHandler.post(() -> {
                    if (listener != null) listener.onPlaybackRateChanged(rate);
                });
            } catch (Exception ignored) {}
        }
        sendGenericResponse(out, protocol, 200, "OK", null, null, null);
    }

    private void handleStop(OutputStream out, String protocol) throws IOException {
        mainHandler.post(() -> {
            if (listener != null) listener.onAirPlayStopped();
        });
        sendGenericResponse(out, protocol, 200, "OK", null, null, null);
    }

    private void sendGenericResponse(OutputStream out, String protocol, int code, String text, String cseq, String contentType, String body) throws IOException {
        byte[] bodyBytes = (body != null) ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        StringBuilder sb = new StringBuilder();
        sb.append(protocol).append(" ").append(code).append(" ").append(text).append("\r\n");
        sb.append("Server: AirTunes/220.68\r\n");
        if (cseq != null) sb.append("CSeq: ").append(cseq).append("\r\n");
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        sb.append("Connection: keep-alive\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        if (bodyBytes.length > 0) {
            out.write(bodyBytes);
        }
        out.flush();
    }

    private void registerMdnsServices() {
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;

            // 1. AirPlay video / screen mirroring service (_airplay._tcp)
            NsdServiceInfo airplayInfo = new NsdServiceInfo();
            airplayInfo.setServiceName(serverName);
            airplayInfo.setServiceType("_airplay._tcp");
            airplayInfo.setPort(AIRPLAY_PORT);
            airplayInfo.setAttribute("model", "AppleTV3,2");
            airplayInfo.setAttribute("features", "0x5A7FFFF7,0x1E");
            airplayInfo.setAttribute("srcvers", "220.68");
            airplayInfo.setAttribute("flags", "0x4");
            airplayInfo.setAttribute("vv", "2");
            airplayInfo.setAttribute("deviceid", hardwareMac);

            airplayRegListener = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "AirPlay mDNS registration failed: " + errorCode);
                }
                @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
                @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                    Log.i(TAG, "_airplay._tcp registered as " + serviceInfo.getServiceName());
                }
                @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
            };
            nsdManager.registerService(airplayInfo, NsdManager.PROTOCOL_DNS_SD, airplayRegListener);

            // 2. RAOP Remote Audio Output Protocol service (_raop._tcp)
            String cleanMac = hardwareMac.replace(":", "");
            NsdServiceInfo raopInfo = new NsdServiceInfo();
            raopInfo.setServiceName(cleanMac + "@" + serverName);
            raopInfo.setServiceType("_raop._tcp");
            raopInfo.setPort(AIRPLAY_PORT);
            raopInfo.setAttribute("ch", "2");
            raopInfo.setAttribute("cn", "0,1,2,3");
            raopInfo.setAttribute("et", "0,3,5");
            raopInfo.setAttribute("md", "0,1,2");
            raopInfo.setAttribute("pw", "false");
            raopInfo.setAttribute("sr", "44100");
            raopInfo.setAttribute("ss", "16");
            raopInfo.setAttribute("tp", "TCP,UDP");
            raopInfo.setAttribute("vn", "65537");
            raopInfo.setAttribute("vs", "220.68");
            raopInfo.setAttribute("da", "true");
            raopInfo.setAttribute("sv", "false");
            raopInfo.setAttribute("sf", "0x4");

            raopRegListener = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "RAOP mDNS registration failed: " + errorCode);
                }
                @Override public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
                @Override public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                    Log.i(TAG, "_raop._tcp registered as " + serviceInfo.getServiceName());
                }
                @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {}
            };
            nsdManager.registerService(raopInfo, NsdManager.PROTOCOL_DNS_SD, raopRegListener);
        } catch (Throwable t) {
            Log.e(TAG, "Error registering AirPlay mDNS services", t);
        }
    }

    private void unregisterMdnsServices() {
        if (nsdManager == null) return;
        try {
            if (airplayRegListener != null) {
                nsdManager.unregisterService(airplayRegListener);
                airplayRegListener = null;
            }
        } catch (Throwable ignored) {}
        try {
            if (raopRegListener != null) {
                nsdManager.unregisterService(raopRegListener);
                raopRegListener = null;
            }
        } catch (Throwable ignored) {}
    }

    private static String generateStableMac() {
        return "AA:BB:CC:DD:EE:01";
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
}

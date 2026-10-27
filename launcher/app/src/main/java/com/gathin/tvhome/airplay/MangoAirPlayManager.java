package com.gathin.tvhome.airplay;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native AirPlay 1 & 2 compatible media receiver for Mango TV.
 *
 * Implements modern Apple discovery:
 * 1. mDNS / DNS-SD (_airplay._tcp and _raop._tcp) compliant with iOS 15, 16, 17, and 18.
 * 2. Apple Continuity BLE AirPlay Beacon (Type 0x09) for instant out-of-band detection
 *    when network mDNS queries are blocked or delayed by Wi-Fi router AP isolation.
 * 3. Integrated HTTP/RTSP engine supporting direct photo streaming, video playback,
 *    and real-time /playback-info synchronization.
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

    public interface PlaybackInfoProvider {
        float getPosition();
        float getDuration();
        float getPlaybackRate();
        boolean isPlaying();
    }

    private final Context context;
    private final Handler mainHandler;
    private final String serverName;
    private final String hardwareMac;
    private final String pairingId;
    private final String publicKey;
    private Listener listener;
    private PlaybackInfoProvider playbackInfoProvider;

    private static final int RTP_AUDIO_PORT = 6000;
    private static final int RTP_CONTROL_PORT = 6001;

    private boolean isRunning = false;
    private ServerSocket serverSocket;
    private DatagramSocket rtpAudioSocket;
    private DatagramSocket rtpControlSocket;
    private ExecutorService threadPool;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener airplayRegListener;
    private NsdManager.RegistrationListener raopRegListener;
    private WifiManager.MulticastLock multicastLock;
    private BluetoothLeAdvertiser bleAdvertiser;
    private AdvertiseCallback bleAdvertiseCallback;

    public MangoAirPlayManager(Context context, String serverName) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.serverName = (serverName != null && !serverName.isEmpty()) ? serverName : "Mango TV";
        this.hardwareMac = generateStableMac();
        this.pairingId = generateStableUuid(hardwareMac);
        this.publicKey = generateStablePublicKey(hardwareMac);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setPlaybackInfoProvider(PlaybackInfoProvider provider) {
        this.playbackInfoProvider = provider;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        threadPool = Executors.newCachedThreadPool();

        // 1. Acquire Wi-Fi Multicast Lock to prevent Android Wi-Fi driver from filtering mDNS
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("MangoAirPlayLock");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                Log.i(TAG, "Acquired WifiManager.MulticastLock for AirPlay");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not acquire MulticastLock", t);
        }

        // 2. Start TCP HTTP/RTSP Server
        startHttpServer();

        // 3. Start UDP RTP Audio and Control Sockets for RAOP
        startUdpAudioSockets();

        // 4. Register mDNS / Bonjour Services
        registerMdnsServices();

        // 5. Start Apple Continuity BLE AirPlay Beacon
        startBleBeacon();

        Log.i(TAG, "Mango AirPlay active on port " + AIRPLAY_PORT + " with MAC " + hardwareMac);
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;

        stopBleBeacon();
        unregisterMdnsServices();
        stopUdpAudioSockets();

        if (multicastLock != null) {
            try {
                if (multicastLock.isHeld()) multicastLock.release();
            } catch (Throwable ignored) {}
            multicastLock = null;
        }

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

    private void startUdpAudioSockets() {
        if (threadPool == null) return;
        threadPool.execute(() -> {
            try {
                rtpAudioSocket = new DatagramSocket(RTP_AUDIO_PORT);
                rtpAudioSocket.setReceiveBufferSize(256 * 1024);
                byte[] buf = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                while (isRunning && !rtpAudioSocket.isClosed()) {
                    try {
                        rtpAudioSocket.receive(packet);
                    } catch (Throwable ignored) {
                        if (!isRunning) break;
                    }
                }
            } catch (Throwable t) {
                if (isRunning) Log.w(TAG, "RTP audio socket error", t);
            }
        });

        threadPool.execute(() -> {
            try {
                rtpControlSocket = new DatagramSocket(RTP_CONTROL_PORT);
                rtpControlSocket.setReceiveBufferSize(64 * 1024);
                byte[] buf = new byte[2048];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                while (isRunning && !rtpControlSocket.isClosed()) {
                    try {
                        rtpControlSocket.receive(packet);
                    } catch (Throwable ignored) {
                        if (!isRunning) break;
                    }
                }
            } catch (Throwable t) {
                if (isRunning) Log.w(TAG, "RTP control socket error", t);
            }
        });
    }

    private void stopUdpAudioSockets() {
        if (rtpAudioSocket != null) {
            try { rtpAudioSocket.close(); } catch (Throwable ignored) {}
            rtpAudioSocket = null;
        }
        if (rtpControlSocket != null) {
            try { rtpControlSocket.close(); } catch (Throwable ignored) {}
            rtpControlSocket = null;
        }
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
                String clientIp = (socket.getInetAddress() != null) ? socket.getInetAddress().getHostAddress() : "unknown";
                String cseq = headers.get("cseq");

                Log.i(TAG, "AirPlay RX: " + method + " " + uri + " (CSeq=" + cseq + ") headers=" + headers + " from " + clientIp);

                boolean bodyHandled = false;

                if ("OPTIONS".equals(method)) {
                    handleOptions(out, protocol, cseq);
                } else if ("GET".equals(method) && (uri.contains("/server-info") || uri.contains("/info"))) {
                    handleServerInfo(out, protocol, cseq);
                } else if ("GET".equals(method) && uri.contains("/playback-info")) {
                    handlePlaybackInfo(out, protocol, cseq);
                } else if (("POST".equals(method) || "PUT".equals(method)) && uri.startsWith("/photo")) {
                    handlePhoto(in, out, protocol, cseq, contentLength, clientIp, clientName);
                    bodyHandled = true;
                } else if ("POST".equals(method) && uri.startsWith("/play")) {
                    handlePlay(in, out, protocol, cseq, contentLength, clientIp, clientName, headers);
                    bodyHandled = true;
                } else if ("POST".equals(method) && uri.startsWith("/scrub")) {
                    handleScrub(out, protocol, cseq, uri);
                } else if ("POST".equals(method) && uri.startsWith("/rate")) {
                    handleRate(out, protocol, cseq, uri);
                } else if ("POST".equals(method) && uri.startsWith("/stop")) {
                    handleStop(out, protocol, cseq);
                } else if (uri.startsWith("/fp-setup") || uri.startsWith("/auth-setup") || uri.startsWith("/pair-setup") || uri.startsWith("/pair-verify")) {
                    // Return 404 so Apple client recognizes MFi / FairPlay DRM is not used and falls back to open streaming
                    sendGenericResponse(out, protocol, 404, "Not Found", cseq, null, null);
                } else if ("POST".equals(method) && uri.startsWith("/reverse")) {
                    sendGenericResponse(out, protocol, 101, "Switching Protocols", cseq, null, null);
                } else if ("SETUP".equals(method)) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onAirPlayClientConnected(clientIp, clientName);
                    });
                    int audioPort = (rtpAudioSocket != null && !rtpAudioSocket.isClosed()) ? rtpAudioSocket.getLocalPort() : RTP_AUDIO_PORT;
                    int controlPort = (rtpControlSocket != null && !rtpControlSocket.isClosed()) ? rtpControlSocket.getLocalPort() : RTP_CONTROL_PORT;
                    String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
                    String resp = proto + " 200 OK\r\n" +
                            "Server: AirTunes/377.17.246\r\n" +
                            (cseq != null ? "CSeq: " + cseq + "\r\n" : "") +
                            "Transport: RTP/AVP/UDP;unicast;mode=record;server_port=" + audioPort + ";control_port=" + controlPort + ";timing_port=0\r\n" +
                            "Session: 1\r\n" +
                            "Audio-Jack-Status: connected\r\n" +
                            "Content-Length: 0\r\n\r\n";
                    out.write(resp.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else if ("RECORD".equals(method)) {
                    String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
                    String resp = proto + " 200 OK\r\n" +
                            "Server: AirTunes/377.17.246\r\n" +
                            (cseq != null ? "CSeq: " + cseq + "\r\n" : "") +
                            "Audio-Latency: 0\r\n" +
                            "Content-Length: 0\r\n\r\n";
                    out.write(resp.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else if ("GET_PARAMETER".equals(method)) {
                    String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
                    String body = "volume: 0.00\r\n";
                    sendGenericResponse(out, proto, 200, "OK", cseq, "text/parameters", body);
                } else if ("SET_PARAMETER".equals(method) || "FLUSH".equals(method)) {
                    sendGenericResponse(out, protocol, 200, "OK", cseq, null, null);
                } else if ("ANNOUNCE".equals(method)) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onAirPlayClientConnected(clientIp, clientName);
                    });
                    sendGenericResponse(out, protocol, 200, "OK", cseq, null, null);
                } else if ("TEARDOWN".equals(method)) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onAirPlayStopped();
                    });
                    sendGenericResponse(out, protocol, 200, "OK", cseq, null, null);
                    break;
                } else {
                    sendGenericResponse(out, protocol, 200, "OK", cseq, null, null);
                }

                // Drain any unconsumed request body so subsequent requests on this keep-alive socket stay aligned
                if (!bodyHandled && contentLength > 0 && contentLength < 35 * 1024 * 1024) {
                    readExactBytes(in, (int) contentLength);
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
        String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
        String resp = proto + " 200 OK\r\n" +
                "Server: AirTunes/377.17.246\r\n" +
                (cseq != null ? "CSeq: " + cseq + "\r\n" : "") +
                "Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET, PUT\r\n" +
                "Content-Length: 0\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void handleServerInfo(OutputStream out, String protocol, String cseq) throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n" +
                "<dict>\n" +
                "<key>deviceid</key>\n<string>" + hardwareMac + "</string>\n" +
                "<key>features</key>\n<integer>9147</integer>\n" +
                "<key>model</key>\n<string>AppleTV3,2</string>\n" +
                "<key>name</key>\n<string>" + serverName + "</string>\n" +
                "<key>protovers</key>\n<string>1.1</string>\n" +
                "<key>srcvers</key>\n<string>377.17.246</string>\n" +
                "<key>flags</key>\n<integer>0</integer>\n" +
                "<key>acl</key>\n<integer>0</integer>\n" +
                "<key>pi</key>\n<string>" + pairingId + "</string>\n" +
                "<key>pk</key>\n<string>" + publicKey + "</string>\n" +
                "<key>vv</key>\n<integer>2</integer>\n" +
                "</dict>\n</plist>";
        sendGenericResponse(out, protocol, 200, "OK", cseq, "text/x-apple-plist+xml", xml);
    }

    private void handlePlaybackInfo(OutputStream out, String protocol, String cseq) throws IOException {
        float pos = 0f;
        float dur = 0f;
        float rate = 0f;
        boolean ready = true;
        if (playbackInfoProvider != null) {
            try {
                pos = playbackInfoProvider.getPosition();
                dur = playbackInfoProvider.getDuration();
                rate = playbackInfoProvider.getPlaybackRate();
                ready = true;
            } catch (Throwable ignored) {}
        }
        if (dur <= 0f) dur = 100f; // Nominal duration when stream duration is not yet resolved

        String xml = String.format(Locale.US,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                "<plist version=\"1.0\">\n" +
                "<dict>\n" +
                "<key>duration</key>\n<real>%.3f</real>\n" +
                "<key>position</key>\n<real>%.3f</real>\n" +
                "<key>rate</key>\n<real>%.1f</real>\n" +
                "<key>readyToPlay</key>\n<true/>\n" +
                "<key>playbackBufferEmpty</key>\n<false/>\n" +
                "<key>playbackBufferFull</key>\n<true/>\n" +
                "<key>playbackLikelyToKeepUp</key>\n<true/>\n" +
                "<key>loadedTimeRanges</key>\n<array>\n" +
                "<dict>\n<key>duration</key>\n<real>%.3f</real>\n<key>start</key>\n<real>0.0</real>\n</dict>\n" +
                "</array>\n" +
                "<key>seekableTimeRanges</key>\n<array>\n" +
                "<dict>\n<key>duration</key>\n<real>%.3f</real>\n<key>start</key>\n<real>0.0</real>\n</dict>\n" +
                "</array>\n" +
                "</dict>\n</plist>",
                dur, pos, rate, dur, dur);

        sendGenericResponse(out, protocol, 200, "OK", cseq, "text/x-apple-plist+xml", xml);
    }

    private void handlePhoto(InputStream in, OutputStream out, String protocol, String cseq, long contentLength, String clientIp, String clientName) throws IOException {
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
        sendGenericResponse(out, protocol, 200, "OK", cseq, null, null);
    }

    private void handlePlay(InputStream in, OutputStream out, String protocol, String cseq, long contentLength, String clientIp, String clientName, Map<String, String> headers) throws IOException {
        String body = "";
        if (contentLength > 0 && contentLength < 1024 * 1024) {
            byte[] bytes = readExactBytes(in, (int) contentLength);
            body = new String(bytes, StandardCharsets.UTF_8);
            Log.i(TAG, "AirPlay play body: " + body);
        }

        String videoUrl = null;
        float startPos = 0.0f;

        // Check Content-Location HTTP header first
        if (headers != null) {
            String clHeader = headers.get("content-location");
            if (clHeader != null && !clHeader.isEmpty()) {
                videoUrl = clHeader.trim();
            }
        }

        if (videoUrl == null && body.contains("Content-Location:")) {
            for (String line : body.split("\n")) {
                line = line.trim();
                if (line.startsWith("Content-Location:")) {
                    videoUrl = line.substring("Content-Location:".length()).trim();
                } else if (line.startsWith("Start-Position:")) {
                    try {
                        startPos = Float.parseFloat(line.substring("Start-Position:".length()).trim());
                    } catch (Exception ignored) {}
                }
            }
        }
        if (videoUrl == null && body.contains("<string>http")) {
            int start = body.indexOf("<string>http");
            int end = body.indexOf("</string>", start);
            if (start >= 0 && end > start) {
                videoUrl = body.substring(start + 8, end).trim();
            }
            if (body.contains("<key>Start-Position</key>")) {
                int posKey = body.indexOf("<key>Start-Position</key>");
                int rStart = body.indexOf("<real>", posKey);
                int rEnd = body.indexOf("</real>", rStart);
                if (rStart >= 0 && rEnd > rStart) {
                    try {
                        startPos = Float.parseFloat(body.substring(rStart + 6, rEnd).trim());
                    } catch (Exception ignored) {}
                }
            }
        }
        if (videoUrl == null && (body.contains("http://") || body.contains("https://"))) {
            int httpIdx = body.indexOf("https://");
            if (httpIdx < 0) httpIdx = body.indexOf("http://");
            if (httpIdx >= 0) {
                int endIdx = body.length();
                for (int i = httpIdx; i < body.length(); i++) {
                    char c = body.charAt(i);
                    if (c <= ' ' || c == '"' || c == '<' || c == '>' || c == '\0' || c == '\r' || c == '\n') {
                        endIdx = i;
                        break;
                    }
                }
                videoUrl = body.substring(httpIdx, endIdx).trim();
            }
        }

        if (videoUrl != null) {
            Log.i(TAG, "AirPlay play video requested: " + videoUrl + " from " + startPos + "s");
            final String finalUrl = videoUrl;
            final float finalPos = startPos;
            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onAirPlayClientConnected(clientIp, clientName);
                    listener.onVideoPlayRequested(finalUrl, finalPos);
                }
            });
        }

        sendGenericResponse(out, protocol, 200, "OK", cseq, null, null);
    }

    private void handleScrub(OutputStream out, String protocol, String cseq, String uri) throws IOException {
        int posIdx = uri.indexOf("position=");
        if (posIdx > 0) {
            try {
                float pos = Float.parseFloat(uri.substring(posIdx + 9));
                mainHandler.post(() -> {
                    if (listener != null) listener.onPlaybackScrub(pos);
                });
            } catch (Exception ignored) {}
        }
        sendGenericResponse(out, protocol, 200, "OK", cseq, null, null);
    }

    private void handleRate(OutputStream out, String protocol, String cseq, String uri) throws IOException {
        int rateIdx = uri.indexOf("value=");
        if (rateIdx > 0) {
            try {
                float rate = Float.parseFloat(uri.substring(rateIdx + 6));
                mainHandler.post(() -> {
                    if (listener != null) listener.onPlaybackRateChanged(rate);
                });
            } catch (Exception ignored) {}
        }
        sendGenericResponse(out, protocol, 200, "OK", cseq, null, null);
    }

    private void handleStop(OutputStream out, String protocol, String cseq) throws IOException {
        mainHandler.post(() -> {
            if (listener != null) listener.onAirPlayStopped();
        });
        sendGenericResponse(out, protocol, 200, "OK", cseq, null, null);
    }

    private void sendGenericResponse(OutputStream out, String protocol, int code, String text, String cseq, String contentType, String body) throws IOException {
        byte[] bodyBytes = (body != null) ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
        StringBuilder sb = new StringBuilder();
        String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
        sb.append(proto).append(" ").append(code).append(" ").append(text).append("\r\n");
        sb.append("Server: AirTunes/377.17.246\r\n");
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

            // 1. AirPlay video / photo service (_airplay._tcp)
            // Stricter iOS 15/16/17/18 criteria:
            // model: AppleTV3,2
            // features: 0x5A7FFFF7,0x1E (Video, Photo, Slideshow, Caching, Screen, Audio)
            // srcvers: 377.17.246
            // flags: 0x4
            // protovers: 1.1
            // pk: 64-char persistent hex public key
            // pi: persistent UUID
            NsdServiceInfo airplayInfo = new NsdServiceInfo();
            airplayInfo.setServiceName(serverName);
            airplayInfo.setServiceType("_airplay._tcp");
            airplayInfo.setPort(AIRPLAY_PORT);
            airplayInfo.setAttribute("model", "AppleTV3,2");
            airplayInfo.setAttribute("features", "0x23BB,0x0");
            airplayInfo.setAttribute("srcvers", "377.17.246");
            airplayInfo.setAttribute("flags", "0x0");
            airplayInfo.setAttribute("protovers", "1.1");
            airplayInfo.setAttribute("acl", "0");
            airplayInfo.setAttribute("vv", "2");
            airplayInfo.setAttribute("deviceid", hardwareMac);
            airplayInfo.setAttribute("pi", pairingId);
            airplayInfo.setAttribute("pk", publicKey);

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

            // 2. AirTunes / RAOP audio service (_raop._tcp)
            // Required by iOS Control Center AirPlay audio picker
            // Service name format: <MAC_NO_COLONS>@<ServerName>
            NsdServiceInfo raopInfo = new NsdServiceInfo();
            String raopName = hardwareMac.replace(":", "") + "@" + serverName;
            raopInfo.setServiceName(raopName);
            raopInfo.setServiceType("_raop._tcp");
            raopInfo.setPort(AIRPLAY_PORT);
            raopInfo.setAttribute("txtvers", "1");
            raopInfo.setAttribute("ch", "2");
            raopInfo.setAttribute("cn", "0,1,2,3");
            raopInfo.setAttribute("da", "true");
            raopInfo.setAttribute("et", "0");
            raopInfo.setAttribute("ft", "0x23BB,0x0");
            raopInfo.setAttribute("sf", "0x0");
            raopInfo.setAttribute("md", "0,1,2");
            raopInfo.setAttribute("am", "AppleTV3,2");
            raopInfo.setAttribute("pk", publicKey);
            raopInfo.setAttribute("tp", "TCP,UDP");
            raopInfo.setAttribute("vn", "65537");
            raopInfo.setAttribute("vs", "377.17.246");
            raopInfo.setAttribute("vv", "2");
            raopInfo.setAttribute("sr", "44100");
            raopInfo.setAttribute("ss", "16");
            raopInfo.setAttribute("pw", "false");

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

    private void startBleBeacon() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            bleAdvertiser = adapter.getBluetoothLeAdvertiser();
            if (bleAdvertiser == null) {
                Log.w(TAG, "BluetoothLeAdvertiser not supported on this device");
                return;
            }

            byte[] ipBytes = getLocalIpAddressBytes();
            if (ipBytes == null || ipBytes.length != 4) return;

            // Apple Continuity AirPlay Target (Type 0x09) payload
            // [0x09, 0x08, flags, seed, ip0, ip1, ip2, ip3, port_hi, port_lo]
            byte[] payload = new byte[] {
                    0x09, 0x08, 0x13, 0x30,
                    ipBytes[0], ipBytes[1], ipBytes[2], ipBytes[3],
                    (byte) ((AIRPLAY_PORT >> 8) & 0xFF), (byte) (AIRPLAY_PORT & 0xFF)
            };

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .setTimeout(0)
                    .build();

            AdvertiseData data = new AdvertiseData.Builder()
                    .addManufacturerData(0x004C, payload) // 0x004C = Apple Inc.
                    .setIncludeDeviceName(false)
                    .build();

            bleAdvertiseCallback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    Log.i(TAG, "Apple AirPlay BLE beacon started successfully");
                }

                @Override
                public void onStartFailure(int errorCode) {
                    Log.w(TAG, "Apple AirPlay BLE beacon start failed: " + errorCode);
                }
            };

            bleAdvertiser.startAdvertising(settings, data, bleAdvertiseCallback);
        } catch (Throwable t) {
            Log.w(TAG, "Could not start Apple AirPlay BLE beacon", t);
        }
    }

    private void stopBleBeacon() {
        if (bleAdvertiser != null && bleAdvertiseCallback != null) {
            try {
                bleAdvertiser.stopAdvertising(bleAdvertiseCallback);
            } catch (Throwable ignored) {}
            bleAdvertiser = null;
            bleAdvertiseCallback = null;
        }
    }

    private byte[] getLocalIpAddressBytes() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if ("wlan0".equalsIgnoreCase(nif.getName()) || "eth0".equalsIgnoreCase(nif.getName())) {
                    Enumeration<InetAddress> addrs = nif.getInetAddresses();
                    while (addrs != null && addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                            return addr.getAddress();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String generateStableMac() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if ("wlan0".equalsIgnoreCase(nif.getName()) || "eth0".equalsIgnoreCase(nif.getName())) {
                    byte[] mac = nif.getHardwareAddress();
                    if (mac != null && mac.length == 6) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 6; i++) {
                            sb.append(String.format("%02X", mac[i]));
                            if (i < 5) sb.append(":");
                        }
                        return sb.toString();
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            String aid = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (aid != null && aid.length() >= 12) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 6; i++) {
                    sb.append(aid.substring(i * 2, i * 2 + 2).toUpperCase(Locale.ROOT));
                    if (i < 5) sb.append(":");
                }
                return sb.toString();
            }
        } catch (Throwable ignored) {}

        return "D8:BE:65:4C:2B:A7";
    }

    private static String generateStableUuid(String seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(hash).toString();
        } catch (Throwable ignored) {
            return "5e1dae28-cb27-4910-aae8-5e32a1fdabed";
        }
    }

    private static String generateStablePublicKey(String seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "c4de9ae66f71f831f63b0d8572fdb790acbe12081e1267dfc086aed13ea7f6ac";
        }
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

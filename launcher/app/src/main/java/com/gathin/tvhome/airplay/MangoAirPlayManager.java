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
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;

import com.gathin.tvhome.airplay.alac.AirPlayAudioPlayer;

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

    // AirPort Express RSA private key (used by open-source AirPlay receivers like shairport-sync
    // for Apple-Challenge / Apple-Response authentication).
    // Without this, iOS drops the RAOP audio streaming connection immediately after OPTIONS.
    private static final String AIRPORT_RSA_KEY_B64 =
            "MIIEpQIBAAKCAQEA59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUt" +
            "wC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4U" +
            "wPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf" +
            "/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/" +
            "UAaHqn9JdsBWLUEpVviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hEewIDAQABAoIBAQDl8Axy9XfW" +
            "BLmkzkEiqoSwF0PsmVrPzH9KsnwLGH+QZlvjWd8SWYGN7u1507HvhF5N3drJoVU3O14nDY4TFQAa" +
            "LlJ9VM35AApXaLyY1ERrN7u9ALKd2LUwYhM7Km539O4yUFYikE2nIPscEsA5ltpxOgUGCY7b7ez5" +
            "NtD6nL1ZKauw7aNXmVAvmJTcuPxWmoktF3gDJKK2wxZuNGcJE0uFQEG4Z3BrWP7yoNuSK3dii2jm" +
            "lpPHr0O/KnPQtzI3eguhe0TwUem/eYSdyzMyVx/YpwkzwtYL3sR5k0o9rKQLtvLzfAqdBxBurciz" +
            "aaA/L0HIgAmOit1GJA2saMxTVPNhAoGBAPfgv1oeZxgxmotiCcMXFEQEWflzhWYTsXrhUIuz5jFu" +
            "a39GLS99ZEErhLdrwj8rDDViRVJ5skOp9zFvlYAHs0xh92ji1E7V/ysnKBfsMrPkk5KSKPrnjndM" +
            "oPdevWnVkgJ5jxFuNgxkOLMuG9i53B4yMvDTCRiIPMQ++N2iLDaRAoGBAO9v//mU8eVkQaoANf0Z" +
            "oMjW8CN4xwWA2cSEIHkd9AfFkftuv8oyLDCG3ZAf0vrhrrtkrfa7ef+AUb69DNggq4mHQAYBp7L+" +
            "k5DKzJrKuO0r+R0YbY9pZD1+/g9dVt91d6LQNepUE/yY2PP5CNoFmjedpLHMOPFdVgqDzDFxU8hL" +
            "AoGBANDrr7xAJbqBjHVwIzQ4To9pb4BNeqDndk5Qe7fT3+/H1njGaC0/rXE0Qb7q5ySgnsCb3DvA" +
            "cJyRM9SJ7OKlGt0FMSdJD5KG0XPIpAVNwgpXXH5MDJg09KHeh0kXo+QA6viFBi21y340NonnEfdf" +
            "54PX4ZGS/Xac1UK+pLkBB+zRAoGAf0AY3H3qKS2lMEI4bzEFoHeK3G895pDaK3TFBVmD7fV0Zhov" +
            "17fegFPMwOII8MisYm9ZfT2Z0s5Ro3s5rkt+nvLAdfC/PYPKzTLalpGSwomSNYJcB9HNMlmhkGzc" +
            "1JnLYT4iyUyx6pcZBmCd8bD0iwY/FzcgNDaUmbX9+XDvRA0CgYEAkE7pIPlE71qvfJQgoA9em0gI" +
            "LAuE4Pu13aKiJnfft7hIjbK+5kyb3TysZvoyDnb3HOKvInK7vXbKuU4ISgxB2bB3HcYzQMGsz1qJ" +
            "2gG0N5hvJpzwwhbhXqFKA4zaaSrw622wDniAK5MlIE0tIAKKP4yxNGjoD2QYjhBGuhvkWKY=";

    private static BigInteger RSA_N;
    private static BigInteger RSA_D;
    private static PrivateKey rsaPrivateKey;

    static {
        try {
            byte[] der = android.util.Base64.decode(AIRPORT_RSA_KEY_B64, android.util.Base64.DEFAULT);
            int idx = 0;
            if ((der[idx] & 0xFF) == 0x30) {
                idx++;
                if ((der[idx] & 0x80) != 0) {
                    idx += 1 + (der[idx] & 0x7F);
                } else {
                    idx++;
                }
            }
            List<byte[]> ints = new ArrayList<>();
            while (idx < der.length && ints.size() < 9) {
                if ((der[idx] & 0xFF) == 0x02) {
                    idx++;
                    int len;
                    if ((der[idx] & 0x80) != 0) {
                        int nbytes = der[idx] & 0x7F;
                        len = 0;
                        for (int k = 0; k < nbytes; k++) {
                            len = (len << 8) | (der[idx + 1 + k] & 0xFF);
                        }
                        idx += 1 + nbytes;
                    } else {
                        len = der[idx] & 0xFF;
                        idx++;
                    }
                    byte[] val = new byte[len];
                    System.arraycopy(der, idx, val, 0, len);
                    ints.add(val);
                    idx += len;
                } else {
                    break;
                }
            }
            if (ints.size() >= 9) {
                RSA_N = new BigInteger(1, ints.get(1));
                RSA_D = new BigInteger(1, ints.get(3));
                RSAPrivateCrtKeySpec spec = new RSAPrivateCrtKeySpec(
                        RSA_N,
                        new BigInteger(1, ints.get(2)), // e
                        RSA_D,                          // d
                        new BigInteger(1, ints.get(4)), // p
                        new BigInteger(1, ints.get(5)), // q
                        new BigInteger(1, ints.get(6)), // dP
                        new BigInteger(1, ints.get(7)), // dQ
                        new BigInteger(1, ints.get(8))  // qInv
                );
                KeyFactory kf = KeyFactory.getInstance("RSA");
                rsaPrivateKey = kf.generatePrivate(spec);
                Log.i(TAG, "AirPort Express RSA private key loaded successfully (" + RSA_N.bitLength() + " bits)");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to parse AirPort RSA key", t);
        }
    }

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
    private static final int RTP_TIMING_PORT = 6002;

    private boolean isRunning = false;
    private ServerSocket serverSocket;
    private DatagramSocket rtpAudioSocket;
    private DatagramSocket rtpControlSocket;
    private DatagramSocket rtpTimingSocket;
    private ExecutorService threadPool;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener airplayRegListener;
    private NsdManager.RegistrationListener raopRegListener;
    private WifiManager.MulticastLock multicastLock;
    private BluetoothLeAdvertiser bleAdvertiser;
    private AdvertiseCallback bleAdvertiseCallback;
    private final AirPlayAudioPlayer audioPlayer = new AirPlayAudioPlayer();

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
        audioPlayer.stop();

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
                if (rtpAudioSocket != null && !rtpAudioSocket.isClosed()) {
                    rtpAudioSocket.close();
                }
                rtpAudioSocket = new DatagramSocket(null);
                rtpAudioSocket.setReuseAddress(true);
                rtpAudioSocket.bind(new InetSocketAddress(RTP_AUDIO_PORT));
                rtpAudioSocket.setReceiveBufferSize(512 * 1024);
                byte[] buf = new byte[4096];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                Log.i(TAG, "RTP audio socket bound to UDP port " + RTP_AUDIO_PORT);
                long rxPackets = 0;
                while (isRunning && !rtpAudioSocket.isClosed()) {
                    try {
                        packet.setLength(buf.length);
                        rtpAudioSocket.receive(packet);
                        rxPackets++;
                        if (rxPackets == 1 || rxPackets % 500 == 0) {
                            Log.i(TAG, "RTP audio packet RX count=" + rxPackets + " len=" + packet.getLength() + " from " + packet.getAddress());
                        }
                        audioPlayer.processRtpAudioPacket(packet.getData(), packet.getOffset(), packet.getLength());
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
                if (rtpControlSocket != null && !rtpControlSocket.isClosed()) {
                    rtpControlSocket.close();
                }
                rtpControlSocket = new DatagramSocket(null);
                rtpControlSocket.setReuseAddress(true);
                rtpControlSocket.bind(new InetSocketAddress(RTP_CONTROL_PORT));
                rtpControlSocket.setReceiveBufferSize(64 * 1024);
                byte[] buf = new byte[2048];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                Log.i(TAG, "RTP control socket bound to UDP port " + RTP_CONTROL_PORT);
                long ctrlPackets = 0;
                while (isRunning && !rtpControlSocket.isClosed()) {
                    try {
                        packet.setLength(buf.length);
                        rtpControlSocket.receive(packet);
                        ctrlPackets++;
                        if (ctrlPackets == 1 || ctrlPackets % 50 == 0) {
                            int pt = (packet.getLength() > 1) ? (packet.getData()[packet.getOffset() + 1] & 0x7F) : -1;
                            Log.i(TAG, "RTP control packet RX count=" + ctrlPackets + ", PT=" + pt);
                        }
                    } catch (Throwable ignored) {
                        if (!isRunning) break;
                    }
                }
            } catch (Throwable t) {
                if (isRunning) Log.w(TAG, "RTP control socket error", t);
            }
        });

        threadPool.execute(() -> {
            try {
                if (rtpTimingSocket != null && !rtpTimingSocket.isClosed()) {
                    rtpTimingSocket.close();
                }
                rtpTimingSocket = new DatagramSocket(null);
                rtpTimingSocket.setReuseAddress(true);
                rtpTimingSocket.bind(new InetSocketAddress(RTP_TIMING_PORT));
                rtpTimingSocket.setReceiveBufferSize(64 * 1024);
                byte[] buf = new byte[128];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                Log.i(TAG, "RTP timing socket bound to UDP port " + RTP_TIMING_PORT);
                long timingCount = 0;
                while (isRunning && !rtpTimingSocket.isClosed()) {
                    try {
                        packet.setLength(buf.length);
                        rtpTimingSocket.receive(packet);
                        timingCount++;
                        if (timingCount == 1 || timingCount % 20 == 0) {
                            Log.i(TAG, "RTP timing packet RX count=" + timingCount + " from " + packet.getAddress() + ":" + packet.getPort());
                        }
                        handleTimingPacket(packet);
                    } catch (Throwable ignored) {
                        if (!isRunning) break;
                    }
                }
            } catch (Throwable t) {
                if (isRunning) Log.w(TAG, "RTP timing socket error", t);
            }
        });
    }

    private void handleTimingPacket(DatagramPacket packet) {
        int len = packet.getLength();
        byte[] data = packet.getData();
        if (len < 32 || data == null) return;

        // PT 82 (Timing Query from client): (data[1] & 0x7F) == 82
        int pt = data[1] & 0x7F;
        if (pt == 82) {
            long nowMillis = System.currentTimeMillis();
            long ntpSecs = (nowMillis / 1000L) + 2208988800L;
            long ntpFrac = ((nowMillis % 1000L) * 0x100000000L) / 1000L;
            long ntpTimestamp = (ntpSecs << 32) | (ntpFrac & 0xFFFFFFFFL);

            byte[] reply = new byte[32];
            reply[0] = (byte) 0x80; // RTP v2
            reply[1] = (byte) 0xD3; // Marker bit + PT 83 (0x80 | 83 = 0xD3)
            reply[2] = data[2];     // Echo sequence number
            reply[3] = data[3];
            // 4 bytes timestamp/padding
            reply[4] = 0; reply[5] = 0; reply[6] = 0; reply[7] = 0;

            // Bytes 8-15: Originate timestamp (copy client's transmit timestamp from query bytes 24-31)
            System.arraycopy(data, 24, reply, 8, 8);

            // Bytes 16-23: Receive timestamp (server time when query arrived)
            writeLongBE(reply, 16, ntpTimestamp);

            // Bytes 24-31: Transmit timestamp (server time when reply leaves)
            writeLongBE(reply, 24, ntpTimestamp);

            try {
                DatagramPacket replyPacket = new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort());
                if (rtpTimingSocket != null && !rtpTimingSocket.isClosed()) {
                    rtpTimingSocket.send(replyPacket);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to send timing reply", t);
            }
        }
    }

    private void sendTimingQueryToClient(InetAddress clientAddr, int clientTimingPort) {
        if (threadPool == null) return;
        threadPool.execute(() -> {
            try {
                long nowMillis = System.currentTimeMillis();
                long ntpSecs = (nowMillis / 1000L) + 2208988800L;
                long ntpFrac = ((nowMillis % 1000L) * 0x100000000L) / 1000L;
                long ntpTimestamp = (ntpSecs << 32) | (ntpFrac & 0xFFFFFFFFL);

                byte[] query = new byte[32];
                query[0] = (byte) 0x80; // RTP v2
                query[1] = (byte) 0xD2; // Marker + PT 82 (0x80 | 82 = 0xD2)
                query[2] = 0;           // Sequence number
                query[3] = 1;
                // Transmit timestamp at bytes 24-31
                writeLongBE(query, 24, ntpTimestamp);

                DatagramPacket qPacket = new DatagramPacket(query, query.length, clientAddr, clientTimingPort);
                if (rtpTimingSocket != null && !rtpTimingSocket.isClosed()) {
                    rtpTimingSocket.send(qPacket);
                    Log.i(TAG, "Sent initial timing query to client " + clientAddr + ":" + clientTimingPort);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to send timing query to client", t);
            }
        });
    }

    private static void writeLongBE(byte[] b, int offset, long val) {
        b[offset]     = (byte) ((val >> 56) & 0xFF);
        b[offset + 1] = (byte) ((val >> 48) & 0xFF);
        b[offset + 2] = (byte) ((val >> 40) & 0xFF);
        b[offset + 3] = (byte) ((val >> 32) & 0xFF);
        b[offset + 4] = (byte) ((val >> 24) & 0xFF);
        b[offset + 5] = (byte) ((val >> 16) & 0xFF);
        b[offset + 6] = (byte) ((val >> 8)  & 0xFF);
        b[offset + 7] = (byte) (val & 0xFF);
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
        if (rtpTimingSocket != null) {
            try { rtpTimingSocket.close(); } catch (Throwable ignored) {}
            rtpTimingSocket = null;
        }
    }

    private void handleConnection(Socket socket) {
        boolean isRtspAudioSession = false;
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
                String appleDeviceName = headers.get("x-apple-device-name");
                String clientName;
                if (appleDeviceName != null && !appleDeviceName.isEmpty()) {
                    clientName = appleDeviceName;
                } else if (userAgent != null && userAgent.toLowerCase(Locale.ROOT).contains("airplay")) {
                    clientName = "iPhone";
                } else if (userAgent != null) {
                    clientName = userAgent;
                } else {
                    clientName = "Apple Device";
                }
                String clientIp = (socket.getInetAddress() != null) ? socket.getInetAddress().getHostAddress() : "unknown";
                String cseq = headers.get("cseq");

                Log.i(TAG, "AirPlay RX: " + method + " " + uri + " (CSeq=" + cseq + ") headers=" + headers + " from " + clientIp);

                boolean bodyHandled = false;

                if ("OPTIONS".equals(method)) {
                    handleOptions(out, protocol, cseq, headers, socket);
                } else if ("GET".equals(method) && (uri.contains("/server-info") || uri.contains("/info"))) {
                    handleServerInfo(out, protocol, cseq, uri);
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
                } else if ("ANNOUNCE".equals(method)) {
                    isRtspAudioSession = true;
                    mainHandler.post(() -> {
                        if (listener != null) listener.onAirPlayClientConnected(clientIp, clientName);
                    });
                    String sdp = "";
                    if (contentLength > 0 && contentLength < 65536) {
                        byte[] bodyBytes = readExactBytes(in, (int) contentLength);
                        sdp = new String(bodyBytes, StandardCharsets.UTF_8);
                        bodyHandled = true;
                    }
                    Log.i(TAG, "AirPlay ANNOUNCE SDP received:\n" + sdp);

                    byte[] aesKey = null;
                    byte[] aesIv = null;
                    String fmtp = null;
                    boolean isAlac = true;

                    for (String line : sdp.split("\r?\n")) {
                        line = line.trim();
                        if (line.startsWith("a=rsaaeskey:")) {
                            String b64Key = line.substring("a=rsaaeskey:".length()).trim();
                            aesKey = decryptRsaKey(b64Key);
                        } else if (line.startsWith("a=aesiv:")) {
                            String b64Iv = line.substring("a=aesiv:".length()).trim();
                            try {
                                aesIv = android.util.Base64.decode(b64Iv, android.util.Base64.DEFAULT);
                            } catch (Throwable t) {
                                Log.w(TAG, "Failed to decode aesiv", t);
                            }
                        } else if (line.startsWith("a=fmtp:")) {
                            fmtp = line.substring("a=fmtp:".length()).trim();
                        } else if (line.startsWith("a=rtpmap:")) {
                            String rtpmap = line.substring("a=rtpmap:".length()).trim();
                            if (rtpmap.contains("AppleLossless")) {
                                isAlac = true;
                            } else if (rtpmap.contains("L16")) {
                                isAlac = false;
                            }
                        }
                    }

                    audioPlayer.setSession(aesKey, aesIv, fmtp, isAlac);

                    String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
                    StringBuilder respSb = new StringBuilder();
                    respSb.append(proto).append(" 200 OK\r\n");
                    respSb.append("Server: AirTunes/377.17.246\r\n");
                    if (cseq != null) respSb.append("CSeq: ").append(cseq).append("\r\n");
                    String challenge = headers.get("apple-challenge");
                    if (challenge != null && !challenge.isEmpty()) {
                        String appleResp = computeAppleResponse(challenge, socket);
                        if (appleResp != null) respSb.append("Apple-Response: ").append(appleResp).append("\r\n");
                    }
                    respSb.append("Content-Length: 0\r\n\r\n");
                    out.write(respSb.toString().getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else if ("SETUP".equals(method)) {
                    isRtspAudioSession = true;
                    mainHandler.post(() -> {
                        if (listener != null) listener.onAirPlayClientConnected(clientIp, clientName);
                    });
                    audioPlayer.initTrack();

                    // Parse client transport ports
                    int clientTimingPort = 0;
                    int clientControlPort = 0;
                    String transportHdr = headers.get("transport");
                    if (transportHdr != null) {
                        for (String param : transportHdr.split(";")) {
                            String p = param.trim();
                            if (p.startsWith("timing_port=")) {
                                try {
                                    clientTimingPort = Integer.parseInt(p.substring(12).trim());
                                } catch (Throwable ignored) {}
                            } else if (p.startsWith("control_port=")) {
                                try {
                                    clientControlPort = Integer.parseInt(p.substring(13).trim());
                                } catch (Throwable ignored) {}
                            }
                        }
                    }

                    // Send initial timing query to client's timing port if specified
                    if (clientTimingPort > 0 && socket.getInetAddress() != null) {
                        sendTimingQueryToClient(socket.getInetAddress(), clientTimingPort);
                    }

                    int audioPort = (rtpAudioSocket != null && !rtpAudioSocket.isClosed()) ? rtpAudioSocket.getLocalPort() : RTP_AUDIO_PORT;
                    int controlPort = (rtpControlSocket != null && !rtpControlSocket.isClosed()) ? rtpControlSocket.getLocalPort() : RTP_CONTROL_PORT;
                    int timingPort = (rtpTimingSocket != null && !rtpTimingSocket.isClosed()) ? rtpTimingSocket.getLocalPort() : RTP_TIMING_PORT;

                    String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
                    StringBuilder respSb = new StringBuilder();
                    respSb.append(proto).append(" 200 OK\r\n");
                    respSb.append("Server: AirTunes/377.17.246\r\n");
                    if (cseq != null) respSb.append("CSeq: ").append(cseq).append("\r\n");
                    respSb.append("Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;server_port=")
                          .append(audioPort)
                          .append(";control_port=")
                          .append(controlPort)
                          .append(";timing_port=")
                          .append(timingPort)
                          .append("\r\n");
                    respSb.append("Session: 1\r\n");
                    respSb.append("Audio-Jack-Status: connected; type=analog\r\n");
                    String challenge = headers.get("apple-challenge");
                    if (challenge != null && !challenge.isEmpty()) {
                        String appleResp = computeAppleResponse(challenge, socket);
                        if (appleResp != null) respSb.append("Apple-Response: ").append(appleResp).append("\r\n");
                    }
                    respSb.append("Content-Length: 0\r\n\r\n");
                    out.write(respSb.toString().getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else if ("RECORD".equals(method)) {
                    isRtspAudioSession = true;
                    audioPlayer.initTrack();
                    mainHandler.post(() -> {
                        if (listener != null) listener.onAirPlayClientConnected(clientIp, clientName + " • Playing Audio");
                    });
                    String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
                    StringBuilder respSb = new StringBuilder();
                    respSb.append(proto).append(" 200 OK\r\n");
                    respSb.append("Server: AirTunes/377.17.246\r\n");
                    if (cseq != null) respSb.append("CSeq: ").append(cseq).append("\r\n");
                    respSb.append("Session: 1\r\n");
                    respSb.append("Audio-Latency: 0\r\n");
                    String challenge = headers.get("apple-challenge");
                    if (challenge != null && !challenge.isEmpty()) {
                        String appleResp = computeAppleResponse(challenge, socket);
                        if (appleResp != null) respSb.append("Apple-Response: ").append(appleResp).append("\r\n");
                    }
                    respSb.append("Content-Length: 0\r\n\r\n");
                    out.write(respSb.toString().getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else if ("GET_PARAMETER".equals(method)) {
                    String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
                    StringBuilder respSb = new StringBuilder();
                    respSb.append(proto).append(" 200 OK\r\n");
                    respSb.append("Server: AirTunes/377.17.246\r\n");
                    if (cseq != null) respSb.append("CSeq: ").append(cseq).append("\r\n");
                    respSb.append("Session: 1\r\n");
                    respSb.append("Content-Type: text/parameters\r\n");
                    String body = "volume: 0.00\r\n";
                    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                    respSb.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");
                    out.write(respSb.toString().getBytes(StandardCharsets.US_ASCII));
                    out.write(bodyBytes);
                    out.flush();
                } else if ("SET_PARAMETER".equals(method)) {
                    if (contentLength > 0 && contentLength < 65536) {
                        byte[] bodyBytes = readExactBytes(in, (int) contentLength);
                        String param = new String(bodyBytes, StandardCharsets.UTF_8);
                        bodyHandled = true;
                        int idx = param.indexOf("volume:");
                        if (idx >= 0) {
                            try {
                                float vol = Float.parseFloat(param.substring(idx + 7).trim());
                                audioPlayer.setVolume(vol);
                                Log.i(TAG, "AirPlay audio volume set: " + vol + " dB");
                            } catch (Throwable ignored) {}
                        }
                    }
                    String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
                    StringBuilder respSb = new StringBuilder();
                    respSb.append(proto).append(" 200 OK\r\n");
                    respSb.append("Server: AirTunes/377.17.246\r\n");
                    if (cseq != null) respSb.append("CSeq: ").append(cseq).append("\r\n");
                    respSb.append("Session: 1\r\n");
                    respSb.append("Content-Length: 0\r\n\r\n");
                    out.write(respSb.toString().getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else if ("FLUSH".equals(method)) {
                    audioPlayer.flush();
                    String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
                    StringBuilder respSb = new StringBuilder();
                    respSb.append(proto).append(" 200 OK\r\n");
                    respSb.append("Server: AirTunes/377.17.246\r\n");
                    if (cseq != null) respSb.append("CSeq: ").append(cseq).append("\r\n");
                    respSb.append("Session: 1\r\n");
                    respSb.append("Content-Length: 0\r\n\r\n");
                    out.write(respSb.toString().getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else if ("TEARDOWN".equals(method)) {
                    audioPlayer.stop();
                    mainHandler.post(() -> {
                        if (listener != null) listener.onAirPlayStopped();
                    });
                    String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
                    StringBuilder respSb = new StringBuilder();
                    respSb.append(proto).append(" 200 OK\r\n");
                    respSb.append("Server: AirTunes/377.17.246\r\n");
                    if (cseq != null) respSb.append("CSeq: ").append(cseq).append("\r\n");
                    respSb.append("Session: 1\r\n");
                    respSb.append("Content-Length: 0\r\n\r\n");
                    out.write(respSb.toString().getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    break;
                } else {
                    sendGenericResponse(out, protocol, 200, "OK", cseq, null, null);
                }

                // Drain any unconsumed request body so subsequent requests on this keep-alive socket stay aligned
                if (!bodyHandled && contentLength > 0 && contentLength < 35 * 1024 * 1024) {
                    drainBytes(in, contentLength);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "AirPlay socket session exception: " + t.getMessage());
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {}
            if (isRtspAudioSession) {
                audioPlayer.stop();
                mainHandler.post(() -> {
                    if (listener != null) listener.onAirPlayStopped();
                });
            }
        }
    }

    private void handleOptions(OutputStream out, String protocol, String cseq, Map<String, String> headers, Socket socket) throws IOException {
        String proto = (protocol != null && protocol.startsWith("RTSP")) ? "RTSP/1.0" : "HTTP/1.1";
        StringBuilder sb = new StringBuilder();
        sb.append(proto).append(" 200 OK\r\n");
        sb.append("Server: AirTunes/377.17.246\r\n");
        if (cseq != null) sb.append("CSeq: ").append(cseq).append("\r\n");
        if (proto.startsWith("RTSP")) {
            sb.append("Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER\r\n");
            sb.append("Audio-Jack-Status: connected; type=analog\r\n");
        } else {
            sb.append("Public: GET, POST, PUT, DELETE, OPTIONS\r\n");
        }
        String challenge = (headers != null) ? headers.get("apple-challenge") : null;
        if (challenge != null && !challenge.isEmpty()) {
            String appleResp = computeAppleResponse(challenge, socket);
            if (appleResp != null) {
                sb.append("Apple-Response: ").append(appleResp).append("\r\n");
                Log.i(TAG, "Apple-Challenge answered with Apple-Response (" + appleResp.length() + " chars)");
            }
        }
        sb.append("Content-Length: 0\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private String computeAppleResponse(String challengeB64, Socket socket) {
        if (RSA_N == null || RSA_D == null) {
            Log.w(TAG, "Apple-Challenge received but RSA key is not available");
            return null;
        }
        try {
            byte[] digest = android.util.Base64.decode(challengeB64.trim(), android.util.Base64.DEFAULT);
            byte[] ip = null;
            if (socket != null && socket.getLocalAddress() != null && socket.getLocalAddress().getAddress().length == 4) {
                ip = socket.getLocalAddress().getAddress();
            }
            if (ip == null || (ip[0] == 0 && ip[1] == 0 && ip[2] == 0 && ip[3] == 0)) {
                ip = getLocalIpAddressBytes();
            }
            if (ip == null) {
                ip = new byte[] {(byte) 192, (byte) 168, 1, 15};
            }

            byte[] mac = getHardwareMacBytes();

            int digestLen = digest.length;
            int ipLen = (ip != null) ? ip.length : 0;
            int macLen = (mac != null) ? mac.length : 0;

            int inputLen = digestLen + ipLen + macLen;
            if (inputLen < 32) {
                inputLen = 32;
            }

            int keyLen = 256;
            int padLen = keyLen - inputLen - 3;
            if (padLen < 8) {
                Log.w(TAG, "Apple-Challenge input too long: " + inputLen);
                return null;
            }

            byte[] buf = new byte[keyLen];
            buf[0] = 0x00;
            buf[1] = 0x01;
            Arrays.fill(buf, 2, 2 + padLen, (byte) 0xFF);
            buf[2 + padLen] = 0x00;

            int pos = 3 + padLen;
            System.arraycopy(digest, 0, buf, pos, digestLen);
            pos += digestLen;
            if (ip != null) {
                System.arraycopy(ip, 0, buf, pos, ipLen);
                pos += ipLen;
            }
            if (mac != null) {
                System.arraycopy(mac, 0, buf, pos, macLen);
                pos += macLen;
            }

            BigInteger m = new BigInteger(1, buf);
            BigInteger s = m.modPow(RSA_D, RSA_N);
            byte[] sBytes = s.toByteArray();
            byte[] sig = new byte[keyLen];
            if (sBytes.length > keyLen) {
                System.arraycopy(sBytes, sBytes.length - keyLen, sig, 0, keyLen);
            } else {
                System.arraycopy(sBytes, 0, sig, keyLen - sBytes.length, sBytes.length);
            }

            return android.util.Base64.encodeToString(sig, android.util.Base64.NO_WRAP).replace("=", "").trim();
        } catch (Throwable t) {
            Log.w(TAG, "Apple-Challenge signing exception", t);
            return null;
        }
    }

    private byte[] getHardwareMacBytes() {
        try {
            String clean = hardwareMac.replaceAll("[^0-9A-Fa-f]", "");
            if (clean.length() >= 12) {
                byte[] bytes = new byte[6];
                for (int i = 0; i < 6; i++) {
                    bytes[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
                }
                return bytes;
            }
        } catch (Throwable ignored) {}
        return new byte[] {(byte) 0xE2, 0x48, (byte) 0xCD, 0x2D, (byte) 0xCF, (byte) 0x9A};
    }

    private void handleServerInfo(OutputStream out, String protocol, String cseq, String uri) throws IOException {
        String txtAirPlayB64 = getTxtAirPlayB64();
        String txtRaopB64 = getTxtRaopB64();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
           .append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
           .append("<plist version=\"1.0\">\n")
           .append("<dict>\n")
           .append("<key>deviceid</key>\n<string>").append(hardwareMac).append("</string>\n")
           .append("<key>features</key>\n<integer>9147</integer>\n")
           .append("<key>model</key>\n<string>AppleTV3,2</string>\n")
           .append("<key>name</key>\n<string>").append(serverName).append("</string>\n")
           .append("<key>protovers</key>\n<string>1.1</string>\n")
           .append("<key>srcvers</key>\n<string>377.17.246</string>\n")
           .append("<key>flags</key>\n<integer>0</integer>\n")
           .append("<key>acl</key>\n<integer>0</integer>\n")
           .append("<key>pi</key>\n<string>").append(pairingId).append("</string>\n")
           .append("<key>pk</key>\n<string>").append(publicKey).append("</string>\n")
           .append("<key>vv</key>\n<integer>2</integer>\n");

        if (txtAirPlayB64 != null && !txtAirPlayB64.isEmpty()) {
            xml.append("<key>txtAirPlay</key>\n<data>").append(txtAirPlayB64).append("</data>\n");
        }
        if (txtRaopB64 != null && !txtRaopB64.isEmpty()) {
            xml.append("<key>txtRAOP</key>\n<data>").append(txtRaopB64).append("</data>\n");
        }

        xml.append("</dict>\n</plist>");
        sendGenericResponse(out, protocol, 200, "OK", cseq, "text/x-apple-plist+xml", xml.toString());
    }

    private String getTxtAirPlayB64() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("model", "AppleTV3,2");
        map.put("features", "0x23BB,0x0");
        map.put("srcvers", "377.17.246");
        map.put("flags", "0x0");
        map.put("protovers", "1.1");
        map.put("acl", "0");
        map.put("vv", "2");
        map.put("deviceid", hardwareMac);
        map.put("pi", pairingId);
        map.put("pk", publicKey);
        return encodeDnsSdTxt(map);
    }

    private String getTxtRaopB64() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("txtvers", "1");
        map.put("ch", "2");
        map.put("cn", "0,1,2,3");
        map.put("da", "true");
        map.put("et", "0,1");
        map.put("ft", "0x23BB,0x0");
        map.put("sf", "0x0");
        map.put("md", "0,1,2");
        map.put("am", "AppleTV3,2");
        map.put("pk", publicKey);
        map.put("tp", "TCP,UDP");
        map.put("vn", "65537");
        map.put("vs", "377.17.246");
        map.put("vv", "2");
        map.put("sr", "44100");
        map.put("ss", "16");
        map.put("pw", "false");
        return encodeDnsSdTxt(map);
    }

    private static String encodeDnsSdTxt(Map<String, String> map) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                String kv = entry.getKey() + "=" + entry.getValue();
                byte[] b = kv.getBytes(StandardCharsets.UTF_8);
                if (b.length > 0 && b.length <= 255) {
                    baos.write(b.length);
                    baos.write(b);
                }
            }
            return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private byte[] decryptRsaKey(String b64Key) {
        if (rsaPrivateKey == null) {
            Log.e(TAG, "rsaPrivateKey is null, cannot decrypt rsaaeskey");
            return null;
        }
        try {
            byte[] enc = android.util.Base64.decode(b64Key.trim(), android.util.Base64.DEFAULT);
            // 1. Try RSA/ECB/OAEPWithSHA-1AndMGF1Padding
            try {
                Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
                cipher.init(Cipher.DECRYPT_MODE, rsaPrivateKey);
                byte[] dec = cipher.doFinal(enc);
                if (dec != null && dec.length >= 16) {
                    byte[] key = (dec.length == 16) ? dec : Arrays.copyOfRange(dec, dec.length - 16, dec.length);
                    Log.i(TAG, "AirPlay AES key decrypted via RSA OAEP (" + key.length + " bytes)");
                    return key;
                }
            } catch (Throwable t1) {
                Log.d(TAG, "OAEP decryption failed, trying PKCS1: " + t1.getMessage());
            }

            // 2. Try RSA/ECB/PKCS1Padding fallback
            try {
                Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                cipher.init(Cipher.DECRYPT_MODE, rsaPrivateKey);
                byte[] dec = cipher.doFinal(enc);
                if (dec != null && dec.length >= 16) {
                    byte[] key = (dec.length == 16) ? dec : Arrays.copyOfRange(dec, dec.length - 16, dec.length);
                    Log.i(TAG, "AirPlay AES key decrypted via RSA PKCS1 (" + key.length + " bytes)");
                    return key;
                }
            } catch (Throwable t2) {
                Log.w(TAG, "PKCS1 decryption failed: " + t2.getMessage());
            }
        } catch (Throwable t) {
            Log.e(TAG, "decryptRsaKey exception", t);
        }
        return null;
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
        Log.i(TAG, "handlePhoto: contentLength=" + contentLength + " from " + clientIp);
        if (contentLength > 0 && contentLength < 35 * 1024 * 1024) { // Up to 35MB photo
            byte[] photoData = readExactBytes(in, (int) contentLength);
            Log.i(TAG, "handlePhoto: read " + photoData.length + " bytes");
            Bitmap bitmap = BitmapFactory.decodeByteArray(photoData, 0, photoData.length);
            Log.i(TAG, "handlePhoto: decoded bitmap=" + (bitmap != null ? bitmap.getWidth() + "x" + bitmap.getHeight() : "null"));
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
            raopInfo.setAttribute("et", "0,1");
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
            if (baos.size() > 8192) {
                throw new IOException("HTTP request line too long");
            }
        }
        if (baos.size() == 0 && b == -1) return null;
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    private static void drainBytes(InputStream in, long count) throws IOException {
        byte[] drainBuf = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int toRead = (int) Math.min(drainBuf.length, remaining);
            int r = in.read(drainBuf, 0, toRead);
            if (r == -1) break;
            remaining -= r;
        }
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

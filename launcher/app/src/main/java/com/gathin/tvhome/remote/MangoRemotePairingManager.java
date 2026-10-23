package com.gathin.tvhome.remote;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages pairing state, ephemeral PIN generation, and authenticated client session tokens.
 */
public class MangoRemotePairingManager {
    private static final String PREFS_NAME = "mango_remote_paired_devices";
    private static final String KEY_PAIRED_TOKENS = "paired_auth_tokens";
    private static final long PIN_VALIDITY_DURATION_MS = 5 * 60 * 1000L; // 5 minutes
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long RATE_LIMIT_LOCKOUT_MS = 60 * 1000L; // 1 minute lockout

    public interface PairingListener {
        void onPairingSessionStarted(String pin, long expiresAtMs);
        void onClientPairedSuccessfully(String clientName, String clientId);
        void onPairingSessionExpired();
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Set<String> trustedTokens = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final SecureRandom secureRandom = new SecureRandom();

    private String activePin = null;
    private long pinExpiresAtMs = 0L;
    private int failedAttempts = 0;
    private long lockoutUntilMs = 0L;
    private PairingListener listener;

    public MangoRemotePairingManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadTrustedTokens();
    }

    public void setPairingListener(PairingListener listener) {
        this.listener = listener;
    }

    private void loadTrustedTokens() {
        Set<String> saved = prefs.getStringSet(KEY_PAIRED_TOKENS, null);
        if (saved != null) {
            trustedTokens.addAll(saved);
        }
    }

    private synchronized void persistTrustedTokens() {
        prefs.edit().putStringSet(KEY_PAIRED_TOKENS, new HashSet<>(trustedTokens)).apply();
    }

    public synchronized String startPairingSession() {
        int randomCode = secureRandom.nextInt(10000);
        activePin = String.format(Locale.US, "%04d", randomCode);
        android.util.Log.i("MangoRemotePairing", "New active pairing session started with PIN: " + activePin);
        pinExpiresAtMs = System.currentTimeMillis() + PIN_VALIDITY_DURATION_MS;
        failedAttempts = 0;

        if (listener != null) {
            listener.onPairingSessionStarted(activePin, pinExpiresAtMs);
        }
        return activePin;
    }

    public synchronized String getActivePin() {
        if (activePin != null && System.currentTimeMillis() > pinExpiresAtMs) {
            activePin = null;
            pinExpiresAtMs = 0L;
            if (listener != null) listener.onPairingSessionExpired();
        }
        return activePin;
    }

    public synchronized boolean isPairingActive() {
        return getActivePin() != null;
    }

    public synchronized String attemptPair(String candidatePin, String clientName, String clientId) {
        long now = System.currentTimeMillis();
        if (now < lockoutUntilMs) {
            return null; // Rate limited
        }

        String currentPin = getActivePin();
        android.util.Log.i("MangoRemotePairing", "attemptPair candidate='" + candidatePin + "' activePin='" + currentPin + "'");
        if (currentPin == null || candidatePin == null) {
            return null;
        }

        if (!currentPin.trim().equals(candidatePin.trim())) {
            failedAttempts++;
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                lockoutUntilMs = now + RATE_LIMIT_LOCKOUT_MS;
                failedAttempts = 0;
            }
            return null;
        }

        // Successfully matched PIN! Issue a secure token
        String token = "mng_" + UUID.randomUUID().toString().replace("-", "") + "_" + System.currentTimeMillis();
        trustedTokens.add(token);
        persistTrustedTokens();

        // Expire active PIN after successful pairing
        activePin = null;
        pinExpiresAtMs = 0L;
        failedAttempts = 0;

        if (listener != null) {
            listener.onClientPairedSuccessfully(clientName, clientId);
        }

        return token;
    }

    public boolean isTokenValid(String token) {
        if (token == null || token.isEmpty()) return false;
        return trustedTokens.contains(token);
    }

    public synchronized void revokeToken(String token) {
        if (token != null && trustedTokens.remove(token)) {
            persistTrustedTokens();
        }
    }

    public synchronized void clearAllPairedDevices() {
        trustedTokens.clear();
        prefs.edit().remove(KEY_PAIRED_TOKENS).apply();
    }

    public int getPairedDeviceCount() {
        return trustedTokens.size();
    }
}

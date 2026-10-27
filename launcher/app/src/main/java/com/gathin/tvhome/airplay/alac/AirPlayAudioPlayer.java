package com.gathin.tvhome.airplay.alac;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * High-performance real-time AudioTrack player for AirPlay audio streaming.
 * Handles:
 * 1. AES-128-CBC audio payload decryption
 * 2. Pure Java ALAC (Apple Lossless Audio Codec) decoding to 16-bit stereo PCM
 * 3. Fallback uncompressed L16/44100/2 PCM rendering
 * 4. Hardware AudioTrack streaming to TV speakers
 */
public class AirPlayAudioPlayer {
    private static final String TAG = "AirPlayAudioPlayer";
    private static final int SAMPLE_RATE = 44100;

    private AudioTrack audioTrack;
    private boolean isPlaying = false;
    private boolean isAlac = true;

    private byte[] aesKey;
    private byte[] aesIv;
    private SecretKeySpec secretKeySpec;
    private IvParameterSpec ivParameterSpec;
    private Cipher aesCipher;

    private AlacFile alacFile;
    private final byte[] decryptedBuf = new byte[8192];
    private final int[] pcmInts = new int[8192];
    private final byte[] pcmBytes = new byte[16384];

    public synchronized void initTrack() {
        if (audioTrack != null && isPlaying) {
            return; // AudioTrack is already active and playing
        }
        if (audioTrack != null) {
            stop();
        }
        try {
            int minBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int bufferSize = Math.max(minBufferSize * 4, 32 * 1024);

            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();

            audioTrack = new AudioTrack(
                    attributes,
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            );

            audioTrack.play();
            isPlaying = true;
            Log.i(TAG, "AudioTrack initialized and playing (buffer=" + bufferSize + " bytes)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize AudioTrack", t);
        }
    }

    public synchronized void setSession(byte[] key, byte[] iv, String fmtp, boolean isAlacStream) {
        this.isAlac = isAlacStream;
        if (key != null && key.length == 16) {
            this.aesKey = Arrays.copyOf(key, 16);
            this.secretKeySpec = new SecretKeySpec(this.aesKey, "AES");
        }
        if (iv != null && iv.length == 16) {
            this.aesIv = Arrays.copyOf(iv, 16);
            this.ivParameterSpec = new IvParameterSpec(this.aesIv);
        }

        try {
            this.aesCipher = Cipher.getInstance("AES/CBC/NoPadding");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to create AES cipher", t);
        }

        this.alacFile = AlacDecodeUtils.create_alac(16, 2);
        if (fmtp != null && !fmtp.isEmpty()) {
            parseFmtp(fmtp, this.alacFile);
        }

        initTrack();
    }

    private void parseFmtp(String fmtp, AlacFile alac) {
        try {
            String[] tokens = fmtp.trim().split("\\s+");
            if (tokens.length >= 11) {
                // fmtp: 96 <frame_length> <compatible_version> <bit_depth> <rice_historymult> <rice_initialhistory> <rice_kmodifier> <channels> <max_run> <max_frame_bytes> <avg_bit_rate> <sample_rate>
                int offset = tokens[0].contains(":") ? 1 : (tokens[0].equals("96") ? 1 : 0);
                if (tokens.length > offset) alac.setinfo_max_samples_per_frame = Integer.parseInt(tokens[offset]);
                if (tokens.length > offset + 1) alac.setinfo_7a = Integer.parseInt(tokens[offset + 1]);
                if (tokens.length > offset + 2) alac.setinfo_sample_size = Integer.parseInt(tokens[offset + 2]);
                if (tokens.length > offset + 3) alac.setinfo_rice_historymult = Integer.parseInt(tokens[offset + 3]);
                if (tokens.length > offset + 4) alac.setinfo_rice_initialhistory = Integer.parseInt(tokens[offset + 4]);
                if (tokens.length > offset + 5) alac.setinfo_rice_kmodifier = Integer.parseInt(tokens[offset + 5]);
                if (tokens.length > offset + 6) alac.setinfo_7f = Integer.parseInt(tokens[offset + 6]);
                if (tokens.length > offset + 7) alac.setinfo_80 = Integer.parseInt(tokens[offset + 7]);
                if (tokens.length > offset + 8) alac.setinfo_82 = Integer.parseInt(tokens[offset + 8]);
                if (tokens.length > offset + 9) alac.setinfo_86 = Integer.parseInt(tokens[offset + 9]);
                if (tokens.length > offset + 10) alac.setinfo_8a_rate = Integer.parseInt(tokens[offset + 10]);
                Log.i(TAG, "Parsed ALAC fmtp: samplesPerFrame=" + alac.setinfo_max_samples_per_frame + ", rate=" + alac.setinfo_8a_rate);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to parse fmtp: " + fmtp, t);
        }
    }

    public void processRtpAudioPacket(byte[] data, int offset, int length) {
        if (length <= 12 || data == null) {
            return;
        }

        // Verify RTP Version 2 (0x80) and Payload Type 96 (AppleLossless / RAOP)
        if ((data[offset] & 0xC0) != 0x80 || (data[offset + 1] & 0x7F) != 96) {
            return;
        }

        int payloadOffset = offset + 12;
        int payloadLen = length - 12;
        if (payloadLen <= 0 || payloadLen > decryptedBuf.length) {
            return;
        }

        int bytesToWrite = 0;
        AudioTrack currentTrack;

        synchronized (this) {
            if (audioTrack == null || !isPlaying) {
                return;
            }

            // 1. Decrypt audio payload using AES-128-CBC
            if (secretKeySpec != null && ivParameterSpec != null && aesCipher != null) {
                try {
                    int encLen = (payloadLen / 16) * 16;
                    aesCipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);
                    if (encLen > 0) {
                        aesCipher.doFinal(data, payloadOffset, encLen, decryptedBuf, 0);
                    }
                    int rem = payloadLen - encLen;
                    if (rem > 0) {
                        System.arraycopy(data, payloadOffset + encLen, decryptedBuf, encLen, rem);
                    }
                } catch (Throwable t) {
                    // Abort immediately on decryption failure to prevent acoustic damage to TV speakers
                    Log.w(TAG, "AES decryption failure, dropping packet: " + t.getMessage());
                    return;
                }
            } else {
                System.arraycopy(data, payloadOffset, decryptedBuf, 0, payloadLen);
            }

            // 2. Decode ALAC audio frame to 16-bit PCM samples
            if (isAlac && alacFile != null) {
                try {
                    int outputBytes = AlacDecodeUtils.decode_frame(alacFile, decryptedBuf, pcmInts, alacFile.setinfo_max_samples_per_frame);
                    if (outputBytes > 0 && outputBytes <= pcmBytes.length) {
                        int totalSamples = outputBytes / 2; // Stereo samples (L + R)
                        for (int i = 0; i < totalSamples; i++) {
                            int s = pcmInts[i];
                            pcmBytes[i * 2] = (byte) (s & 0xFF);
                            pcmBytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                        }
                        bytesToWrite = outputBytes;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "ALAC decode exception: " + t.getMessage());
                }
            } else {
                // Uncompressed L16 PCM: big-endian to little-endian conversion
                try {
                    int alignedLen = (payloadLen / 4) * 4;
                    int numSamples = alignedLen / 2;
                    for (int i = 0; i < numSamples; i++) {
                        pcmBytes[i * 2] = decryptedBuf[i * 2 + 1];
                        pcmBytes[i * 2 + 1] = decryptedBuf[i * 2];
                    }
                    bytesToWrite = alignedLen;
                } catch (Throwable t) {
                    Log.w(TAG, "L16 decode exception: " + t.getMessage());
                }
            }

            currentTrack = this.audioTrack;
        }

        // 3. Write PCM bytes to hardware AudioTrack outside monitor lock to avoid blocking TCP control commands
        if (bytesToWrite > 0 && currentTrack != null && isPlaying) {
            try {
                currentTrack.write(pcmBytes, 0, bytesToWrite);
            } catch (Throwable t) {
                Log.w(TAG, "AudioTrack write exception: " + t.getMessage());
            }
        }
    }

    public synchronized void setVolume(float volumeDb) {
        if (audioTrack == null) return;
        try {
            float gain;
            if (volumeDb <= -140f) {
                gain = 0.0f;
            } else if (volumeDb >= 0f) {
                gain = 1.0f;
            } else {
                gain = (float) Math.pow(10.0, volumeDb / 20.0);
            }
            audioTrack.setVolume(gain);
        } catch (Throwable ignored) {}
    }

    public synchronized void flush() {
        if (audioTrack != null) {
            try {
                audioTrack.flush();
            } catch (Throwable ignored) {}
        }
    }

    public synchronized boolean isPlaying() {
        return isPlaying;
    }

    public synchronized void pause() {
        if (audioTrack != null && isPlaying) {
            try {
                audioTrack.pause();
                isPlaying = false;
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void resume() {
        if (audioTrack != null && !isPlaying) {
            try {
                audioTrack.play();
                isPlaying = true;
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void stop() {
        isPlaying = false;
        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.stop();
                audioTrack.release();
            } catch (Throwable ignored) {}
            audioTrack = null;
        }
    }
}

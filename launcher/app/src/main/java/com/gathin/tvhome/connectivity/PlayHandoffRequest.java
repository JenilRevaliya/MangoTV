package com.gathin.tvhome.connectivity;

import android.graphics.Bitmap;

/**
 * Immutable request model representing a media playback handoff / casting session in Mango Play.
 */
public class PlayHandoffRequest {
    public final String id;
    public final String sourceDevice;
    public final String title;
    public final String metadata;
    public final String streamUrl;
    public final long positionMs;
    public final long durationMs;
    public final Bitmap thumbnail;

    public PlayHandoffRequest(String id, String sourceDevice, String title, String metadata,
                              String streamUrl, long positionMs, long durationMs, Bitmap thumbnail) {
        this.id = id != null ? id : "play-" + System.currentTimeMillis();
        this.sourceDevice = sourceDevice != null ? sourceDevice : "Nearby Device";
        this.title = title != null ? title : "Media";
        this.metadata = metadata != null ? metadata : "Streaming Video";
        this.streamUrl = streamUrl;
        this.positionMs = Math.max(0, positionMs);
        this.durationMs = Math.max(0, durationMs);
        this.thumbnail = thumbnail;
    }

    public static String formatDuration(long ms) {
        if (ms <= 0) return "0:00";
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%d:%02d", minutes, seconds);
        }
    }
}

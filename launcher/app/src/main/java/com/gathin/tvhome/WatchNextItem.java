package com.gathin.tvhome;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;

/** Model representing a recently watched episode or movie in Continue Watching. */
public final class WatchNextItem {
    final long id;
    final String title;
    final String subtitle;
    final String duration;
    final float progress;
    final int posterRes;
    final Uri posterUri;
    final int badgeRes;
    final Drawable appIcon;
    final String packageName;
    final String appName;
    final Intent launchIntent;

    public WatchNextItem(long id, String title, String subtitle, String duration, float progress,
                          int posterRes, Uri posterUri, int badgeRes, Drawable appIcon,
                          String packageName, String appName, Intent launchIntent) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.duration = duration;
        this.progress = Math.max(0f, Math.min(1f, progress));
        this.posterRes = posterRes;
        this.posterUri = posterUri;
        this.badgeRes = badgeRes;
        this.appIcon = appIcon;
        this.packageName = packageName;
        this.appName = appName != null ? appName : "";
        this.launchIntent = launchIntent;
    }
}

package com.gathin.tvhome;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WatchNextManager {
    private static final String TAG = "TvHome.WatchNext";
    private static final String FILE_NAME = "watch_next.json";
    public static final Uri WATCH_NEXT_URI = Uri.parse("content://android.media.tv/watch_next_program");

    private WatchNextManager() {}

    /**
     * Loads the real Continue Watching list from the Android TV system provider
     * (content://android.media.tv/watch_next_program) or custom config JSON.
     * Returns an empty list if no items are found. NO MOCK DATA.
     */
    public static List<WatchNextItem> load(Context context) {
        List<WatchNextItem> items = new ArrayList<>();

        // 1. Check custom user configuration in watch_next.json if provided
        File dir = Config.dir(context);
        File customFile = dir != null ? new File(dir, FILE_NAME) : null;
        if (customFile != null && customFile.exists()) {
            List<WatchNextItem> customItems = parseJson(context, customFile);
            if (!customItems.isEmpty()) return customItems;
        }

        // 2. Query system Android TV ContentProvider (used by Projectivy, FLauncher, etc.)
        PackageManager pm = context.getPackageManager();
        try {
            String[] projection = new String[] {
                    "_id",
                    "package_name",
                    "title",
                    "episode_title",
                    "season_display_number",
                    "episode_display_number",
                    "season_number",
                    "episode_number",
                    "short_description",
                    "poster_art_uri",
                    "thumbnail_uri",
                    "intent_uri",
                    "last_playback_position_millis",
                    "duration_millis",
                    "watch_next_type",
                    "last_engagement_time_utc_millis"
            };

            Cursor cursor = context.getContentResolver().query(
                    WATCH_NEXT_URI,
                    projection,
                    null,
                    null,
                    "last_engagement_time_utc_millis DESC"
            );

            if (cursor != null) {
                try {
                    int idCol = cursor.getColumnIndex("_id");
                    int pkgCol = cursor.getColumnIndex("package_name");
                    int titleCol = cursor.getColumnIndex("title");
                    int epCol = cursor.getColumnIndex("episode_title");
                    int seasonDispCol = cursor.getColumnIndex("season_display_number");
                    int epDispCol = cursor.getColumnIndex("episode_display_number");
                    int seasonCol = cursor.getColumnIndex("season_number");
                    int epNumCol = cursor.getColumnIndex("episode_number");
                    int descCol = cursor.getColumnIndex("short_description");
                    int posterCol = cursor.getColumnIndex("poster_art_uri");
                    int thumbCol = cursor.getColumnIndex("thumbnail_uri");
                    int intentCol = cursor.getColumnIndex("intent_uri");
                    int posCol = cursor.getColumnIndex("last_playback_position_millis");
                    int durCol = cursor.getColumnIndex("duration_millis");

                    while (cursor.moveToNext() && items.size() < 25) {
                        long id = idCol != -1 ? cursor.getLong(idCol) : 0;
                        String pkg = pkgCol != -1 ? cursor.getString(pkgCol) : "";
                        String title = titleCol != -1 ? cursor.getString(titleCol) : "";
                        String epTitle = epCol != -1 ? cursor.getString(epCol) : "";
                        String season = seasonDispCol != -1 ? cursor.getString(seasonDispCol) : "";
                        if (season == null || season.isEmpty()) {
                            season = seasonCol != -1 ? cursor.getString(seasonCol) : "";
                        }
                        String epNum = epDispCol != -1 ? cursor.getString(epDispCol) : "";
                        if (epNum == null || epNum.isEmpty()) {
                            epNum = epNumCol != -1 ? cursor.getString(epNumCol) : "";
                        }
                        String desc = descCol != -1 ? cursor.getString(descCol) : "";
                        String posterUriStr = posterCol != -1 ? cursor.getString(posterCol) : "";
                        String thumbUriStr = thumbCol != -1 ? cursor.getString(thumbCol) : "";
                        String intentUri = intentCol != -1 ? cursor.getString(intentCol) : "";
                        long pos = posCol != -1 ? cursor.getLong(posCol) : 0;
                        long dur = durCol != -1 ? cursor.getLong(durCol) : 0;

                        if ((title == null || title.trim().isEmpty()) && (pkg == null || pkg.trim().isEmpty())) {
                            continue;
                        }

                        float progress = dur > 0 ? (float) pos / dur : 0f;
                        String subtitle = "";
                        boolean hasSeason = season != null && !season.trim().isEmpty();
                        boolean hasEp = epNum != null && !epNum.trim().isEmpty();
                        boolean hasEpTitle = epTitle != null && !epTitle.trim().isEmpty();

                        if (hasSeason || hasEp) {
                            StringBuilder sb = new StringBuilder();
                            if (hasSeason) sb.append("S").append(season);
                            if (hasEp) {
                                if (hasSeason) sb.append(" · ");
                                sb.append("E").append(epNum);
                            }
                            if (hasEpTitle) sb.append(": ").append(epTitle);
                            subtitle = sb.toString();
                        } else if (hasEpTitle) {
                            subtitle = epTitle;
                        } else if (desc != null && !desc.trim().isEmpty()) {
                            subtitle = desc;
                        }

                        long remainingMin = dur > pos ? (dur - pos) / 60_000 : 0;
                        String durationStr = remainingMin > 0 ? remainingMin + " min left" : "";

                        Intent launchIntent = null;
                        if (intentUri != null && !intentUri.trim().isEmpty()) {
                            try {
                                launchIntent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
                            } catch (Exception ignored) {}
                        }
                        if (launchIntent == null && pkg != null && !pkg.trim().isEmpty()) {
                            launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg);
                            if (launchIntent == null) {
                                launchIntent = pm.getLaunchIntentForPackage(pkg);
                            }
                        }

                        Uri posterUri = null;
                        if (posterUriStr != null && !posterUriStr.trim().isEmpty()) {
                            try {
                                posterUri = Uri.parse(posterUriStr);
                            } catch (Exception ignored) {}
                        }
                        if (posterUri == null && thumbUriStr != null && !thumbUriStr.trim().isEmpty()) {
                            try {
                                posterUri = Uri.parse(thumbUriStr);
                            } catch (Exception ignored) {}
                        }

                        int badge = getBadgeForPackage(pkg);
                        Drawable appIcon = null;
                        if (badge == 0 && pkg != null && !pkg.isEmpty()) {
                            try {
                                appIcon = pm.getApplicationIcon(pkg);
                            } catch (Exception ignored) {}
                        }

                        String appName = getAppName(context, pkg);

                        items.add(new WatchNextItem(
                                id,
                                title != null ? title : "",
                                subtitle,
                                durationStr,
                                progress,
                                0,
                                posterUri,
                                badge,
                                appIcon,
                                pkg,
                                appName,
                                launchIntent
                        ));
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "System watch next unavailable or empty: " + e.getMessage());
        }

        // Return real items only! NO MOCK FALLBACK ITEMS!
        return items;
    }

    public static int getBadgeForPackage(String pkg) {
        if (pkg == null) return 0;
        String p = pkg.toLowerCase();
        if (p.contains("netflix")) return R.drawable.ic_badge_netflix;
        if (p.contains("amazon") || p.contains("prime")) return R.drawable.ic_badge_prime;
        if (p.contains("hotstar") || p.contains("disney")) return R.drawable.ic_badge_hotstar;
        if (p.contains("apple")) return R.drawable.ic_badge_apple;
        if (p.contains("youtube") || p.contains("smarttube")) return R.drawable.ic_badge_youtube;
        if (p.contains("vlc")) return R.drawable.ic_badge_vlc;
        if (p.contains("zee5") || p.contains("graymatrix")) return R.drawable.ic_badge_zee5;
        if (p.contains("shemaroo")) return R.drawable.ic_badge_shemaroo;
        return 0;
    }

    private static List<WatchNextItem> parseJson(Context context, File file) {
        List<WatchNextItem> list = new ArrayList<>();
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            in.read(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            JSONArray array = new JSONArray(text);
            PackageManager pm = context.getPackageManager();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String title = obj.optString("title", "");
                String subtitle = obj.optString("subtitle", "");
                String duration = obj.optString("duration", "");
                float progress = (float) obj.optDouble("progress", 0.5);
                String pkg = obj.optString("package", "");
                String posterUriStr = obj.optString("poster", "");
                String intentUri = obj.optString("intent", "");

                Uri posterUri = null;
                if (!posterUriStr.isEmpty()) {
                    try {
                        posterUri = Uri.parse(posterUriStr);
                    } catch (Exception ignored) {}
                }

                Intent launchIntent = null;
                if (!intentUri.isEmpty()) {
                    try {
                        launchIntent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
                    } catch (Exception ignored) {}
                }
                if (launchIntent == null && !pkg.isEmpty()) {
                    launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg);
                    if (launchIntent == null) launchIntent = pm.getLaunchIntentForPackage(pkg);
                }

                int badge = getBadgeForPackage(pkg);
                Drawable appIcon = null;
                if (badge == 0 && !pkg.isEmpty()) {
                    try {
                        appIcon = pm.getApplicationIcon(pkg);
                    } catch (Exception ignored) {}
                }

                String appName = obj.optString("app_name", "");
                if (appName.isEmpty()) {
                    appName = getAppName(context, pkg);
                }

                list.add(new WatchNextItem(
                        i,
                        title,
                        subtitle,
                        duration,
                        progress,
                        0,
                        posterUri,
                        badge,
                        appIcon,
                        pkg,
                        appName,
                        launchIntent
                ));
            }
        } catch (Exception e) {
            Log.w(TAG, "parseJson error: " + e.getMessage());
        }
        return list;
    }

    public static String getAppName(Context context, String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return "";
        try {
            PackageManager pm = context.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Exception ignored) {}

        String p = pkg.toLowerCase();
        if (p.contains("netflix")) return "Netflix";
        if (p.contains("amazon") || p.contains("prime")) return "Prime Video";
        if (p.contains("hotstar") || p.contains("disney")) return "JioHotstar";
        if (p.contains("apple")) return "Apple TV";
        if (p.contains("youtube") || p.contains("smarttube")) return "YouTube";
        if (p.contains("vlc")) return "VLC";
        if (p.contains("zee5") || p.contains("graymatrix")) return "ZEE5";
        if (p.contains("shemaroo")) return "Shemaroo";
        if (p.contains("jio")) return "JioCinema";
        if (p.contains("sony")) return "SonyLIV";
        if (p.contains("mxplayer")) return "MX Player";
        return "";
    }

    public static Uri insertProgram(Context context, ContentValues values) {
        try {
            Uri uri = context.getContentResolver().insert(WATCH_NEXT_URI, values);
            Log.i(TAG, "insertProgram returned: " + uri);
            return uri;
        } catch (Exception e) {
            Log.e(TAG, "Failed to insert into watch_next_program", e);
            return null;
        }
    }

    public static int deleteProgram(Context context, long id) {
        try {
            Uri uri = Uri.parse(WATCH_NEXT_URI + "/" + id);
            return context.getContentResolver().delete(uri, null, null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete from watch_next_program", e);
            return 0;
        }
    }
}

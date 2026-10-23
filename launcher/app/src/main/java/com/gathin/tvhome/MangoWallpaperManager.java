package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-performance wallpaper management engine for MangoTV.
 * Supports online curated feeds (ultra high-res abstract graffiti and landscapes),
 * automatic offline fallback (5 bundled WebP drawables), disk caching,
 * and pre-computed Apple-style frosted blur layers.
 */
public final class MangoWallpaperManager {
    private static final String TAG = "TvHome.WallpaperManager";
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // Curated high-resolution open-source / CDN wallpapers (Abstract & Epic Aerial Landscapes)
    public static final String[] ONLINE_WALLPAPERS = {
            // Fluid Abstract & Still Life Art
            "https://images.unsplash.com/photo-1541701494587-cb58502866ab?auto=format&fit=crop&w=1920&q=80",
            "https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?auto=format&fit=crop&w=1920&q=80",
            "https://images.unsplash.com/photo-1550684848-fac1c5b4e853?auto=format&fit=crop&w=1920&q=80",
            // Epic High-Definition Apple TV-style Landscapes & Aerials
            "https://images.unsplash.com/photo-1506744038136-46273834b3fb?auto=format&fit=crop&w=1920&q=80",
            "https://images.unsplash.com/photo-1470071459604-3b5ec3a7fe05?auto=format&fit=crop&w=1920&q=80",
            "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?auto=format&fit=crop&w=1920&q=80",
            "https://images.unsplash.com/photo-1493246507139-91e8fad9978e?auto=format&fit=crop&w=1920&q=80",
            "https://images.unsplash.com/photo-1502784444187-359ac186c5bb?auto=format&fit=crop&w=1920&q=80",
            "https://images.unsplash.com/photo-1531366936337-7c912a4589a7?auto=format&fit=crop&w=1920&q=80",
            "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?auto=format&fit=crop&w=1920&q=80"
    };

    // 5 bundled offline wallpapers (stored in res/drawable-nodpi/)
    public static final int[] OFFLINE_WALLPAPERS = {
            R.drawable.wallpaper_offline_1,
            R.drawable.wallpaper_offline_2,
            R.drawable.wallpaper_offline_3,
            R.drawable.wallpaper_offline_4,
            R.drawable.wallpaper_offline_5
    };

    public static final class WallpaperBundle {
        public final Bitmap sharp;
        public final Bitmap blurred;
        public final String identifier;

        public WallpaperBundle(Bitmap sharp, Bitmap blurred, String identifier) {
            this.sharp = sharp;
            this.blurred = blurred;
            this.identifier = identifier;
        }
    }

    public interface WallpaperCallback {
        void onWallpaperReady(WallpaperBundle bundle);
    }

    private MangoWallpaperManager() {}

    public static boolean isNetworkAvailable(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network net = cm.getActiveNetwork();
                if (net == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            } else {
                NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Loads the initial wallpaper synchronously for zero-flicker startup.
     */
    public static WallpaperBundle getInitialWallpaper(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int width = dm.widthPixels;
        int height = dm.heightPixels;

        // Try offline resources in order until one decodes
        for (int i = 0; i < OFFLINE_WALLPAPERS.length; i++) {
            Bitmap sharp = decodeResource(context, OFFLINE_WALLPAPERS[i], width, height);
            if (sharp != null) {
                Bitmap blurred = createFrostedBlur(sharp);
                return new WallpaperBundle(sharp, blurred, "offline_" + (i + 1));
            }
        }
        return null;
    }

    /**
     * Loads the next wallpaper in the background. Uses online URL if connected,
     * otherwise falls back to offline bundled drawables.
     */
    public static void getNextWallpaper(Context context, int index, WallpaperCallback callback) {
        if (callback == null) return;
        Context appCtx = context.getApplicationContext();
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        final int targetW = dm.widthPixels;
        final int targetH = dm.heightPixels;

        EXECUTOR.execute(() -> {
            boolean online = isNetworkAvailable(appCtx);
            Bitmap sharp = null;
            String identifier = "offline_" + (index % OFFLINE_WALLPAPERS.length);

            if (online) {
                String urlStr = ONLINE_WALLPAPERS[Math.abs(index) % ONLINE_WALLPAPERS.length];
                File cached = getCachedFile(appCtx, urlStr);
                if (cached.exists() && cached.length() > 0) {
                    sharp = decodeFile(cached, targetW, targetH);
                    identifier = "cache_" + cached.getName();
                } else {
                    if (downloadToFile(urlStr, cached)) {
                        sharp = decodeFile(cached, targetW, targetH);
                        identifier = "online_" + cached.getName();
                    }
                }
            }

            // Fallback to offline WebP if offline or download/decoding failed
            if (sharp == null) {
                for (int i = 0; i < OFFLINE_WALLPAPERS.length; i++) {
                    int resId = OFFLINE_WALLPAPERS[(Math.abs(index) + i) % OFFLINE_WALLPAPERS.length];
                    sharp = decodeResource(appCtx, resId, targetW, targetH);
                    if (sharp != null) {
                        identifier = "offline_" + ((Math.abs(index) + i) % OFFLINE_WALLPAPERS.length);
                        break;
                    }
                }
            }

            if (sharp == null) {
                MAIN_HANDLER.post(() -> callback.onWallpaperReady(null));
                return;
            }

            Bitmap blurred = createFrostedBlur(sharp);
            final WallpaperBundle bundle = new WallpaperBundle(sharp, blurred, identifier);

            MAIN_HANDLER.post(() -> callback.onWallpaperReady(bundle));
        });
    }

    private static File getCachedFile(Context context, String url) {
        File dir = new File(context.getCacheDir(), "wallpapers");
        if (!dir.exists()) dir.mkdirs();
        String hash = md5(url);
        return new File(dir, hash + ".webp");
    }

    private static boolean downloadToFile(String urlStr, File dest) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV; Build/TV)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setDoInput(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code != 200 && code != 301 && code != 302) {
                return false;
            }

            File temp = new File(dest.getParentFile(), dest.getName() + ".tmp");
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }
            if (temp.exists() && temp.length() > 1024) {
                return temp.renameTo(dest);
            } else {
                temp.delete();
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Download failed for " + urlStr + ": " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap decodeFile(File file, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;

            opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight);
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bm = BitmapFactory.decodeFile(file.getPath(), opts);
            if (bm == null) {
                bm = BitmapFactory.decodeFile(file.getPath());
            }
            return bm;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "decodeFile failed: " + e.getMessage());
            try {
                return BitmapFactory.decodeFile(file.getPath());
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static Bitmap decodeResource(Context context, int resId, int reqWidth, int reqHeight) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(context.getResources(), resId, opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                return BitmapFactory.decodeResource(context.getResources(), resId);
            }

            opts.inSampleSize = calculateInSampleSize(opts, reqWidth, reqHeight);
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bm = BitmapFactory.decodeResource(context.getResources(), resId, opts);
            if (bm == null) {
                bm = BitmapFactory.decodeResource(context.getResources(), resId);
            }
            return bm;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "decodeResource failed: " + e.getMessage());
            try {
                return BitmapFactory.decodeResource(context.getResources(), resId);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    /**
     * Creates an authentic Apple frosted blur representation by downsampling to 240x135
     * and running an accelerated 2-pass box blur. Scaled up with bilinear filtering on GPU,
     * this renders as buttery-smooth frosted glass at 60 FPS with zero lag.
     */
    public static Bitmap createFrostedBlur(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        try {
            int blurW = Math.max(120, src.getWidth() / 8);
            int blurH = Math.max(68, src.getHeight() / 8);
            Bitmap small = Bitmap.createScaledBitmap(src, blurW, blurH, true);
            fastBoxBlur(small, 6);
            return small;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static void fastBoxBlur(Bitmap bitmap, int radius) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pix = new int[w * h];
        bitmap.getPixels(pix, 0, w, 0, 0, w, h);

        int wm = w - 1;
        int hm = h - 1;
        int wh = w * h;
        int div = radius + radius + 1;

        int[] r = new int[wh];
        int[] g = new int[wh];
        int[] b = new int[wh];
        int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
        int[] vmin = new int[Math.max(w, h)];

        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (i = 0; i < 256 * divsum; i++) {
            dv[i] = (i / divsum);
        }

        yw = yi = 0;
        int[][] stack = new int[div][3];
        int stackpointer;
        int stackstart;
        int[] sir;
        int rbs;
        int r1 = radius + 1;
        int routsum, goutsum, boutsum;
        int rinsum, ginsum, binsum;

        for (y = 0; y < h; y++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                rbs = r1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
            }
            stackpointer = radius;

            for (x = 0; x < w; x++) {
                r[yi] = dv[rsum];
                g[yi] = dv[gsum];
                b[yi] = dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (y == 0) {
                    vmin[x] = Math.min(x + radius + 1, wm);
                }
                p = pix[yw + vmin[x]];

                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[(stackpointer) % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi++;
            }
            yw += w;
        }

        for (x = 0; x < w; x++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                sir = stack[i + radius];
                sir[0] = r[yi];
                sir[1] = g[yi];
                sir[2] = b[yi];
                rbs = r1 - Math.abs(i);
                rsum += r[yi] * rbs;
                gsum += g[yi] * rbs;
                bsum += b[yi] * rbs;

                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }

                if (i < hm) {
                    yp += w;
                }
            }
            yi = x;
            stackpointer = radius;
            for (y = 0; y < h; y++) {
                pix[yi] = (0xff000000 & pix[yi]) | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (x == 0) {
                    vmin[y] = Math.min(y + r1, hm) * w;
                }
                p = x + vmin[y];

                sir[0] = r[p];
                sir[1] = g[p];
                sir[2] = b[p];

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi += w;
            }
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h);
    }

    private static String md5(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(s.getBytes());
            byte[] messageDigest = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte aMessageDigest : messageDigest) {
                String h = Integer.toHexString(0xFF & aMessageDigest);
                while (h.length() < 2) h = "0" + h;
                hexString.append(h);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}

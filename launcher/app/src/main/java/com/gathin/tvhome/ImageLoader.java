package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ultra-lean asynchronous image loader with memory LRU caching and downsampling
 * tailored for TV memory constraints.
 */
public final class ImageLoader {
    private static final String TAG = "TvHome.ImageLoader";
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // 12 MB max memory cache for decoded TV cards
    private static final int CACHE_SIZE = 12 * 1024 * 1024;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap != null ? bitmap.getByteCount() : 0;
        }
    };

    private ImageLoader() {}

    public static void load(ImageView imageView, Uri uri, int placeholderRes) {
        if (imageView == null) return;
        if (uri == null) {
            if (placeholderRes != 0) imageView.setImageResource(placeholderRes);
            else imageView.setImageDrawable(null);
            imageView.setTag(R.id.poster, null);
            return;
        }

        String key = uri.toString();
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            imageView.setTag(R.id.poster, key);
            imageView.setImageBitmap(cached);
            return;
        }

        if (placeholderRes != 0) {
            imageView.setImageResource(placeholderRes);
        } else {
            imageView.setImageDrawable(null);
        }

        imageView.setTag(R.id.poster, key);
        Context context = imageView.getContext().getApplicationContext();

        EXECUTOR.execute(() -> {
            Bitmap bitmap = decodeUri(context, uri, 400, 224);
            if (bitmap != null) {
                CACHE.put(key, bitmap);
                MAIN_HANDLER.post(() -> {
                    if (key.equals(imageView.getTag(R.id.poster))) {
                        imageView.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    private static Bitmap decodeUri(Context context, Uri uri, int reqWidth, int reqHeight) {
        try {
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return decodeHttp(uri.toString(), reqWidth, reqHeight);
            } else {
                ContentResolver resolver = context.getContentResolver();
                // Pass 1: Decode bounds
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream in = resolver.openInputStream(uri)) {
                    if (in == null) return null;
                    BitmapFactory.decodeStream(in, null, options);
                }

                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
                options.inJustDecodeBounds = false;

                // Pass 2: Decode downsampled image
                try (InputStream in = resolver.openInputStream(uri)) {
                    if (in == null) return null;
                    return BitmapFactory.decodeStream(in, null, options);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to decode " + uri + ": " + e.getMessage());
            return null;
        }
    }

    private static Bitmap decodeHttp(String urlStr, int reqWidth, int reqHeight) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoInput(true);
            conn.connect();

            byte[] data;
            try (InputStream in = conn.getInputStream()) {
                data = readFully(in);
            }

            if (data == null || data.length == 0) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
            options.inJustDecodeBounds = false;

            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Exception e) {
            Log.w(TAG, "Failed http fetch " + urlStr + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readFully(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
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
}

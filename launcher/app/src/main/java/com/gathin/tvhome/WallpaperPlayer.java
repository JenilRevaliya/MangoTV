package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * High-performance, Apple tvOS-style wallpaper player for MangoTV Home.
 * Drives:
 * - Dynamic 1-minute rotation across high-res open-source & offline wallpapers
 * - 1200ms silky crossfade transitions between Layer A and Layer B
 * - Pre-computed Apple frosted glass blur layer (fades smoothly in and out as dock visibility toggles)
 */
final class WallpaperPlayer {
    private static final String TAG = "TvHome.WallpaperPlayer";
    private static final long ROTATION_INTERVAL_MS = 60_000L; // 1 minute per user requirement
    private static final long CROSSFADE_MS = 1200L; // Apple smooth crossfade

    private final Context context;
    private final FrameLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ImageView wallpaperA;
    private ImageView wallpaperB;
    private ImageView wallpaperBlurOverlay;

    private boolean showingA = true;
    private boolean active = false;
    private boolean closed = false;
    private boolean blurActive = false;
    private boolean isTransitioning = false;
    private int currentSlideIndex = 0;

    private final Runnable nextWallpaperRunnable = new Runnable() {
        @Override
        public void run() {
            if (!active || closed) return;
            transitionToNextWallpaper();
            handler.postDelayed(this, ROTATION_INTERVAL_MS);
        }
    };

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.context = context;
        this.root = root;

        wallpaperA = root.findViewById(R.id.wallpaperA);
        wallpaperB = root.findViewById(R.id.wallpaperB);
        wallpaperBlurOverlay = root.findViewById(R.id.wallpaperBlurOverlay);

        showFirst();
    }

    private void showFirst() {
        MangoWallpaperManager.WallpaperBundle initial = MangoWallpaperManager.getInitialWallpaper(context);
        if (initial != null && initial.sharp != null) {
            if (wallpaperA != null) {
                wallpaperA.setImageBitmap(initial.sharp);
                wallpaperA.setAlpha(1.0f);
            }
            if (wallpaperB != null) {
                wallpaperB.setImageBitmap(initial.sharp);
                wallpaperB.setAlpha(0.0f);
            }
            if (wallpaperBlurOverlay != null && initial.blurred != null) {
                wallpaperBlurOverlay.setImageBitmap(initial.blurred);
                wallpaperBlurOverlay.setAlpha(blurActive ? 1.0f : 0.0f);
            }
            showingA = true;
        } else {
            handler.post(this::transitionToNextWallpaper);
        }
    }

    void start() {
        if (closed) return;
        active = true;
        ImageView current = showingA ? wallpaperA : wallpaperB;
        if (current == null || current.getDrawable() == null) {
            showFirst();
        } else {
            current.setAlpha(1.0f);
        }
        handler.removeCallbacks(nextWallpaperRunnable);
        handler.postDelayed(nextWallpaperRunnable, ROTATION_INTERVAL_MS);
    }

    void stop() {
        active = false;
        handler.removeCallbacks(nextWallpaperRunnable);
    }

    void close() {
        active = false;
        closed = true;
        handler.removeCallbacks(nextWallpaperRunnable);
        if (wallpaperA != null) wallpaperA.animate().cancel();
        if (wallpaperB != null) wallpaperB.animate().cancel();
        if (wallpaperBlurOverlay != null) wallpaperBlurOverlay.animate().cancel();
    }

    void update() {
        // Called on external wake-up
        if (!active || closed || isTransitioning) return;
        transitionToNextWallpaper();
    }

    private void transitionToNextWallpaper() {
        if (closed || !active || isTransitioning) return;
        isTransitioning = true;
        currentSlideIndex++;

        MangoWallpaperManager.getNextWallpaper(context, currentSlideIndex, bundle -> {
            if (closed || !active) {
                isTransitioning = false;
                return;
            }
            if (bundle == null || bundle.sharp == null) {
                ImageView current = showingA ? wallpaperA : wallpaperB;
                if (current != null) current.setAlpha(1.0f);
                isTransitioning = false;
                return;
            }

            if (wallpaperA == null || wallpaperB == null) {
                isTransitioning = false;
                return;
            }

            wallpaperA.animate().cancel();
            wallpaperB.animate().cancel();

            if (showingA) {
                // Currently wallpaperA is visible (on bottom). wallpaperB is on top.
                // 1. Ensure wallpaperA is fully visible underneath so background never shows
                wallpaperA.setAlpha(1.0f);
                // 2. Load new bitmap into wallpaperB (on top) and fade it in
                wallpaperB.setImageBitmap(bundle.sharp);
                wallpaperB.setAlpha(0.0f);
                wallpaperB.animate()
                        .alpha(1.0f)
                        .setDuration(CROSSFADE_MS)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                if (wallpaperA != null) wallpaperA.setAlpha(0.0f);
                                isTransitioning = false;
                            }
                        })
                        .start();
                showingA = false;
            } else {
                // Currently wallpaperB is visible (on top). wallpaperA is on bottom.
                // 1. Load new bitmap into wallpaperA and set its alpha to 1.0 immediately underneath
                wallpaperA.setImageBitmap(bundle.sharp);
                wallpaperA.setAlpha(1.0f);
                // 2. Fade out wallpaperB to reveal wallpaperA underneath
                wallpaperB.animate()
                        .alpha(0.0f)
                        .setDuration(CROSSFADE_MS)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                isTransitioning = false;
                            }
                        })
                        .start();
                showingA = true;
            }

            if (wallpaperBlurOverlay != null && bundle.blurred != null) {
                wallpaperBlurOverlay.setImageBitmap(bundle.blurred);
            }
        });
    }

    /**
     * Controls the Apple frosted blur overlay.
     * When apps move to row 3 (dock invisible), blur is activated (alpha -> 1.0).
     * When dock becomes visible again, blur is wiped off (alpha -> 0.0).
     */
    public void setBlurActive(boolean active, boolean animate) {
        this.blurActive = active;
        if (wallpaperBlurOverlay == null) return;

        float targetAlpha = active ? 1.0f : 0.0f;
        if (wallpaperBlurOverlay.getAlpha() == targetAlpha) return;

        wallpaperBlurOverlay.animate().cancel();
        if (animate) {
            wallpaperBlurOverlay.animate()
                    .alpha(targetAlpha)
                    .setDuration(350)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
        } else {
            wallpaperBlurOverlay.setAlpha(targetAlpha);
        }
    }
}

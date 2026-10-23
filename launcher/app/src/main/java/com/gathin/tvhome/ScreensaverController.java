package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Controller for the Apple TV-style Aerial Screensaver.
 * Handles smooth fade-in wallpaper transitions, Ken Burns drift, dynamic Apple greeting,
 * and Apple weather condition indicators with burn-in prevention.
 */
public final class ScreensaverController {

    private static final long SLIDE_DURATION_MS = 15_000L; // 15-second timer per user requirement
    private static final long CROSSFADE_MS = 1500L;

    private final Context context;
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ImageView wallpaperA;
    private ImageView wallpaperB;
    private View dreamContent;
    private TextView dreamGreeting;
    private ImageView dreamWeatherIcon;
    private TextView dreamTemperature;
    private TextView dreamWeatherDesc;
    private ImageView dreamLogo;

    private int currentSlideIndex = 0;
    private boolean showingA = true;
    private boolean running = false;
    private ObjectAnimator currentAlphaAnim = null;

    private final Runnable nextSlideRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            transitionToNextSlide();
            handler.postDelayed(this, SLIDE_DURATION_MS);
        }
    };

    public ScreensaverController(Context context, View root) {
        this.context = context;
        this.root = root;
        initViews();
    }

    private void initViews() {
        wallpaperA = root.findViewById(R.id.wallpaperA);
        wallpaperB = root.findViewById(R.id.wallpaperB);
        dreamContent = root.findViewById(R.id.dreamContent);
        dreamGreeting = root.findViewById(R.id.dreamGreeting);
        dreamWeatherIcon = root.findViewById(R.id.dreamWeatherIcon);
        dreamTemperature = root.findViewById(R.id.dreamTemperature);
        dreamWeatherDesc = root.findViewById(R.id.dreamWeatherDesc);
        dreamLogo = root.findViewById(R.id.dreamLogo);
    }

    private final WeatherManager.WeatherListener weatherListener = this::applyWeatherData;

    public void start() {
        running = true;
        updateGreetingAndWeather();
        WeatherManager.registerListener(weatherListener);

        // Initial wallpaper immediately from MangoWallpaperManager
        MangoWallpaperManager.WallpaperBundle initial = MangoWallpaperManager.getInitialWallpaper(context);
        if (initial != null && initial.sharp != null && wallpaperA != null) {
            wallpaperA.setImageBitmap(initial.sharp);
            wallpaperA.setAlpha(1f);
            wallpaperA.setScaleX(1.0f);
            wallpaperA.setScaleY(1.0f);
            wallpaperA.animate()
                    .scaleX(1.04f)
                    .scaleY(1.04f)
                    .setDuration(SLIDE_DURATION_MS)
                    .start();
            showingA = true;
        }

        handler.removeCallbacks(nextSlideRunnable);
        handler.postDelayed(nextSlideRunnable, SLIDE_DURATION_MS);
    }

    public void stop() {
        running = false;
        WeatherManager.unregisterListener(weatherListener);
        handler.removeCallbacks(nextSlideRunnable);
        if (currentAlphaAnim != null) {
            currentAlphaAnim.cancel();
            currentAlphaAnim = null;
        }
        if (wallpaperA != null) wallpaperA.animate().cancel();
        if (wallpaperB != null) wallpaperB.animate().cancel();
        if (dreamLogo != null) dreamLogo.animate().cancel();
        if (dreamContent != null) dreamContent.animate().cancel();
    }

    private void transitionToNextSlide() {
        if (!running) return;

        currentSlideIndex++;
        MangoWallpaperManager.getNextWallpaper(context, currentSlideIndex, bundle -> {
            if (!running || bundle == null || bundle.sharp == null) return;

            ImageView incoming = showingA ? wallpaperB : wallpaperA;
            ImageView outgoing = showingA ? wallpaperA : wallpaperB;
            if (incoming == null || outgoing == null) return;

            incoming.setImageBitmap(bundle.sharp);
            incoming.setAlpha(0f);
            incoming.setScaleX(1.0f);
            incoming.setScaleY(1.0f);

            // Apple smooth crossfade on alpha
            if (currentAlphaAnim != null) {
                currentAlphaAnim.cancel();
            }
            ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f);
            alphaAnim.setDuration(CROSSFADE_MS);
            alphaAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    outgoing.setAlpha(0f);
                    outgoing.setImageDrawable(null);
                    if (currentAlphaAnim == animation) {
                        currentAlphaAnim = null;
                    }
                }
            });
            currentAlphaAnim = alphaAnim;
            alphaAnim.start();

            // Subtle Ken Burns slow zoom (1.0f -> 1.04f)
            incoming.animate()
                    .scaleX(1.04f)
                    .scaleY(1.04f)
                    .setDuration(SLIDE_DURATION_MS)
                    .start();

            showingA = !showingA;

            updateGreetingAndWeather();
            nudgeForBurnInProtection();
        });
    }

    private void updateGreetingAndWeather() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);

        if (dreamGreeting != null) {
            if (hour >= 5 && hour < 12) {
                dreamGreeting.setText("GOOD MORNING");
            } else if (hour >= 12 && hour < 17) {
                dreamGreeting.setText("GOOD AFTERNOON");
            } else if (hour >= 17 && hour < 22) {
                dreamGreeting.setText("GOOD EVENING");
            } else {
                dreamGreeting.setText("GOOD NIGHT");
            }
        }

        applyWeatherData(WeatherManager.getTemperature(), WeatherManager.getWeatherCode(), WeatherManager.getCondition());
        WeatherManager.fetchWeatherIfNeeded();
    }

    private void applyWeatherData(int temp, int weatherCode, String condition) {
        int iconRes = WeatherManager.getCurrentWeatherIconRes();
        if (dreamWeatherIcon != null) {
            dreamWeatherIcon.setImageResource(iconRes);
        }
        if (dreamTemperature != null) {
            dreamTemperature.setText(temp + "°");
        }
        if (dreamWeatherDesc != null) {
            dreamWeatherDesc.setText(condition); // Location removed per user request
        }
    }

    private void nudgeForBurnInProtection() {
        if (dreamContent != null) {
            // Subtle drift within +/- 12px around rule-of-thirds anchor
            float driftX = (float) (Math.sin(currentSlideIndex * 1.3) * 12);
            float driftY = (float) (Math.cos(currentSlideIndex * 1.1) * 8);
            dreamContent.animate()
                    .translationX(driftX)
                    .translationY(driftY)
                    .setDuration(2000)
                    .start();
        }
        if (dreamLogo != null) {
            // Anti-phase drift for watermark
            float driftX = (float) (-Math.sin(currentSlideIndex * 1.3) * 10);
            float driftY = (float) (Math.cos(currentSlideIndex * 1.1) * 6);
            dreamLogo.animate()
                    .translationX(driftX)
                    .translationY(driftY)
                    .setDuration(2000)
                    .start();
        }
    }
}

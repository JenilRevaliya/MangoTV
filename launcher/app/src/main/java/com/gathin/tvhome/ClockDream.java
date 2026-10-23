package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: Apple TV aerial style wallpapers with smooth crossfades,
 * rule-of-thirds thick clock, greeting, and weather indicators.
 */
public class ClockDream extends DreamService {

    private ScreensaverController controller;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        controller = new ScreensaverController(this, getWindow().getDecorView());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (controller != null) controller.start();
    }

    @Override
    public void onDreamingStopped() {
        if (controller != null) controller.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (controller != null) controller.stop();
        super.onDetachedFromWindow();
    }
}

package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * Activity version of the Apple-style screensaver for immediate preview and testing.
 * Any key press finishes the activity.
 */
public class ScreensaverActivity extends Activity {

    private ScreensaverController controller;
    private long launchTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.dream_clock);

        launchTime = System.currentTimeMillis();
        controller = new ScreensaverController(this, findViewById(android.R.id.content));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controller != null) controller.start();
    }

    @Override
    protected void onPause() {
        if (controller != null) controller.stop();
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        android.util.Log.d("ScreensaverActivity", "dispatchKeyEvent: action=" + event.getAction() 
                + " keyCode=" + event.getKeyCode() + " timeDelta=" + (System.currentTimeMillis() - launchTime));
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // Ignore accidental key events fired during launch transition (e.g. within 1.2s)
            if (System.currentTimeMillis() - launchTime < 1200) {
                return true;
            }
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}

package com.gathin.tvhome.remote;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dispatches remote control commands to the Android TV system and active media sessions.
 */
public class MangoRemoteKeyInjector {
    private static final String TAG = "MangoRemoteKeyInjector";

    public interface RemoteKeyReceiver {
        boolean onDirectKeyEvent(int keyCode);
        void onDirectTextEntered(String text);
    }

    private final Context context;
    private final AudioManager audioManager;
    private final PackageManager packageManager;
    private final ExecutorService keyExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Instrumentation instrumentation;
    private RemoteKeyReceiver directReceiver;

    public MangoRemoteKeyInjector(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.packageManager = this.context.getPackageManager();
        keyExecutor.execute(() -> {
            try {
                instrumentation = new Instrumentation();
            } catch (Throwable t) {
                Log.w(TAG, "Instrumentation unavailable in this process", t);
            }
        });
    }

    public void setDirectReceiver(RemoteKeyReceiver receiver) {
        this.directReceiver = receiver;
    }

    public boolean handleNavigation(String direction) {
        if (direction == null) return false;
        int keyCode;
        switch (direction.toLowerCase()) {
            case MangoRemoteProtocol.DIR_UP:
                keyCode = KeyEvent.KEYCODE_DPAD_UP;
                break;
            case MangoRemoteProtocol.DIR_DOWN:
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN;
                break;
            case MangoRemoteProtocol.DIR_LEFT:
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT;
                break;
            case MangoRemoteProtocol.DIR_RIGHT:
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                break;
            case MangoRemoteProtocol.DIR_SELECT:
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER;
                break;
            case MangoRemoteProtocol.DIR_BACK:
                keyCode = KeyEvent.KEYCODE_BACK;
                break;
            case MangoRemoteProtocol.DIR_HOME:
                keyCode = KeyEvent.KEYCODE_HOME;
                break;
            case MangoRemoteProtocol.DIR_MENU:
                keyCode = KeyEvent.KEYCODE_MENU;
                break;
            default:
                return false;
        }

        // Special handling for HOME to guarantee launcher focus
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(homeIntent);
            return true;
        }

        dispatchKey(keyCode);
        return true;
    }

    public boolean handleMedia(String action) {
        if (action == null) return false;
        int keyCode;
        switch (action.toLowerCase()) {
            case MangoRemoteProtocol.MEDIA_PLAY_PAUSE:
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                break;
            case MangoRemoteProtocol.MEDIA_PLAY:
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY;
                break;
            case MangoRemoteProtocol.MEDIA_PAUSE:
                keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE;
                break;
            case MangoRemoteProtocol.MEDIA_FAST_FORWARD:
                keyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
                break;
            case MangoRemoteProtocol.MEDIA_REWIND:
                keyCode = KeyEvent.KEYCODE_MEDIA_REWIND;
                break;
            case MangoRemoteProtocol.MEDIA_NEXT:
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT;
                break;
            case MangoRemoteProtocol.MEDIA_PREV:
                keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                break;
            default:
                return false;
        }

        // AudioManager.dispatchMediaKeyEvent dispatches system-wide across YouTube, Netflix, Spotify
        if (audioManager != null) {
            long now = System.currentTimeMillis();
            audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
            return true;
        }

        dispatchKey(keyCode);
        return true;
    }

    public boolean handleVolume(String action) {
        if (audioManager == null || action == null) return false;
        int direction;
        switch (action.toLowerCase()) {
            case MangoRemoteProtocol.VOLUME_UP:
                direction = AudioManager.ADJUST_RAISE;
                break;
            case MangoRemoteProtocol.VOLUME_DOWN:
                direction = AudioManager.ADJUST_LOWER;
                break;
            case MangoRemoteProtocol.VOLUME_MUTE:
                direction = AudioManager.ADJUST_TOGGLE_MUTE;
                break;
            default:
                return false;
        }

        audioManager.adjustSuggestedStreamVolume(
                direction,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_PLAY_SOUND
        );
        return true;
    }

    public boolean handleAppLaunch(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        try {
            Intent launchIntent = packageManager.getLeanbackLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                launchIntent = packageManager.getLaunchIntentForPackage(packageName);
            }
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(launchIntent);
                return true;
            } else {
                Log.w(TAG, "No launch intent found for package: " + packageName);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error launching app: " + packageName, t);
        }
        return false;
    }

    public boolean handleTextInput(String text) {
        if (text == null) return false;
        if (directReceiver != null) {
            mainHandler.post(() -> directReceiver.onDirectTextEntered(text));
            return true;
        }
        // Fallback: character by character key injection for ASCII
        keyExecutor.execute(() -> {
            for (char c : text.toCharArray()) {
                int code = getKeyCodeForChar(c);
                if (code != KeyEvent.KEYCODE_UNKNOWN) {
                    dispatchKeySync(code);
                }
            }
        });
        return true;
    }

    private void dispatchKey(int keyCode) {
        if (directReceiver != null) {
            mainHandler.post(() -> {
                if (directReceiver != null) {
                    directReceiver.onDirectKeyEvent(keyCode);
                }
            });
        }

        keyExecutor.execute(() -> dispatchKeySync(keyCode));
    }

    private void dispatchKeySync(int keyCode) {
        try {
            if (instrumentation != null) {
                instrumentation.sendKeyDownUpSync(keyCode);
            }
        } catch (Throwable ignored) {
            // Instrumentation can throw SecurityException if injected across unauthorized windows;
            // AudioManager and DirectReceiver handle system-level keys.
        }
    }

    private int getKeyCodeForChar(char c) {
        if (c >= 'a' && c <= 'z') return KeyEvent.KEYCODE_A + (c - 'a');
        if (c >= 'A' && c <= 'Z') return KeyEvent.KEYCODE_A + (c - 'A');
        if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + (c - '0');
        if (c == ' ') return KeyEvent.KEYCODE_SPACE;
        if (c == '\n') return KeyEvent.KEYCODE_ENTER;
        if (c == '\b') return KeyEvent.KEYCODE_DEL;
        return KeyEvent.KEYCODE_UNKNOWN;
    }
}

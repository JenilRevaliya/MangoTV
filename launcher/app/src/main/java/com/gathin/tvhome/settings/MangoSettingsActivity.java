package com.gathin.tvhome.settings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.gathin.tvhome.Config;
import com.gathin.tvhome.R;
import com.gathin.tvhome.remote.MangoRemotePairingManager;
import com.gathin.tvhome.remote.MangoRemoteProtocol;
import com.gathin.tvhome.settings.connectivity.SystemConnectivityHelper;
import com.gathin.tvhome.settings.qr.QrCodeGenerator;

import java.io.File;
import java.util.Locale;

/**
 * Premium, Apple-inspired TV Settings Activity for Mango TV.
 * Exposes real connectivity cards, Mango ecosystem toggles, QR Remote pairing,
 * and native Android system settings integration.
 */
public final class MangoSettingsActivity extends Activity {

    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Connectivity Cards
    private View cardWifi;
    private View cardBluetooth;
    private TextView textWifiStatus;
    private TextView textBluetoothStatus;

    // Mango Connectivity
    private View cardMangoShare;
    private View cardMangoPlay;
    private View cardMangoBuds;
    private View cardMangoRemote;

    private Switch switchMangoShare;
    private Switch switchMangoPlay;
    private Switch switchMangoBuds;
    private Switch switchMangoRemote;

    private TextView textSharePill;
    private TextView textPlayPill;
    private TextView textBudsPill;
    private TextView textRemotePill;

    // Display & Appearance
    private View cardAnimations;
    private View cardAdvancedEffects;
    private Switch switchAnimations;
    private Switch switchAdvancedEffects;

    // Actions & Privacy
    private View cardSoundSettings;
    private View cardClearCooldowns;
    private View cardRevokeRemotes;
    private View cardDeviceSettings;
    private TextView textSysInfo;

    // QR Pairing Overlay
    private FrameLayout qrDialogOverlay;
    private ImageView qrImageView;
    private TextView qrPinText;
    private TextView qrUrlText;
    private TextView btnQrRegenerate;
    private TextView btnQrDone;

    private MangoRemotePairingManager pairingManager;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;
    private boolean mangoShare;
    private boolean mangoPlay;
    private boolean mangoBuds;
    private boolean mangoRemote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mango_settings);

        choices = getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        pairingManager = new MangoRemotePairingManager(this);

        initViews();
        loadPreferences();
        bindListeners();
        setupFocusScales();
        refreshSystemDiagnostics();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConnectivityCards();
        refreshPreferencesUi();
    }

    private void initViews() {
        // Connectivity Cards
        cardWifi = findViewById(R.id.cardWifi);
        cardBluetooth = findViewById(R.id.cardBluetooth);
        textWifiStatus = findViewById(R.id.textWifiStatus);
        textBluetoothStatus = findViewById(R.id.textBluetoothStatus);

        // Mango Features
        cardMangoShare = findViewById(R.id.cardMangoShare);
        cardMangoPlay = findViewById(R.id.cardMangoPlay);
        cardMangoBuds = findViewById(R.id.cardMangoBuds);
        cardMangoRemote = findViewById(R.id.cardMangoRemote);

        switchMangoShare = findViewById(R.id.switchMangoShare);
        switchMangoPlay = findViewById(R.id.switchMangoPlay);
        switchMangoBuds = findViewById(R.id.switchMangoBuds);
        switchMangoRemote = findViewById(R.id.switchMangoRemote);

        textSharePill = findViewById(R.id.textSharePill);
        textPlayPill = findViewById(R.id.textPlayPill);
        textBudsPill = findViewById(R.id.textBudsPill);
        textRemotePill = findViewById(R.id.textRemotePill);

        // Display
        columnChoices[0] = findViewById(R.id.btnCol4);
        columnChoices[1] = findViewById(R.id.btnCol5);
        columnChoices[2] = findViewById(R.id.btnCol6);
        columnChoices[3] = findViewById(R.id.btnCol7);

        cardAnimations = findViewById(R.id.cardAnimations);
        cardAdvancedEffects = findViewById(R.id.cardAdvancedEffects);
        switchAnimations = findViewById(R.id.switchAnimations);
        switchAdvancedEffects = findViewById(R.id.switchAdvancedEffects);

        // Sound, Privacy & Device Settings
        cardSoundSettings = findViewById(R.id.cardSoundSettings);
        cardClearCooldowns = findViewById(R.id.cardClearCooldowns);
        cardRevokeRemotes = findViewById(R.id.cardRevokeRemotes);
        cardDeviceSettings = findViewById(R.id.cardDeviceSettings);
        textSysInfo = findViewById(R.id.textSysInfo);

        // QR Modal
        qrDialogOverlay = findViewById(R.id.qrDialogOverlay);
        qrImageView = findViewById(R.id.qrImageView);
        qrPinText = findViewById(R.id.qrPinText);
        qrUrlText = findViewById(R.id.qrUrlText);
        btnQrRegenerate = findViewById(R.id.btnQrRegenerate);
        btnQrDone = findViewById(R.id.btnQrDone);
    }

    private void loadPreferences() {
        Config config = Config.load(this);
        columns = config.columns;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        mangoShare = choices.getBoolean("mango_share_enabled", true);
        mangoPlay = choices.getBoolean("mango_play_enabled", true);
        mangoBuds = choices.getBoolean("mango_buds_enabled", true);
        mangoRemote = choices.getBoolean("mango_remote_enabled", true);
    }

    private void bindListeners() {
        // Wi-Fi Card -> Open native Wi-Fi settings
        cardWifi.setOnClickListener(v -> safeStartActivity(SystemConnectivityHelper.getWifiSettingsIntent()));

        // Bluetooth Card -> Open native Bluetooth settings
        cardBluetooth.setOnClickListener(v -> safeStartActivity(SystemConnectivityHelper.getBluetoothSettingsIntent()));

        // Mango Share Toggle
        cardMangoShare.setOnClickListener(v -> {
            mangoShare = !mangoShare;
            choices.edit().putBoolean("mango_share_enabled", mangoShare).apply();
            setResult(RESULT_OK);
            refreshPreferencesUi();
        });

        // Mango Play Toggle
        cardMangoPlay.setOnClickListener(v -> {
            mangoPlay = !mangoPlay;
            choices.edit().putBoolean("mango_play_enabled", mangoPlay).apply();
            setResult(RESULT_OK);
            refreshPreferencesUi();
        });

        // Mango Buds Toggle
        cardMangoBuds.setOnClickListener(v -> {
            mangoBuds = !mangoBuds;
            choices.edit().putBoolean("mango_buds_enabled", mangoBuds).apply();
            setResult(RESULT_OK);
            refreshPreferencesUi();
        });

        // Mango Remote -> Open QR Pairing Overlay
        cardMangoRemote.setOnClickListener(v -> showQrPairingDialog());

        // Grid columns buttons (4, 5, 6, 7)
        for (int i = 0; i < columnChoices.length; i++) {
            final int value = i + 4;
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refreshPreferencesUi();
            });
        }

        // Animations Toggle
        cardAnimations.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refreshPreferencesUi();
        });

        // Advanced Effects Toggle
        cardAdvancedEffects.setOnClickListener(v -> {
            if (!animations) {
                Toast.makeText(this, "Enable Smooth Animations first", Toast.LENGTH_SHORT).show();
                return;
            }
            advancedEffects = !advancedEffects;
            choices.edit().putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refreshPreferencesUi();
        });

        // Sound Settings
        cardSoundSettings.setOnClickListener(v -> safeStartActivity(SystemConnectivityHelper.getSoundSettingsIntent()));

        // Clear Device Cooldowns
        cardClearCooldowns.setOnClickListener(v -> {
            choices.edit().putLong("cooldowns_cleared_time", System.currentTimeMillis()).apply();
            Toast.makeText(this, "Device suppression cooldowns reset", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
        });

        // Revoke All Paired Phones
        cardRevokeRemotes.setOnClickListener(v -> {
            if (pairingManager != null) {
                pairingManager.clearAllPairedDevices();
            }
            Toast.makeText(this, "All paired remotes revoked", Toast.LENGTH_SHORT).show();
            refreshPreferencesUi();
        });

        // Device Settings -> Native Android settings
        cardDeviceSettings.setOnClickListener(v -> safeStartActivity(SystemConnectivityHelper.getDeviceSettingsIntent()));

        // QR Modal Buttons
        btnQrDone.setOnClickListener(v -> hideQrPairingDialog());
        btnQrRegenerate.setOnClickListener(v -> generateAndDisplayQrSession());
        qrDialogOverlay.setOnClickListener(v -> hideQrPairingDialog());
    }

    private void refreshConnectivityCards() {
        SystemConnectivityHelper.WifiState wifi = SystemConnectivityHelper.getWifiState(this);
        textWifiStatus.setText(wifi.statusSummary);
        textWifiStatus.setTextColor(wifi.isConnected ? 0xFF34C759 : 0xFFA1A1A6);

        SystemConnectivityHelper.BtState bt = SystemConnectivityHelper.getBluetoothState(this);
        textBluetoothStatus.setText(bt.statusSummary);
        textBluetoothStatus.setTextColor(bt.isEnabled ? 0xFF34C759 : 0xFFA1A1A6);
    }

    private void refreshPreferencesUi() {
        switchMangoShare.setChecked(mangoShare);
        textSharePill.setText(mangoShare ? "Enabled" : "Off");
        textSharePill.setTextColor(mangoShare ? 0xFF34C759 : 0xFF8E8E93);

        switchMangoPlay.setChecked(mangoPlay);
        textPlayPill.setText(mangoPlay ? "Enabled" : "Off");
        textPlayPill.setTextColor(mangoPlay ? 0xFF34C759 : 0xFF8E8E93);

        switchMangoBuds.setChecked(mangoBuds);
        textBudsPill.setText(mangoBuds ? "Enabled" : "Off");
        textBudsPill.setTextColor(mangoBuds ? 0xFF34C759 : 0xFF8E8E93);

        switchMangoRemote.setChecked(mangoRemote);
        int pairedCount = pairingManager != null ? pairingManager.getPairedDeviceCount() : 0;
        textRemotePill.setText(pairedCount > 0 ? (pairedCount + " Paired • Tap to Pair") : "Pair Phone");
        textRemotePill.setTextColor(0xFFFF9500);

        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }

        switchAnimations.setChecked(animations);
        switchAdvancedEffects.setChecked(advancedEffects);
        cardAdvancedEffects.setAlpha(animations ? 1.0f : 0.45f);
        cardAdvancedEffects.setEnabled(animations);
    }

    private void refreshSystemDiagnostics() {
        try {
            String model = Build.MANUFACTURER + " " + Build.MODEL;
            String androidVer = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
            String ip = SystemConnectivityHelper.getLocalIpAddress();

            File dataDir = Environment.getDataDirectory();
            StatFs stat = new StatFs(dataDir.getPath());
            long freeBytes = stat.getAvailableBytes();
            long totalBytes = stat.getTotalBytes();
            String freeGb = String.format(Locale.US, "%.1f", freeBytes / (1024.0 * 1024.0 * 1024.0));
            String totalGb = String.format(Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0 * 1024.0));

            String text = "Mango TV v2.4 • " + androidVer + " • " + model +
                    "\nStorage: " + freeGb + " GB free of " + totalGb + " GB • Host IP: " + ip;
            textSysInfo.setText(text);
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // Dynamic QR Remote Pairing Dialog
    // =========================================================================
    private void showQrPairingDialog() {
        qrDialogOverlay.setVisibility(View.VISIBLE);
        qrDialogOverlay.setAlpha(0f);
        qrDialogOverlay.animate().alpha(1f).setDuration(220).start();
        generateAndDisplayQrSession();
        btnQrDone.requestFocus();
    }

    private void hideQrPairingDialog() {
        if (qrDialogOverlay.getVisibility() != View.VISIBLE) return;
        qrDialogOverlay.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            qrDialogOverlay.setVisibility(View.GONE);
            cardMangoRemote.requestFocus();
        }).start();
    }

    private void generateAndDisplayQrSession() {
        if (pairingManager == null) {
            pairingManager = new MangoRemotePairingManager(this);
        }

        String pin = pairingManager.startPairingSession();
        String localIp = SystemConnectivityHelper.getLocalIpAddress();
        String pairingUrl = "http://" + localIp + ":" + MangoRemoteProtocol.DEFAULT_PORT + "/?pin=" + pin;

        // Display PIN with spaces for effortless reading
        if (pin != null && pin.length() == 4) {
            qrPinText.setText(pin.charAt(0) + " " + pin.charAt(1) + " " + pin.charAt(2) + " " + pin.charAt(3));
        } else {
            qrPinText.setText(pin);
        }

        qrUrlText.setText("http://" + localIp + ":" + MangoRemoteProtocol.DEFAULT_PORT);

        // Generate dynamic QR code Bitmap asynchronously
        new Thread(() -> {
            try {
                Bitmap qrBitmap = QrCodeGenerator.generateQrBitmap(pairingUrl, 380, 380);
                handler.post(() -> {
                    qrImageView.setImageBitmap(qrBitmap);
                });
            } catch (Throwable ignored) {}
        }).start();
    }

    private void setupFocusScales() {
        View[] focusableCards = new View[]{
                cardWifi, cardBluetooth,
                cardMangoShare, cardMangoPlay, cardMangoBuds, cardMangoRemote,
                cardAnimations, cardAdvancedEffects,
                cardSoundSettings, cardClearCooldowns, cardRevokeRemotes, cardDeviceSettings
        };

        for (View view : focusableCards) {
            if (view == null) continue;
            view.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(180).start();
                } else {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(180).start();
                }
            });
        }
    }

    private void safeStartActivity(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Settings panel unavailable on this TV device", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (qrDialogOverlay.getVisibility() == View.VISIBLE) {
                hideQrPairingDialog();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}

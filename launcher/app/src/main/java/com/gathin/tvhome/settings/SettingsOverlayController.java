package com.gathin.tvhome.settings;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
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

/**
 * Controller for the Apple-inspired right-side floating glass Settings overlay.
 * Slides in over the live home screen without interrupting launcher wallpaper, clock,
 * or running applications.
 */
public final class SettingsOverlayController {

    private final Activity activity;
    private final View overlayRoot;
    private final View scrim;
    private final LinearLayout panel;
    private final FrameLayout btnClose;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences choices;
    private final MangoRemotePairingManager pairingManager;

    // Connectivity Cards
    private final View cardWifi;
    private final View cardBluetooth;
    private final ImageView iconWifi;
    private final ImageView chevronWifi;
    private final TextView textWifiTitle;
    private final TextView textWifiStatus;
    private final TextView textWifiSsid;
    private final ImageView iconBluetooth;
    private final ImageView chevronBluetooth;
    private final TextView textBtTitle;
    private final TextView textBtStatus;
    private final TextView textBtDevices;

    // Mango Services
    private final View rowShare;
    private final View rowPlay;
    private final View rowBuds;
    private final View rowRemote;
    private final Switch switchShare;
    private final Switch switchPlay;
    private final Switch switchBuds;
    private final TextView textRemoteSub;

    // System Rows
    private final View rowDisplay;
    private final View rowPrivacy;
    private final View rowDevice;
    private final ImageView iconDevice;
    private final TextView textDeviceTitle;
    private final ImageView chevronDevice;

    // Dynamic QR Modal
    private final FrameLayout qrModal;
    private final ImageView qrImageView;
    private final TextView qrPinText;
    private final TextView qrUrlText;
    private final TextView btnQrRegenerate;
    private final TextView btnQrDone;

    private boolean isOpen = false;
    private Runnable onDismissListener;

    public SettingsOverlayController(Activity activity, View overlayRoot) {
        this.activity = activity;
        this.overlayRoot = overlayRoot;
        this.choices = activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        this.pairingManager = new MangoRemotePairingManager(activity);

        this.scrim = overlayRoot.findViewById(R.id.settingsScrim);
        this.panel = overlayRoot.findViewById(R.id.settingsPanel);
        this.btnClose = overlayRoot.findViewById(R.id.btnSettingsClose);

        this.cardWifi = overlayRoot.findViewById(R.id.cardSettingsWifi);
        this.cardBluetooth = overlayRoot.findViewById(R.id.cardSettingsBluetooth);
        this.iconWifi = overlayRoot.findViewById(R.id.iconSettingsWifi);
        this.chevronWifi = overlayRoot.findViewById(R.id.chevronSettingsWifi);
        this.textWifiTitle = overlayRoot.findViewById(R.id.textSettingsWifiTitle);
        this.textWifiStatus = overlayRoot.findViewById(R.id.textSettingsWifiStatus);
        this.textWifiSsid = overlayRoot.findViewById(R.id.textSettingsWifiSsid);

        this.iconBluetooth = overlayRoot.findViewById(R.id.iconSettingsBluetooth);
        this.chevronBluetooth = overlayRoot.findViewById(R.id.chevronSettingsBluetooth);
        this.textBtTitle = overlayRoot.findViewById(R.id.textSettingsBtTitle);
        this.textBtStatus = overlayRoot.findViewById(R.id.textSettingsBtStatus);
        this.textBtDevices = overlayRoot.findViewById(R.id.textSettingsBtDevices);

        this.rowShare = overlayRoot.findViewById(R.id.rowSettingsShare);
        this.rowPlay = overlayRoot.findViewById(R.id.rowSettingsPlay);
        this.rowBuds = overlayRoot.findViewById(R.id.rowSettingsBuds);
        this.rowRemote = overlayRoot.findViewById(R.id.rowSettingsRemote);
        this.switchShare = overlayRoot.findViewById(R.id.switchSettingsShare);
        this.switchPlay = overlayRoot.findViewById(R.id.switchSettingsPlay);
        this.switchBuds = overlayRoot.findViewById(R.id.switchSettingsBuds);
        this.textRemoteSub = overlayRoot.findViewById(R.id.textSettingsRemoteSub);

        this.rowDisplay = overlayRoot.findViewById(R.id.rowSettingsDisplay);
        this.rowPrivacy = overlayRoot.findViewById(R.id.rowSettingsPrivacy);
        this.rowDevice = overlayRoot.findViewById(R.id.rowSettingsDevice);
        this.iconDevice = overlayRoot.findViewById(R.id.iconSettingsDevice);
        this.textDeviceTitle = overlayRoot.findViewById(R.id.textSettingsDeviceTitle);
        this.chevronDevice = overlayRoot.findViewById(R.id.chevronSettingsDevice);

        this.qrModal = overlayRoot.findViewById(R.id.settingsQrModal);
        this.qrImageView = overlayRoot.findViewById(R.id.settingsQrImageView);
        this.qrPinText = overlayRoot.findViewById(R.id.settingsQrPinText);
        this.qrUrlText = overlayRoot.findViewById(R.id.settingsQrUrlText);
        this.btnQrRegenerate = overlayRoot.findViewById(R.id.btnSettingsQrRegenerate);
        this.btnQrDone = overlayRoot.findViewById(R.id.btnSettingsQrDone);

        bindListeners();
        setupFocusScales();

        updateWifiCardAppearance(false);
        updateBtCardAppearance(false);
        updateDeviceRowAppearance(false);
    }

    public void setOnDismissListener(Runnable listener) {
        this.onDismissListener = listener;
    }

    private void bindListeners() {
        scrim.setOnClickListener(v -> dismiss());
        btnClose.setOnClickListener(v -> dismiss());

        cardWifi.setOnClickListener(v -> safeStartActivity(SystemConnectivityHelper.getWifiSettingsIntents()));
        cardBluetooth.setOnClickListener(v -> safeStartActivity(SystemConnectivityHelper.getBluetoothSettingsIntents()));

        rowShare.setOnClickListener(v -> {
            boolean current = choices.getBoolean("mango_share_enabled", true);
            choices.edit().putBoolean("mango_share_enabled", !current).apply();
            refreshState();
        });

        rowPlay.setOnClickListener(v -> {
            boolean current = choices.getBoolean("mango_play_enabled", true);
            choices.edit().putBoolean("mango_play_enabled", !current).apply();
            refreshState();
        });

        rowBuds.setOnClickListener(v -> {
            boolean current = choices.getBoolean("mango_buds_enabled", true);
            choices.edit().putBoolean("mango_buds_enabled", !current).apply();
            refreshState();
        });

        rowRemote.setOnClickListener(v -> showQrPairingDialog());

        rowDisplay.setOnClickListener(v -> safeStartActivity(SystemConnectivityHelper.getDisplaySettingsIntents()));
        rowPrivacy.setOnClickListener(v -> {
            choices.edit().putLong("cooldowns_cleared_time", System.currentTimeMillis()).apply();
            Toast.makeText(activity, "Suppression cooldowns reset", Toast.LENGTH_SHORT).show();
        });

        rowDevice.setOnClickListener(v -> safeStartActivity(SystemConnectivityHelper.getDeviceSettingsIntents()));

        qrModal.setOnClickListener(v -> hideQrPairingDialog());
        btnQrDone.setOnClickListener(v -> hideQrPairingDialog());
        btnQrRegenerate.setOnClickListener(v -> generateAndDisplayQrSession());
    }

    private void setupFocusScales() {
        View[] focusableItems = new View[]{
                cardWifi, cardBluetooth,
                rowShare, rowPlay, rowBuds, rowRemote,
                rowDisplay, rowPrivacy, rowDevice,
                btnClose, btnQrRegenerate, btnQrDone
        };

        for (View v : focusableItems) {
            if (v == null) continue;
            v.setOnFocusChangeListener((view, hasFocus) -> {
                float targetScale = 1.0f;
                if (hasFocus) {
                    if (view == btnClose) {
                        targetScale = 1.08f;
                    } else if (view == btnQrRegenerate || view == btnQrDone) {
                        targetScale = 1.04f;
                    } else {
                        targetScale = 1.0f; // Tiles and rows stay strictly within boundary
                    }
                }
                if (targetScale != 1.0f || view.getScaleX() != 1.0f) {
                    view.animate().scaleX(targetScale).scaleY(targetScale).setDuration(160).start();
                }

                if (view == cardWifi) {
                    updateWifiCardAppearance(hasFocus);
                } else if (view == cardBluetooth) {
                    updateBtCardAppearance(hasFocus);
                } else if (view == rowDevice) {
                    updateDeviceRowAppearance(hasFocus);
                }
            });
        }
    }

    public void show() {
        if (isOpen) return;
        isOpen = true;

        refreshState();
        overlayRoot.setVisibility(View.VISIBLE);
        cardWifi.requestFocus();
        updateWifiCardAppearance(true);

        scrim.setAlpha(0f);
        scrim.animate()
                .alpha(1f)
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        float slideDistance = panel.getWidth() > 0 ? panel.getWidth() + 80f : 500f;
        panel.setTranslationX(slideDistance);
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate()
                .translationX(0f)
                .alpha(1f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(280)
                .setInterpolator(new PathInterpolator(0.16f, 1.0f, 0.3f, 1.0f))
                .start();
    }

    public void dismiss() {
        if (!isOpen) return;

        if (qrModal.getVisibility() == View.VISIBLE) {
            hideQrPairingDialog();
            return;
        }

        isOpen = false;

        scrim.animate()
                .alpha(0f)
                .setDuration(220)
                .start();

        float slideDistance = panel.getWidth() > 0 ? panel.getWidth() + 80f : 500f;
        panel.animate()
                .translationX(slideDistance)
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(220)
                .setInterpolator(new PathInterpolator(0.7f, 0.0f, 0.84f, 0.0f))
                .withEndAction(() -> {
                    overlayRoot.setVisibility(View.GONE);
                    if (onDismissListener != null) {
                        onDismissListener.run();
                    }
                })
                .start();
    }

    public void toggle() {
        if (isOpen) {
            dismiss();
        } else {
            show();
        }
    }

    public boolean isOpen() {
        return isOpen;
    }

    public boolean handleBack() {
        if (isOpen) {
            if (qrModal.getVisibility() == View.VISIBLE) {
                hideQrPairingDialog();
            } else {
                dismiss();
            }
            return true;
        }
        return false;
    }

    public void ensureFocus() {
        if (!isOpen) return;
        View focused = activity.getCurrentFocus();
        if (focused == null || !isDescendantOf(focused, overlayRoot)) {
            if (qrModal.getVisibility() == View.VISIBLE) {
                btnQrDone.requestFocus();
            } else {
                cardWifi.requestFocus();
            }
        }
    }

    private static boolean isDescendantOf(View child, View parent) {
        View v = child;
        while (v != null) {
            if (v == parent) return true;
            if (!(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
        return false;
    }

    public void refreshState() {
        // Wi-Fi State
        SystemConnectivityHelper.WifiState wifi = SystemConnectivityHelper.getWifiState(activity);
        textWifiStatus.setText(wifi.isConnected ? "Connected" : "Disconnected");
        textWifiSsid.setText(wifi.ssid != null ? wifi.ssid : "Unavailable");
        updateWifiCardAppearance(cardWifi != null && cardWifi.hasFocus());

        // Bluetooth State
        SystemConnectivityHelper.BtState bt = SystemConnectivityHelper.getBluetoothState(activity);
        textBtStatus.setText(bt.isEnabled ? "On" : "Off");
        int connectedCount = bt.connectedDeviceNames != null ? bt.connectedDeviceNames.size() : 0;
        if (connectedCount > 0) {
            textBtDevices.setText(connectedCount + (connectedCount == 1 ? " device connected" : " devices connected"));
        } else if (bt.pairedCount > 0) {
            textBtDevices.setText(bt.pairedCount + (bt.pairedCount == 1 ? " paired device" : " paired devices"));
        } else {
            textBtDevices.setText("0 devices connected");
        }
        updateBtCardAppearance(cardBluetooth != null && cardBluetooth.hasFocus());
        updateDeviceRowAppearance(rowDevice != null && rowDevice.hasFocus());

        // Mango Feature switches
        boolean shareOn = choices.getBoolean("mango_share_enabled", true);
        boolean playOn = choices.getBoolean("mango_play_enabled", true);
        boolean budsOn = choices.getBoolean("mango_buds_enabled", true);

        switchShare.setChecked(shareOn);
        switchPlay.setChecked(playOn);
        switchBuds.setChecked(budsOn);

        int pairedCount = pairingManager.getPairedDeviceCount();
        textRemoteSub.setText(pairedCount > 0 ? (pairedCount + " devices paired") : "Pair your phone");
    }

    private void updateWifiCardAppearance(boolean hasFocus) {
        SystemConnectivityHelper.WifiState wifi = SystemConnectivityHelper.getWifiState(activity);
        if (hasFocus) {
            if (iconWifi != null) iconWifi.setColorFilter(0xFF000000, PorterDuff.Mode.SRC_IN);
            if (chevronWifi != null) chevronWifi.setColorFilter(0x80000000, PorterDuff.Mode.SRC_IN);
            if (textWifiTitle != null) textWifiTitle.setTextColor(0xFF000000);
            if (textWifiStatus != null) {
                textWifiStatus.setTextColor(wifi.isConnected ? 0xFF1C7C33 : 0xCC000000);
            }
            if (textWifiSsid != null) textWifiSsid.setTextColor(0x99000000);
        } else {
            if (iconWifi != null) iconWifi.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
            if (chevronWifi != null) chevronWifi.setColorFilter(0x80FFFFFF, PorterDuff.Mode.SRC_IN);
            if (textWifiTitle != null) textWifiTitle.setTextColor(0xFFFFFFFF);
            if (textWifiStatus != null) {
                textWifiStatus.setTextColor(wifi.isConnected ? 0xFF34C759 : 0xE6FFFFFF);
            }
            if (textWifiSsid != null) textWifiSsid.setTextColor(0xB3FFFFFF);
        }
    }

    private void updateBtCardAppearance(boolean hasFocus) {
        SystemConnectivityHelper.BtState bt = SystemConnectivityHelper.getBluetoothState(activity);
        if (hasFocus) {
            if (iconBluetooth != null) iconBluetooth.setColorFilter(0xFF000000, PorterDuff.Mode.SRC_IN);
            if (chevronBluetooth != null) chevronBluetooth.setColorFilter(0x80000000, PorterDuff.Mode.SRC_IN);
            if (textBtTitle != null) textBtTitle.setTextColor(0xFF000000);
            if (textBtStatus != null) {
                textBtStatus.setTextColor(bt.isEnabled ? 0xFF1C7C33 : 0xCC000000);
            }
            if (textBtDevices != null) textBtDevices.setTextColor(0x99000000);
        } else {
            if (iconBluetooth != null) iconBluetooth.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
            if (chevronBluetooth != null) chevronBluetooth.setColorFilter(0x80FFFFFF, PorterDuff.Mode.SRC_IN);
            if (textBtTitle != null) textBtTitle.setTextColor(0xFFFFFFFF);
            if (textBtStatus != null) {
                textBtStatus.setTextColor(bt.isEnabled ? 0xFF34C759 : 0xE6FFFFFF);
            }
            if (textBtDevices != null) textBtDevices.setTextColor(0xB3FFFFFF);
        }
    }

    private void updateDeviceRowAppearance(boolean hasFocus) {
        if (hasFocus) {
            if (iconDevice != null) iconDevice.setColorFilter(0xFF000000, PorterDuff.Mode.SRC_IN);
            if (chevronDevice != null) chevronDevice.setColorFilter(0x80000000, PorterDuff.Mode.SRC_IN);
            if (textDeviceTitle != null) textDeviceTitle.setTextColor(0xFF000000);
        } else {
            if (iconDevice != null) iconDevice.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
            if (chevronDevice != null) chevronDevice.setColorFilter(0x80FFFFFF, PorterDuff.Mode.SRC_IN);
            if (textDeviceTitle != null) textDeviceTitle.setTextColor(0xFFFFFFFF);
        }
    }

    private void showQrPairingDialog() {
        qrModal.setVisibility(View.VISIBLE);
        qrModal.setAlpha(0f);
        qrModal.animate().alpha(1f).setDuration(200).start();
        generateAndDisplayQrSession();
        btnQrDone.requestFocus();
    }

    private void hideQrPairingDialog() {
        if (qrModal.getVisibility() != View.VISIBLE) return;
        qrModal.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            qrModal.setVisibility(View.GONE);
            rowRemote.requestFocus();
        }).start();
    }

    private void generateAndDisplayQrSession() {
        String pin = pairingManager.startPairingSession();
        String localIp = SystemConnectivityHelper.getLocalIpAddress();
        String pairingUrl = "http://" + localIp + ":" + MangoRemoteProtocol.DEFAULT_PORT + "/?pin=" + pin;

        if (pin != null && pin.length() == 4) {
            qrPinText.setText(pin.charAt(0) + " " + pin.charAt(1) + " " + pin.charAt(2) + " " + pin.charAt(3));
        } else {
            qrPinText.setText(pin);
        }

        qrUrlText.setText("http://" + localIp + ":" + MangoRemoteProtocol.DEFAULT_PORT);

        new Thread(() -> {
            try {
                Bitmap qrBitmap = QrCodeGenerator.generateQrBitmap(pairingUrl, 360, 360);
                handler.post(() -> qrImageView.setImageBitmap(qrBitmap));
            } catch (Throwable ignored) {}
        }).start();
    }

    private void safeStartActivity(Intent... intents) {
        for (Intent intent : intents) {
            if (intent == null) continue;
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        Toast.makeText(activity, "Settings panel unavailable on this TV device", Toast.LENGTH_SHORT).show();
    }
}

package com.gathin.tvhome.connectivity;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.gathin.tvhome.R;
import com.gathin.tvhome.bluetooth.AirPodsDevice;
import com.gathin.tvhome.share.MangoShareServer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central Notification & Connectivity Coordinator for Mango TV.
 *
 * Implements strict priority arbitration, debouncing, cooldown tracking,
 * D-pad focus-trapping, and Apple tvOS-style translucent pop-up presentation
 * across Mango Buds, Mango Share, and Mango Play.
 */
public class MangoConnectivityCoordinator {
    private static final String TAG = "MangoConnectivity";

    // Priority Levels
    public static final int PRIORITY_NONE = 0;
    public static final int PRIORITY_STATUS_PILL = 1;
    public static final int PRIORITY_BUDS = 2;
    public static final int PRIORITY_PLAY = 3;
    public static final int PRIORITY_SHARE_PROMPT = 4;
    public static final int PRIORITY_REMOTE_PAIRING = 4;
    public static final int PRIORITY_TRANSFER_ACTIVE = 5;

    private static final long PROMPT_TIMEOUT_MS = 30000L;
    private static final long COOLDOWN_DURATION_MS = 300000L; // 5 minutes cooldown
    private static final long STATUS_PILL_TIMEOUT_MS = 6000L;

    public interface Listener {
        void onConnectBuds(AirPodsDevice device);
        void onDeclineBuds(AirPodsDevice device);
        void onAcceptShare(MangoShareServer.TransferSession session);
        void onDeclineShare(MangoShareServer.TransferSession session);
        void onCancelShare(MangoShareServer.TransferSession session);
        void onPlayHandoffConfirmed(PlayHandoffRequest request);
        void onPlayHandoffDismissed(PlayHandoffRequest request);
        void playNavSound();
    }

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;

    // View References
    private View overlayRoot;
    private View scrim;
    private View card;

    // Header Views
    private ImageView badgeIcon;
    private TextView titleView;
    private TextView subtitleView;

    // Panel 1: Buds Views
    private View panelBuds;
    private ImageView budsCaseImage;
    private View budsBatteryRow;
    private TextView budsBatteryLeftText;
    private TextView budsBatteryRightText;
    private TextView budsBatteryCaseText;
    private TextView budsBatteryUnavailableText;

    // Panel 2: Share Views
    private View panelShare;
    private ImageView shareThumbnail;
    private TextView shareFileName;
    private TextView shareFileMeta;
    private View shareProgressSection;
    private ProgressBar shareProgressBarReal;
    private TextView shareProgressStats;

    // Panel 3: Play Views
    private View panelPlay;
    private ImageView playThumbnail;
    private TextView playMediaTitle;
    private TextView playMediaMeta;
    private ProgressBar playProgressBar;
    private TextView playTimeElapsed;
    private TextView playTimeTotal;

    // Panel 4: Remote Pairing Views
    private View panelRemote;
    private TextView remotePinText;
    private TextView remoteUrlText;

    // Buttons
    private View buttonsRow;
    private TextView btnPrimary;
    private TextView btnSecondary;

    // Top-right Status Pill
    private View statusPill;
    private ImageView pillIcon;
    private TextView pillTitle;
    private TextView pillSubtitle;

    // Active State
    private int currentPriority = PRIORITY_NONE;
    private AirPodsDevice activeBudsDevice;
    private MangoShareServer.TransferSession activeShareSession;
    private PlayHandoffRequest activePlayRequest;

    private View preOverlayFocusedView;
    private Runnable autoDismissRunnable;
    private Runnable pillDismissRunnable;

    // Cooldown Cache: Identifier -> Timestamp until when it is suppressed
    private final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();

    public MangoConnectivityCoordinator(Activity activity, View overlayRoot, View statusPill) {
        this.activity = activity;
        this.overlayRoot = overlayRoot;
        this.statusPill = statusPill;
        bindViews();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private void bindViews() {
        if (overlayRoot != null) {
            scrim = overlayRoot.findViewById(R.id.connectivityScrim);
            card = overlayRoot.findViewById(R.id.connectivityCard);

            badgeIcon = overlayRoot.findViewById(R.id.connectivityBadgeIcon);
            titleView = overlayRoot.findViewById(R.id.connectivityTitle);
            subtitleView = overlayRoot.findViewById(R.id.connectivitySubtitle);

            panelBuds = overlayRoot.findViewById(R.id.panelBuds);
            budsCaseImage = overlayRoot.findViewById(R.id.budsCaseImage);
            budsBatteryRow = overlayRoot.findViewById(R.id.budsBatteryRow);
            budsBatteryLeftText = overlayRoot.findViewById(R.id.budsBatteryLeftText);
            budsBatteryRightText = overlayRoot.findViewById(R.id.budsBatteryRightText);
            budsBatteryCaseText = overlayRoot.findViewById(R.id.budsBatteryCaseText);
            budsBatteryUnavailableText = overlayRoot.findViewById(R.id.budsBatteryUnavailableText);

            panelShare = overlayRoot.findViewById(R.id.panelShare);
            shareThumbnail = overlayRoot.findViewById(R.id.shareFileThumbnail);
            shareFileName = overlayRoot.findViewById(R.id.shareFileName);
            shareFileMeta = overlayRoot.findViewById(R.id.shareFileMeta);
            shareProgressSection = overlayRoot.findViewById(R.id.shareProgressSection);
            shareProgressBarReal = overlayRoot.findViewById(R.id.shareProgressBarReal);
            shareProgressStats = overlayRoot.findViewById(R.id.shareProgressStats);

            panelPlay = overlayRoot.findViewById(R.id.panelPlay);
            playThumbnail = overlayRoot.findViewById(R.id.playMediaThumbnail);
            playMediaTitle = overlayRoot.findViewById(R.id.playMediaTitle);
            playMediaMeta = overlayRoot.findViewById(R.id.playMediaMeta);
            playProgressBar = overlayRoot.findViewById(R.id.playProgressBar);
            playTimeElapsed = overlayRoot.findViewById(R.id.playTimeElapsed);
            playTimeTotal = overlayRoot.findViewById(R.id.playTimeTotal);

            // Panel 4: Remote
            panelRemote = overlayRoot.findViewById(R.id.panelRemote);
            remotePinText = overlayRoot.findViewById(R.id.remotePinText);
            remoteUrlText = overlayRoot.findViewById(R.id.remoteUrlText);

            buttonsRow = overlayRoot.findViewById(R.id.connectivityButtonsRow);
            btnPrimary = overlayRoot.findViewById(R.id.btnConnectivityPrimary);
            btnSecondary = overlayRoot.findViewById(R.id.btnConnectivitySecondary);

            setupButtonListeners();
        }

        if (statusPill != null) {
            pillIcon = statusPill.findViewById(R.id.pillIcon);
            pillTitle = statusPill.findViewById(R.id.pillTitle);
            pillSubtitle = statusPill.findViewById(R.id.pillSubtitle);

            statusPill.setOnClickListener(v -> {
                dismissStatusPill();
                if (activeBudsDevice != null) {
                    showBudsPrompt(activeBudsDevice);
                }
            });
        }
    }

    private void setupButtonListeners() {
        if (btnPrimary != null) {
            btnPrimary.setOnClickListener(v -> handlePrimaryAction());
            btnPrimary.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && listener != null) listener.playNavSound();
            });
        }
        if (btnSecondary != null) {
            btnSecondary.setOnClickListener(v -> handleSecondaryAction());
            btnSecondary.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && listener != null) listener.playNavSound();
            });
        }
    }

    // ---- Mango Buds Flow -------------------------------------------------------------------------

    public boolean showBudsPrompt(AirPodsDevice device) {
        if (device == null || isCoolingDown("buds:" + device.address)) {
            return false;
        }
        // Do not interrupt active transfer or equal/higher priority
        if (currentPriority >= PRIORITY_BUDS && currentPriority != PRIORITY_STATUS_PILL) {
            return false;
        }

        activeBudsDevice = device;
        currentPriority = PRIORITY_BUDS;
        prepareOverlayPresentation();

        // Header: Orange badge with Mango Buds
        if (badgeIcon != null) {
            badgeIcon.setImageResource(R.drawable.ic_airpods);
            badgeIcon.setBackgroundResource(R.drawable.bg_badge_orange);
        }
        if (titleView != null) titleView.setText("Mango Buds");
        if (subtitleView != null) {
            subtitleView.setText(device.modelName + " detected nearby");
        }

        // Show Panel 1: Buds
        showOnlyPanel(panelBuds);

        // Battery logic: only show when genuinely available
        boolean hasLeft = device.leftBattery >= 0;
        boolean hasRight = device.rightBattery >= 0;
        boolean hasCase = device.caseBattery >= 0;

        if (budsBatteryRow != null && budsBatteryUnavailableText != null) {
            if (hasLeft || hasRight || hasCase) {
                budsBatteryRow.setVisibility(View.VISIBLE);
                budsBatteryUnavailableText.setVisibility(View.GONE);

                if (budsBatteryLeftText != null) {
                    budsBatteryLeftText.setText(device.formatBattery(device.leftBattery) + " Left");
                }
                if (budsBatteryRightText != null) {
                    budsBatteryRightText.setText(device.formatBattery(device.rightBattery) + " Right");
                }
                if (budsBatteryCaseText != null) {
                    budsBatteryCaseText.setText(device.formatBattery(device.caseBattery) + " Case");
                }
            } else {
                budsBatteryRow.setVisibility(View.GONE);
                budsBatteryUnavailableText.setVisibility(View.VISIBLE);
            }
        }

        // Configure Buttons: Connect / Decline
        if (buttonsRow != null) buttonsRow.setVisibility(View.VISIBLE);
        if (btnPrimary != null) {
            btnPrimary.setText("Connect");
            btnPrimary.setVisibility(View.VISIBLE);
        }
        if (btnSecondary != null) {
            btnSecondary.setText("Decline");
            btnSecondary.setVisibility(View.VISIBLE);
        }

        animateInCard();
        return true;
    }

    public void updateBudsStatus(AirPodsDevice device) {
        if (currentPriority == PRIORITY_BUDS && activeBudsDevice != null &&
                activeBudsDevice.address.equals(device.address)) {
            activeBudsDevice = device;
            if (device.leftBattery >= 0 && budsBatteryLeftText != null) {
                budsBatteryLeftText.setText(device.formatBattery(device.leftBattery) + " Left");
            }
            if (device.rightBattery >= 0 && budsBatteryRightText != null) {
                budsBatteryRightText.setText(device.formatBattery(device.rightBattery) + " Right");
            }
            if (device.caseBattery >= 0 && budsBatteryCaseText != null) {
                budsBatteryCaseText.setText(device.formatBattery(device.caseBattery) + " Case");
            }
        }
    }

    // ---- Mango Share Flow ------------------------------------------------------------------------

    public boolean showSharePrompt(MangoShareServer.TransferSession session) {
        if (session == null || isCoolingDown("share:" + session.senderAlias)) {
            return false;
        }
        if (currentPriority == PRIORITY_TRANSFER_ACTIVE) {
            session.decline();
            return false;
        }

        activeShareSession = session;
        currentPriority = PRIORITY_SHARE_PROMPT;
        prepareOverlayPresentation();

        // Header: Blue badge with Mango Share
        if (badgeIcon != null) {
            badgeIcon.setImageResource(R.drawable.ic_mango_share);
            badgeIcon.setBackgroundResource(R.drawable.bg_badge_blue);
        }
        if (titleView != null) titleView.setText("Mango Share");
        if (subtitleView != null) {
            subtitleView.setText(session.senderAlias + " wants to send a file");
        }

        // Show Panel 2: Share
        showOnlyPanel(panelShare);
        if (shareProgressSection != null) shareProgressSection.setVisibility(View.GONE);

        // File Details
        if (!session.files.isEmpty()) {
            MangoShareServer.FileItem firstFile = session.files.get(0);
            if (shareFileName != null) shareFileName.setText(firstFile.fileName);
            if (shareFileMeta != null) {
                String meta = formatFileSize(firstFile.size);
                if (session.files.size() > 1) {
                    meta += " • " + session.files.size() + " files";
                }
                shareFileMeta.setText(meta);
            }
        }

        // Buttons: Accept / Decline
        if (buttonsRow != null) buttonsRow.setVisibility(View.VISIBLE);
        if (btnPrimary != null) {
            btnPrimary.setText("Accept");
            btnPrimary.setVisibility(View.VISIBLE);
        }
        if (btnSecondary != null) {
            btnSecondary.setText("Decline");
            btnSecondary.setVisibility(View.VISIBLE);
        }

        animateInCard();
        return true;
    }

    public void updateShareProgress(int percent, long currentBytes, long totalBytes) {
        if (currentPriority == PRIORITY_SHARE_PROMPT || currentPriority == PRIORITY_TRANSFER_ACTIVE) {
            currentPriority = PRIORITY_TRANSFER_ACTIVE;
            cancelAutoDismiss(); // Do not timeout during active bytes transfer

            if (shareProgressSection != null && shareProgressSection.getVisibility() != View.VISIBLE) {
                shareProgressSection.setVisibility(View.VISIBLE);
            }
            if (shareProgressBarReal != null) {
                shareProgressBarReal.setProgress(percent);
            }
            if (shareProgressStats != null) {
                String text = "Receiving: " + formatFileSize(currentBytes) + " / " +
                        formatFileSize(totalBytes) + " (" + percent + "%)";
                shareProgressStats.setText(text);
            }
            // Switch button to Cancel only
            if (btnPrimary != null) btnPrimary.setVisibility(View.GONE);
            if (btnSecondary != null) {
                btnSecondary.setText("Cancel");
                btnSecondary.setVisibility(View.VISIBLE);
            }
        }
    }

    public void onShareComplete() {
        if (subtitleView != null) subtitleView.setText("Transfer complete");
        if (shareProgressStats != null) shareProgressStats.setText("Saved to Downloads/MangoShare");
        handler.postDelayed(this::dismissDialog, 2200L);
    }

    // ---- Mango Play Flow -------------------------------------------------------------------------

    public boolean showPlayHandoffPrompt(PlayHandoffRequest request) {
        if (request == null || isCoolingDown("play:" + request.id)) {
            return false;
        }
        if (currentPriority >= PRIORITY_PLAY && currentPriority != PRIORITY_STATUS_PILL) {
            return false;
        }

        activePlayRequest = request;
        currentPriority = PRIORITY_PLAY;
        prepareOverlayPresentation();

        // Header: Orange badge with Mango Play
        if (badgeIcon != null) {
            badgeIcon.setImageResource(R.drawable.ic_mango_play);
            badgeIcon.setBackgroundResource(R.drawable.bg_badge_orange);
        }
        if (titleView != null) titleView.setText("Mango Play");
        if (subtitleView != null) {
            subtitleView.setText("Continue on Mango TV");
        }

        // Show Panel 3: Play
        showOnlyPanel(panelPlay);

        if (playMediaTitle != null) playMediaTitle.setText(request.title);
        if (playMediaMeta != null) playMediaMeta.setText(request.metadata);

        if (playProgressBar != null && request.durationMs > 0) {
            int progress = (int) ((request.positionMs * 1000) / request.durationMs);
            playProgressBar.setProgress(progress);
        }
        if (playTimeElapsed != null) playTimeElapsed.setText(PlayHandoffRequest.formatDuration(request.positionMs));
        if (playTimeTotal != null) playTimeTotal.setText(PlayHandoffRequest.formatDuration(request.durationMs));

        if (request.thumbnail != null && playThumbnail != null) {
            playThumbnail.setImageBitmap(request.thumbnail);
        }

        // Buttons: Play now / Not now
        if (buttonsRow != null) buttonsRow.setVisibility(View.VISIBLE);
        if (btnPrimary != null) {
            btnPrimary.setText("Play now");
            btnPrimary.setVisibility(View.VISIBLE);
        }
        if (btnSecondary != null) {
            btnSecondary.setText("Not now");
            btnSecondary.setVisibility(View.VISIBLE);
        }

        animateInCard();
        return true;
    }

    // ---- Top-Right Compact Status Pill -----------------------------------------------------------

    public void showStatusPill(String title, String subtitle, int iconRes) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            handler.post(() -> showStatusPill(title, subtitle, iconRes));
            return;
        }
        if (isDialogVisible()) return; // Never show status pill when full dialog is active
        if (statusPill == null) return;

        if (pillTitle != null) pillTitle.setText(title);
        if (pillSubtitle != null) pillSubtitle.setText(subtitle);
        if (pillIcon != null && iconRes != 0) pillIcon.setImageResource(iconRes);

        currentPriority = PRIORITY_STATUS_PILL;
        statusPill.setVisibility(View.VISIBLE);
        statusPill.setAlpha(0f);
        statusPill.setTranslationY(-20f);
        statusPill.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();

        if (pillDismissRunnable != null) handler.removeCallbacks(pillDismissRunnable);
        pillDismissRunnable = this::dismissStatusPill;
        handler.postDelayed(pillDismissRunnable, STATUS_PILL_TIMEOUT_MS);
    }

    public void dismissStatusPill() {
        if (statusPill == null || statusPill.getVisibility() != View.VISIBLE) return;
        if (pillDismissRunnable != null) {
            handler.removeCallbacks(pillDismissRunnable);
            pillDismissRunnable = null;
        }

        statusPill.animate()
                .alpha(0f)
                .translationY(-16f)
                .setDuration(200)
                .setInterpolator(new AccelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    statusPill.setVisibility(View.GONE);
                    if (currentPriority == PRIORITY_STATUS_PILL) {
                        currentPriority = PRIORITY_NONE;
                    }
                })
                .start();
    }

    // ---- Action Handlers -------------------------------------------------------------------------

    private void handlePrimaryAction() {
        if (currentPriority == PRIORITY_BUDS && activeBudsDevice != null) {
            if (btnPrimary != null) btnPrimary.setText("Connecting...");
            if (listener != null) listener.onConnectBuds(activeBudsDevice);
            handler.postDelayed(this::dismissDialog, 1500L);
        } else if (currentPriority == PRIORITY_SHARE_PROMPT && activeShareSession != null) {
            if (listener != null) listener.onAcceptShare(activeShareSession);
        } else if (currentPriority == PRIORITY_PLAY && activePlayRequest != null) {
            PlayHandoffRequest req = activePlayRequest;
            dismissDialog();
            if (listener != null) listener.onPlayHandoffConfirmed(req);
        }
    }

    private void handleSecondaryAction() {
        if (currentPriority == PRIORITY_BUDS && activeBudsDevice != null) {
            addCooldown("buds:" + activeBudsDevice.address);
            if (listener != null) listener.onDeclineBuds(activeBudsDevice);
            dismissDialog();
        } else if (currentPriority == PRIORITY_SHARE_PROMPT && activeShareSession != null) {
            addCooldown("share:" + activeShareSession.senderAlias);
            if (listener != null) listener.onDeclineShare(activeShareSession);
            dismissDialog();
        } else if (currentPriority == PRIORITY_TRANSFER_ACTIVE && activeShareSession != null) {
            if (listener != null) listener.onCancelShare(activeShareSession);
            dismissDialog();
        } else if (currentPriority == PRIORITY_PLAY && activePlayRequest != null) {
            addCooldown("play:" + activePlayRequest.id);
            if (listener != null) listener.onPlayHandoffDismissed(activePlayRequest);
            dismissDialog();
        } else {
            dismissDialog();
        }
    }

    public boolean handleBackPressed() {
        if (isDialogVisible()) {
            handleSecondaryAction();
            return true;
        }
        if (statusPill != null && statusPill.getVisibility() == View.VISIBLE) {
            dismissStatusPill();
            return true;
        }
        return false;
    }

    public boolean isDialogVisible() {
        return overlayRoot != null && overlayRoot.getVisibility() == View.VISIBLE;
    }

    // ---- Presentation & Animations ---------------------------------------------------------------

    private void prepareOverlayPresentation() {
        dismissStatusPill();
        cancelAutoDismiss();

        // Capture previous focus before showing overlay
        preOverlayFocusedView = activity.getCurrentFocus();

        if (overlayRoot != null) {
            overlayRoot.setVisibility(View.VISIBLE);
            if (scrim != null) {
                scrim.setAlpha(0f);
                scrim.animate().alpha(1f).setDuration(220).start();
            }
        }
    }

    private void animateInCard() {
        if (card != null) {
            card.setAlpha(0f);
            card.setScaleX(0.95f);
            card.setScaleY(0.95f);
            card.setTranslationY(-24f);
            card.animate()
                    .alpha(1f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .withEndAction(() -> {
                        if (btnPrimary != null && btnPrimary.isAttachedToWindow()) {
                            btnPrimary.requestFocus();
                        }
                    })
                    .start();
        }

        // Schedule safe auto-dismiss timeout (30s)
        autoDismissRunnable = () -> {
            Log.d(TAG, "Prompt timed out after 30s. Declining safely.");
            handleSecondaryAction();
        };
        handler.postDelayed(autoDismissRunnable, PROMPT_TIMEOUT_MS);
    }

    public void dismissDialog() {
        if (!isDialogVisible()) return;
        cancelAutoDismiss();

        if (scrim != null) {
            scrim.animate().alpha(0f).setDuration(180).start();
        }
        if (card != null) {
            card.animate()
                    .alpha(0f)
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .translationY(16f)
                    .setDuration(180)
                    .setInterpolator(new AccelerateInterpolator(1.4f))
                    .withEndAction(() -> {
                        if (overlayRoot != null) overlayRoot.setVisibility(View.GONE);
                        currentPriority = PRIORITY_NONE;
                        activeBudsDevice = null;
                        activeShareSession = null;
                        activePlayRequest = null;

                        // Restore previous screen focus
                        if (preOverlayFocusedView != null && preOverlayFocusedView.isAttachedToWindow()) {
                            preOverlayFocusedView.requestFocus();
                        }
                        preOverlayFocusedView = null;
                    })
                    .start();
        }
    }

    public void showRemotePairingPrompt(String pin, String connectUrl, Runnable onDismiss) {
        if (overlayRoot == null || panelRemote == null) return;
        if (currentPriority > PRIORITY_REMOTE_PAIRING) return;

        currentPriority = PRIORITY_REMOTE_PAIRING;
        prepareOverlayPresentation();

        if (badgeIcon != null) {
            badgeIcon.setImageResource(R.drawable.ic_airplay);
            badgeIcon.setBackgroundResource(R.drawable.bg_badge_orange);
        }
        if (titleView != null) titleView.setText("Mango Remote");
        if (subtitleView != null) subtitleView.setText("Pair companion web remote");

        showOnlyPanel(panelRemote);

        if (remotePinText != null && pin != null) {
            StringBuilder formatted = new StringBuilder();
            for (int i = 0; i < pin.length(); i++) {
                if (i > 0) formatted.append(" ");
                formatted.append(pin.charAt(i));
            }
            remotePinText.setText(formatted.toString());
        }
        if (remoteUrlText != null) {
            remoteUrlText.setText(connectUrl);
        }

        if (btnPrimary != null) {
            btnPrimary.setText("Done");
            btnPrimary.setVisibility(View.VISIBLE);
            btnPrimary.setOnClickListener(v -> {
                dismissDialog();
                if (onDismiss != null) onDismiss.run();
            });
            btnPrimary.requestFocus();
        }
        if (btnSecondary != null) {
            btnSecondary.setVisibility(View.GONE);
        }

        animateInCard();
    }

    private void showOnlyPanel(View target) {
        if (panelBuds != null) panelBuds.setVisibility(target == panelBuds ? View.VISIBLE : View.GONE);
        if (panelShare != null) panelShare.setVisibility(target == panelShare ? View.VISIBLE : View.GONE);
        if (panelPlay != null) panelPlay.setVisibility(target == panelPlay ? View.VISIBLE : View.GONE);
        if (panelRemote != null) panelRemote.setVisibility(target == panelRemote ? View.VISIBLE : View.GONE);
        if (btnSecondary != null && target != panelRemote) btnSecondary.setVisibility(View.VISIBLE);
    }

    private void cancelAutoDismiss() {
        if (autoDismissRunnable != null) {
            handler.removeCallbacks(autoDismissRunnable);
            autoDismissRunnable = null;
        }
    }

    // ---- Cooldown Cache --------------------------------------------------------------------------

    private void addCooldown(String key) {
        cooldownMap.put(key, System.currentTimeMillis() + COOLDOWN_DURATION_MS);
    }

    private boolean isCoolingDown(String key) {
        Long expireTime = cooldownMap.get(key);
        if (expireTime == null) return false;
        if (System.currentTimeMillis() > expireTime) {
            cooldownMap.remove(key);
            return false;
        }
        return true;
    }

    public void clearCooldowns() {
        cooldownMap.clear();
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}

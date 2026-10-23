package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import com.gathin.tvhome.bluetooth.AirPodsDetector;
import com.gathin.tvhome.bluetooth.AirPodsDevice;
import com.gathin.tvhome.share.MangoShareServer;
import com.gathin.tvhome.airplay.MangoAirPlayManager;
import com.gathin.tvhome.connectivity.MangoConnectivityCoordinator;
import com.gathin.tvhome.connectivity.PlayHandoffRequest;
import com.gathin.tvhome.remote.MangoRemoteKeyInjector;
import com.gathin.tvhome.remote.MangoRemotePairingManager;
import com.gathin.tvhome.remote.MangoRemoteProtocol;
import com.gathin.tvhome.remote.MangoRemoteServer;
import org.json.JSONObject;
import android.widget.ProgressBar;
import java.lang.reflect.Method;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.util.Log;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.SoundEffectConstants;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private static final long STATS_MS = 10_000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private RecyclerView watchNextList;
    private WatchNextAdapter watchNextAdapter;
    private List<WatchNextItem> watchNextItems;
    private int lastWatchNextFocused = 0;
    private View focusedWatchNextCard;
    private ContentObserver watchNextObserver;
    private final ExecutorService watchNextExecutor = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private WallpaperPlayer wallpaperPlayer;
    private View bottomVignette;
    private GlassView glass;
    private ImageView homeWeatherIcon;
    private TextView homeWeatherTemp;
    private final WeatherManager.WeatherListener homeWeatherListener = this::updateHomeWeather;

    // Mango TV Unified Connectivity Coordinator
    private MangoConnectivityCoordinator connectivityCoordinator;
    private long lastCooldownsClearedTime = 0L;

    // Mango Remote Server & Pairing
    private MangoRemoteServer mangoRemoteServer;
    private MangoRemotePairingManager remotePairingManager;
    private MangoRemoteKeyInjector remoteKeyInjector;

    // AirPods detection and Apple TV-grade HUD
    private AirPodsDetector airPodsDetector;
    private AirPodsDevice currentAirPodsDevice;
    private View airPodsHud;
    private ImageView airPodsIcon;
    private TextView airPodsTitle;
    private TextView airPodsStatus;
    private TextView airPodsLeftBattery;
    private ImageView airPodsLeftBolt;
    private TextView airPodsCaseBattery;
    private ImageView airPodsCaseBolt;
    private TextView airPodsRightBattery;
    private ImageView airPodsRightBolt;
    private TextView airPodsBtnConnect;
    private TextView airPodsBtnAudio;
    private TextView airPodsBtnDismiss;
    private View focusedAirPodsButton;
    private View preAirPodsFocusedView;
    private Runnable airPodsAutoDismissRunnable;

    // Mango Share
    private MangoShareServer mangoShareServer;
    private MangoShareServer.TransferSession currentShareSession;
    private View shareHud;
    private TextView shareHudTitle;
    private TextView shareHudStatus;
    private TextView shareSenderDetails;
    private ProgressBar shareProgressBar;
    private View shareButtonsRow;
    private TextView btnShareAccept;
    private TextView btnShareDecline;
    private View focusedShareButton;
    private View preShareFocusedView;
    private Runnable shareAutoDismissRunnable;

    // Mango AirPlay
    private MangoAirPlayManager mangoAirPlayManager;
    private View airPlayHud;
    private TextView airPlayHudTitle;
    private TextView airPlayHudStatus;
    private ImageView airPlayPhotoView;
    private Runnable airPlayAutoDismissRunnable;

    private int heldNavKeyCode = 0;
    private long navHoldStartTime = 0L;
    private int navHoldStep = 0;
    private boolean isNavHolding = false;
    private boolean isFastNavigating = false;
    private boolean isSyntheticNav = false;

    private View ambient;
    private TextView heroName;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View btnInputs;
    private View btnSettings;
    private TextView btnWifi;
    private TextView shamsiDate;
    private TextView stats;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    private boolean shamsi;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    private boolean settingsKeyPanel;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** config.xml dock_row: the first row is a separate dock (band, parked low, top shelf) rather than plain grid. */
    private boolean dockRow;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    private boolean festive;
    private DiyaDrawable diya;
    private BellDrawable bell;
    private MarigoldDrawable marigold;
    private int greetingColor;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Rebuild the tiles on resume: Home was fully hidden by an app, or Home was pressed. Not after a side panel such as Wi-Fi settings. */
    private boolean rebuildOnResume = true;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    /** Reorder mode is on (top-bar button). */
    private boolean reordering;
    /** Key of the tile being carried in reorder mode, or null. */
    private String movingKey;
    /** Tile order when reorder mode started, to save only when something moved. */
    private List<String> orderAtStart;
    private View btnReorder;
    /** dock_apps as loaded (config.xml or a theme): a saved Order only applies while these are unchanged. */
    private List<String> dockApps;
    /** fill_dock false: the dock can have empty slots, which reorder mode leaves out and restores afterwards. */
    private boolean fillDockLeavesSlots;
    /** Edit-dock mode (Settings menu): a reorder session that swaps one chosen app into one dock place. */
    private boolean dockEditing;
    /** Dock place being changed in edit-dock mode, or -1 while the place is still being chosen. */
    private int dockEditSlot = -1;
    private AppLibraryDialog appLibrary;
    private boolean libraryFocusPending;
    private RecyclerView.OnScrollListener pendingFocusScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            reloadApps(false);
            if (appLibrary != null) appLibrary.refresh();
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            sky.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        COLUMNS = cfg.columns;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        dockRow = cfg.dockRow;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        root.setSoundEffectsEnabled(false);

        try {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(10)
                    .setAudioAttributes(audioAttributes)
                    .build();
            clickSoundId = soundPool.load(this, R.raw.apple_click, 1);
            soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
                if (status == 0) soundLoaded = true;
            });
        } catch (Exception ignored) {}
        clockContainer = findViewById(R.id.clockContainer);
        if (clockContainer != null) {
            clockContainer.setPivotX(0f);
            clockContainer.setPivotY(0f);
        }
        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        bottomVignette = findViewById(R.id.bottomVignette);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        if (festive) glass.setAccent(festival.accentColor());
        homeWeatherIcon = findViewById(R.id.homeWeatherIcon);
        homeWeatherTemp = findViewById(R.id.homeWeatherTemp);
        heroName = findViewById(R.id.heroName);
        // dock_title false: the name above the dock never shows; the glow still follows the dock.
        if (!cfg.dockTitle) heroName.setVisibility(View.GONE);
        greeting = findViewById(R.id.greeting);
        greetingColor = greeting.getCurrentTextColor();
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        stats = findViewById(R.id.stats);
        showStats = cfg.showStats;
        shamsi = cfg.shamsiDate;
        greetingName = cfg.greetingName;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }

            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll just enough to show the rect between the grid's top edge and its
             * bottom; dock tiles are left to onTileFocused, which parks the dock row on the padding line.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                // Handled cleanly and deterministically via scrollGridToRow
                return true;
            }
        });
        ((GridLayoutManager) grid.getLayoutManager()).setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return adapter != null && adapter.isLibraryAt(position) ? COLUMNS : 1; }
        });
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.setItemViewCacheSize(12);
        grid.setSoundEffectsEnabled(false);
        // Tiles that scroll out while one is carried come back from the view cache without a rebind; set their
        // dimming again whenever one attaches, so none stays dim after the drop.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        // DockDecoration omitted for seamless uniform Apple-style grid layout across all rows
        if (animations) {
            int enterAnim = reducedMotion ? R.anim.tile_enter_reduced : R.anim.tile_enter;
            float enterDelay = reducedMotion ? 0f : 0.06f;
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, enterAnim), enterDelay));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        dockApps = cfg.dockApps;
        fillDockLeavesSlots = !cfg.fillDock;
        adapter.setSavedOrder(Order.ranks(Order.load(Order.file(this)), dockApps));
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the columns and the rows that must fit (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            int tileW = AppAdapter.tileWidthPx(inner, h, COLUMNS, rowsBelowDock, cfg.dockTitle, cfg.dockRow, density);
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), density);
            int dockRow = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * density);   // item height plus DockDecoration offsets
            int gridRows = itemH * rowsBelowDock;
            // Keep the hero below the header when the library is enabled on a short screen,
            // even when the theme requests an extra visible row below the dock.
            int shelfClearance = cfg.appLibrary ? heroName.getMeasuredHeight() + (int) (40 * density) : 0;
            int want = Math.max(shelfClearance, h - dockRow - gridRows - (int) (AppAdapter.BOTTOM_ROOM_DP * density));
            // Where the rows cannot fit even at the smallest tile (3 rows with the title on), the title keeps its
            // room above the dock and the last rows run off the bottom instead of the title drawing over the clock.
            if (MainActivity.this.dockRow && cfg.dockTitle && rowsBelowDock > 0) want = Math.max(want, (int) (AppAdapter.TITLE_ROOM_DP * density));
            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.
            if (!MainActivity.this.dockRow) want = (int) (AppAdapter.TOP_ROOM_DP * density);
            if (watchNextList != null && watchNextList.getVisibility() == View.VISIBLE) {
                want = (int) (6 * density);
            }
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                totalGridScrollY += dy;
                if (pendingGridScrollDy > 0) {
                    pendingGridScrollDy = Math.max(0, pendingGridScrollDy - dy);
                } else if (pendingGridScrollDy < 0) {
                    pendingGridScrollDy = Math.min(0, pendingGridScrollDy - dy);
                }
                updateBottomVignette(true);
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                if (glassGlideAnimator == null || !glassGlideAnimator.isRunning()) {
                    if (lastFocusedTileCard != null && lastFocusedTileCard.isAttachedToWindow() && lastFocusedTileCard.isFocused()) {
                        updateTileGlass(lastFocusedTileCard, lastFocused, false);
                    }
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                updateBottomVignette(true);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    pendingGridScrollDy = 0;
                    if (lastFocusedTileCard != null && lastFocusedTileCard.isFocused()) {
                        updateTileGlass(lastFocusedTileCard, lastFocused, false);
                    }
                }
            }
        });

        btnInputs = findViewById(R.id.btnInputs);
        btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        btnSettings.setOnClickListener(v -> open(systemIntent(Settings.ACTION_SETTINGS)));
        btnReorder = findViewById(R.id.btnReorder);
        btnReorder.setOnClickListener(v -> toggleReorder());
        btnReorder.setVisibility(cfg.reorderButton ? View.VISIBLE : View.GONE);
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (watchNextList != null && watchNextList.getVisibility() == View.VISIBLE && watchNextAdapter != null && watchNextAdapter.getItemCount() > 0) {
                    int targetCw = 0;
                    if (v.getId() == R.id.btnSettings) targetCw = Math.min(2, watchNextAdapter.getItemCount() - 1);
                    else if (v.getId() == R.id.btnInputs) targetCw = Math.min(3, watchNextAdapter.getItemCount() - 1);
                    focusWatchNext(targetCw);
                    return true;
                }
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            int leftButton = R.id.btnWifi;
            int rightButton = btnReorder.getVisibility() == View.VISIBLE ? R.id.btnReorder :
                    (btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs : R.id.btnSettings);
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnReorder.setOnKeyListener(pillKeys);
        for (View button : new View[]{btnWifi, btnSettings, btnInputs, btnReorder}) {
            button.setOnFocusChangeListener((view, focused) -> {
                view.animate()
                        .scaleX(focused ? 1.06f : 1f)
                        .scaleY(focused ? 1.06f : 1f)
                        .translationZ(focused ? 8f : 0f)
                        .setDuration(160)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                        .start();
            });
        }
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);

        dockContainer = findViewById(R.id.dockContainer);
        dockPill = findViewById(R.id.dockPill);

        // Hide the glass when focus truly leaves all tile/CW areas.
        // We deliberately do NOT hide on transient intermediate focus (RecyclerView parent, watchNextList
        // container, or grid container), because Android moves focus through the container before
        // settling on a child cardContainer/card — triggering hideGlass() mid-transition causes flicker.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF == null) return; // Transient null during focus handoff — ignore
            int id = newF.getId();
            // Directly on a tile, CW card, or dock card — glass stays
            if (id == R.id.card || id == R.id.cardContainer || id == R.id.dockCard) return;
            // Focus is on one of the RecyclerView containers themselves (watchNextList or grid) as an
            // intermediate step — keep the glass; a focus-change to the actual card follows immediately.
            if (id == R.id.watchNextList || id == R.id.grid) return;
            // Focus is still inside the watchNext, apps section, or dock pill subtree
            if (watchNextList != null && isDescendantOf(newF, watchNextList)) return;
            if (grid != null && isDescendantOf(newF, grid)) return;
            if (dockPill != null && isDescendantOf(newF, dockPill)) return;
            if (airPodsHud != null && isDescendantOf(newF, airPodsHud)) return;
            if (shareHud != null && isDescendantOf(newF, shareHud)) return;
            // Focus truly left tiles: clear tracking and fade the glass
            focusedWatchNextCard = null;
            lastFocusedTileCard = null;
            focusedAirPodsButton = null;
            focusedShareButton = null;
            hideGlass();
        });

        setupWatchNext();
        setupDockRow();

        try {
            watchNextObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    reloadWatchNext();
                }
            };
            getContentResolver().registerContentObserver(
                    WatchNextManager.WATCH_NEXT_URI,
                    true,
                    watchNextObserver
            );
        } catch (Exception e) {
            Log.d("TvHome", "Cannot register watch next observer: " + e.getMessage());
        }

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        initConnectivityCoordinator();
        initAirPodsViews();
        initShareViews();
        initAirPlayViews();
        initMangoEcosystem();

        // Debug/test receivers for live hardware verification over ADB
        try {
            IntentFilter testFilter = new IntentFilter("com.gathin.tvhome.action.TEST_AIRPODS");
            testFilter.addAction("com.gathin.tvhome.action.TEST_BUDS");
            testFilter.addAction("com.gathin.tvhome.action.TEST_SHARE");
            testFilter.addAction("com.gathin.tvhome.action.TEST_AIRPLAY");
            testFilter.addAction("com.gathin.tvhome.action.TEST_PLAY");
            testFilter.addAction("com.gathin.tvhome.action.TEST_PILL");
            testFilter.addAction("com.gathin.tvhome.action.START_REMOTE_PAIRING");
            testFilter.addAction("com.gathin.tvhome.action.TEST_REMOTE_PAIRING");
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if ("com.gathin.tvhome.action.TEST_AIRPODS".equals(action) ||
                            "com.gathin.tvhome.action.TEST_BUDS".equals(action)) {
                        AirPodsDevice mock = new AirPodsDevice("00:11:22:33:44:55");
                        mock.modelName = "AirPods Pro";
                        mock.modelId = 0x0E20;
                        mock.leftBattery = 92;
                        mock.rightBattery = 88;
                        mock.caseBattery = 74;
                        mock.isLeftCharging = false;
                        mock.isRightCharging = false;
                        mock.isCaseCharging = false;
                        mock.lidOpen = true;
                        if (connectivityCoordinator != null) {
                            connectivityCoordinator.showBudsPrompt(mock);
                        } else {
                            showAirPodsHud(mock);
                        }
                    } else if ("com.gathin.tvhome.action.TEST_SHARE".equals(action)) {
                        MangoShareServer.TransferSession mockSession =
                                new MangoShareServer.TransferSession("test-123", "Jenil's iPhone", "iOS", "mobile");
                        mockSession.addFile(new MangoShareServer.FileItem("f1", "Weekend.jpg", 8808038, "image/jpeg", "tok1"));
                        if (connectivityCoordinator != null) {
                            connectivityCoordinator.showSharePrompt(mockSession);
                        } else {
                            showShareHud(mockSession);
                        }
                    } else if ("com.gathin.tvhome.action.TEST_AIRPLAY".equals(action)) {
                        if (connectivityCoordinator != null) {
                            connectivityCoordinator.showStatusPill("AirPlay", "Jenil's iPhone connected", R.drawable.ic_airplay);
                        } else {
                            showAirPlayHud("Connected", "Jenil's iPhone");
                        }
                    } else if ("com.gathin.tvhome.action.TEST_PLAY".equals(action)) {
                        PlayHandoffRequest mockPlay = new PlayHandoffRequest(
                                "play-test",
                                "Jenil's iPhone",
                                "Interstellar",
                                "2h 49m • 4K",
                                "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                                4326000L,
                                10143000L,
                                null
                        );
                        if (connectivityCoordinator != null) {
                            connectivityCoordinator.showPlayHandoffPrompt(mockPlay);
                        }
                    } else if ("com.gathin.tvhome.action.TEST_PILL".equals(action)) {
                        if (connectivityCoordinator != null) {
                            connectivityCoordinator.showStatusPill("Mango Buds", "Tap to connect", R.drawable.ic_airpods);
                        }
                    } else if ("com.gathin.tvhome.action.START_REMOTE_PAIRING".equals(action) ||
                            "com.gathin.tvhome.action.TEST_REMOTE_PAIRING".equals(action)) {
                        startMangoRemotePairing();
                    }
                }
            }, testFilter);
        } catch (Throwable ignored) {}
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null) openLauncherSettingsIfAsked(getIntent());
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    private static final int STAGE_WATCH_NEXT = 0;
    private static final int STAGE_APPS_DOCK = 1;
    private static final int STAGE_APPS_GRID = 2;
    private static final int STAGE_DOCK_FOCUSED = 3; // Mac Dock focused — clock stays large, CW stays in row 3
    private int currentStage = STAGE_WATCH_NEXT;
    private boolean initialStageSet = false;
    private View appsContainer;
    private View appsHeader;
    private View watchNextSection;
    private View clockContainer;
    private ValueAnimator stageAnimator;
    private View lastFocusedTileCard;

    // Apple-like click sound effect
    private SoundPool soundPool;
    private int clickSoundId = 0;
    private boolean soundLoaded = false;

    private static boolean isNavKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void playAppleClickSound() {
        if (soundPool != null && soundLoaded && clickSoundId != 0) {
            soundPool.play(clickSoundId, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    private boolean canNavigate(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return true;
        }

        View focused = getCurrentFocus();
        if (focused == null) return false;

        // 1. Top bar menu buttons
        int id = focused.getId();
        if (id == R.id.btnWifi || id == R.id.btnSettings || id == R.id.btnInputs || id == R.id.btnReorder) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return false; // At absolute top
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return id != R.id.btnWifi;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int rightButton = (btnReorder != null && btnReorder.getVisibility() == View.VISIBLE) ? R.id.btnReorder :
                        ((btnInputs != null && btnInputs.getVisibility() == View.VISIBLE) ? R.id.btnInputs : R.id.btnSettings);
                return id != rightButton;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                boolean hasCw = (watchNextList != null && watchNextList.getVisibility() == View.VISIBLE
                        && watchNextAdapter != null && watchNextAdapter.getItemCount() > 0);
                boolean hasDock = !dockAppList.isEmpty() && dockPill != null && dockPill.getChildCount() > 0;
                boolean hasApps = adapter != null && adapter.appCount() > 0;
                return hasCw || hasDock || hasApps;
            }
            return true;
        }

        // 2. Continue Watching cards
        if (focusedWatchNextCard != null || isDescendantOf(focused, watchNextList)) {
            if (watchNextAdapter == null) return false;
            int count = watchNextAdapter.getItemCount();
            int pos = lastWatchNextFocused >= 0 ? lastWatchNextFocused : 0;
            if (watchNextList != null) {
                View directChild = watchNextList.findContainingItemView(focused);
                if (directChild != null) {
                    int adapterPos = watchNextList.getChildAdapterPosition(directChild);
                    if (adapterPos != RecyclerView.NO_POSITION) pos = adapterPos;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return pos > 0;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return pos < count - 1;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return (btnWifi != null && btnWifi.getVisibility() == View.VISIBLE)
                        || (btnSettings != null && btnSettings.getVisibility() == View.VISIBLE)
                        || (btnInputs != null && btnInputs.getVisibility() == View.VISIBLE);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                boolean hasDock = !dockAppList.isEmpty() && dockPill != null && dockPill.getChildCount() > 0;
                boolean hasApps = adapter != null && adapter.appCount() > 0;
                return hasDock || hasApps;
            }
            return true;
        }

        // 3. Mac Dock cards
        View focusedDock = getFocusedDockTile();
        if (focusedDock != null || isDescendantOf(focused, dockPill)) {
            int pos = focusedDockIndex;
            if (focusedDock != null && dockPill != null) {
                int childIdx = dockPill.indexOfChild(focusedDock);
                if (childIdx >= 0) pos = childIdx;
            }
            int count = dockAppList.size();
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return pos > 0;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return pos < count - 1;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                boolean hasCw = (watchNextList != null && watchNextList.getVisibility() == View.VISIBLE
                        && watchNextAdapter != null && watchNextAdapter.getItemCount() > 0);
                boolean hasTopBar = (btnWifi != null && btnWifi.getVisibility() == View.VISIBLE)
                        || (btnSettings != null && btnSettings.getVisibility() == View.VISIBLE)
                        || (btnInputs != null && btnInputs.getVisibility() == View.VISIBLE);
                return hasCw || hasTopBar;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return adapter != null && adapter.appCount() > 0;
            }
            return true;
        }

        // 4. Apps Grid cards
        if (isDescendantOf(focused, grid) || focused.getId() == R.id.card) {
            if (adapter == null) return false;
            int count = adapter.appCount();
            if (count == 0) return false;
            int pos = focusedTilePosition();
            if (pos < 0 && grid != null) {
                View directChild = grid.findContainingItemView(focused);
                if (directChild != null) {
                    int adapterPos = grid.getChildAdapterPosition(directChild);
                    if (adapterPos != RecyclerView.NO_POSITION) pos = adapterPos;
                }
            }
            if (pos < 0) pos = lastFocused >= 0 ? lastFocused : 0;

            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                boolean isLeftEdge = rtlAppGrid ? ((pos % COLUMNS) == COLUMNS - 1 || pos == count - 1) : ((pos % COLUMNS) == 0);
                return !isLeftEdge;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                boolean isRightEdge = rtlAppGrid ? ((pos % COLUMNS) == 0) : ((pos % COLUMNS) == COLUMNS - 1 || pos >= count - 1);
                return !isRightEdge;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
                if (pos >= lastRowStart) {
                    return adapter.isLibraryAt(count);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return true;
            }
        }

        return true;
    }

    private int totalGridScrollY = 0;
    private int pendingGridScrollDy = 0;
    private float currentTargetAppsY = 540f;
    private float currentTargetWatchNextY = 220f;
    private float currentTargetDockY = 390f;

    // Mac Dock row fields
    private FrameLayout dockContainer;
    private View dockPillBg;
    private LinearLayout dockPill;
    private ValueAnimator dockPillBgAnimator;
    private ValueAnimator glassGlideAnimator;
    private View currentFocusedCard;
    private List<DockApp> dockAppList = new ArrayList<>();
    private int focusedDockIndex = 0; // which dock tile is currently focused

    private static class DockApp {
        String pkg;
        String label;
        Drawable icon;
        Drawable banner;
        ComponentName component;
        Intent customIntent;
        View tileView;
        DockApp(String pkg, String label, Drawable icon, Drawable banner, ComponentName component, Intent customIntent) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.banner = banner;
            this.component = component;
            this.customIntent = customIntent;
        }
    }

    private int getGridRowHeight() {
        if (grid != null && grid.getChildCount() > 0) {
            View child = grid.getChildAt(0);
            if (child.getHeight() > 0) return child.getHeight();
        }
        return Math.round(146f * density);
    }

    private void scrollGridToRow(int appRow) {
        if (grid == null) return;
        int targetScrollRow = Math.max(0, appRow - 1);
        int itemH = getGridRowHeight();
        int targetScrollY = targetScrollRow * itemH;
        int dy = targetScrollY - totalGridScrollY;
        pendingGridScrollDy = dy;
        if (dy != 0) {
            if (isFastNavigating) {
                grid.scrollBy(0, dy);
            } else if (animations && !reducedMotion) {
                grid.smoothScrollBy(0, dy);
            } else {
                grid.scrollBy(0, dy);
            }
        }
    }

    private void setStageWatchNext() {
        // Stage 0: Clock large (Rows 1-2), CW in Row 3 (220dp), Dock in Row 4 (390dp) inside screen
        setStageInternal(STAGE_WATCH_NEXT, 540f * density, 220f * density, 1.0f, 1.0f, 0f);
        animateDock(390f * density, 1.0f);
        scrollGridToRow(0);
    }

    private void setStageDockFocused() {
        // Stage 0.5: Clock stays large (1.0f), CW stays in Row 3 (220dp), Dock in Row 4 (390dp)
        setStageInternal(STAGE_DOCK_FOCUSED, 540f * density, 220f * density, 1.0f, 1.0f, 0f);
        animateDock(390f * density, 1.0f);
        scrollGridToRow(0);
    }

    private void setStageForApp(int position) {
        int appRow = position / COLUMNS;
        if (appRow == 0) {
            // Stage 1: Clock shrinks to Row 1, CW moves to Row 2, Apps grid in Row 3 (305dp to prevent overlapping CW cards), Dock fades
            setStageInternal(STAGE_APPS_DOCK, 305f * density, 130f * density, 1.0f, 0.55f, -8f * density);
            animateDock(200f * density, 0f);
            scrollGridToRow(0);
        } else {
            // Stage 2: CW slides up under header layer, Apps grid scrolls
            setStageInternal(STAGE_APPS_GRID, 125f * density, -50f * density, 0.0f, 0.55f, -8f * density);
            animateDock(100f * density, 0f);
            scrollGridToRow(appRow);
        }
    }

    private void animateDock(float targetY, float targetAlpha) {
        if (dockContainer == null) return;
        currentTargetDockY = targetY;
        int duration = (animations && initialStageSet) ? (reducedMotion ? 120 : 240) : 0;
        dockContainer.animate().cancel();
        if (targetAlpha > 0f) dockContainer.setVisibility(View.VISIBLE);
        if (duration > 0) {
            dockContainer.animate()
                    .translationY(targetY)
                    .alpha(targetAlpha)
                    .setDuration(duration)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .setUpdateListener(anim -> {
                        if (glassShown && (glassGlideAnimator == null || !glassGlideAnimator.isRunning())) {
                            View focusedDock = getFocusedDockTile();
                            if (focusedDock != null) updateDockGlass(focusedDock, false);
                        }
                    })
                    .withEndAction(() -> {
                        if (targetAlpha == 0f) dockContainer.setVisibility(View.GONE);
                        updateBottomVignette(false);
                    })
                    .start();
        } else {
            dockContainer.setTranslationY(targetY);
            dockContainer.setAlpha(targetAlpha);
            if (targetAlpha == 0f) dockContainer.setVisibility(View.GONE);
            updateBottomVignette(false);
        }
        updateBottomVignette(duration > 0);
    }

    private void setStage(int stage) {
        if (stage == STAGE_WATCH_NEXT) {
            setStageWatchNext();
        } else if (stage == STAGE_DOCK_FOCUSED) {
            setStageDockFocused();
        } else if (stage == STAGE_APPS_DOCK) {
            setStageForApp(0);
        } else {
            setStageForApp(COLUMNS);
        }
    }

    private void setStageInternal(int stage, float targetAppsY, float targetWatchNextY, float targetWatchNextAlpha, float targetClockScale, float targetClockY) {
        if (appsContainer == null) appsContainer = findViewById(R.id.appsContainer);
        if (watchNextSection == null) watchNextSection = findViewById(R.id.watchNextSection);
        if (clockContainer == null) {
            clockContainer = findViewById(R.id.clockContainer);
            if (clockContainer != null) {
                clockContainer.setPivotX(0f);
                clockContainer.setPivotY(0f);
            }
        }

        currentTargetAppsY = targetAppsY;
        currentTargetWatchNextY = targetWatchNextY;

        if (appsHeader == null) appsHeader = findViewById(R.id.appsHeader);
        if (appsHeader != null) {
            boolean showHeader = (stage == STAGE_APPS_DOCK || stage == STAGE_APPS_GRID);
            appsHeader.setVisibility(showHeader ? View.VISIBLE : View.GONE);
        }

        if (initialStageSet && currentStage == stage && appsContainer != null
                && Math.abs(appsContainer.getTranslationY() - targetAppsY) < 1f) {
            return;
        }
        currentStage = stage;
        boolean isFirstStage = !initialStageSet;
        initialStageSet = true;

        int duration = (animations && !isFirstStage) ? (reducedMotion ? 120 : 240) : 0;
        Interpolator interpolator = new DecelerateInterpolator(1.5f);

        if (clockContainer != null) {
            clockContainer.setPivotX(0f);
            clockContainer.setPivotY(0f);
            clockContainer.animate().cancel();
            if (duration > 0) {
                clockContainer.animate()
                        .scaleX(targetClockScale)
                        .scaleY(targetClockScale)
                        .translationY(targetClockY)
                        .setDuration(duration)
                        .setInterpolator(interpolator)
                        .start();
            } else {
                clockContainer.setScaleX(targetClockScale);
                clockContainer.setScaleY(targetClockScale);
                clockContainer.setTranslationY(targetClockY);
            }
        }

        if (watchNextSection != null) {
            watchNextSection.animate().cancel();
            if (duration > 0) {
                watchNextSection.animate()
                        .translationY(targetWatchNextY)
                        .alpha(targetWatchNextAlpha)
                        .setDuration(duration)
                        .setInterpolator(interpolator)
                        .setUpdateListener(anim -> {
                            if (glassShown && (glassGlideAnimator == null || !glassGlideAnimator.isRunning())) {
                                if (focusedWatchNextCard != null && focusedWatchNextCard.isFocused()) {
                                    updateWatchNextGlass(focusedWatchNextCard, false);
                                }
                            }
                        })
                        .start();
            } else {
                watchNextSection.setTranslationY(targetWatchNextY);
                watchNextSection.setAlpha(targetWatchNextAlpha);
            }
        }

        if (appsContainer != null) {
            float targetAppsAlpha = (stage == STAGE_APPS_DOCK || stage == STAGE_APPS_GRID) ? 1.0f : 0.0f;
            appsContainer.animate().cancel();
            if (targetAppsAlpha > 0f) {
                appsContainer.setVisibility(View.VISIBLE);
            }
            if (duration > 0) {
                appsContainer.animate()
                        .translationY(targetAppsY)
                        .alpha(targetAppsAlpha)
                        .setDuration(duration)
                        .setInterpolator(interpolator)
                        .setUpdateListener(anim -> {
                            if (glassShown && (glassGlideAnimator == null || !glassGlideAnimator.isRunning())) {
                                updateCurrentFocusGlass(false);
                            }
                        })
                        .withEndAction(() -> {
                            if (targetAppsAlpha == 0f) appsContainer.setVisibility(View.GONE);
                        })
                        .start();
            } else {
                appsContainer.setTranslationY(targetAppsY);
                appsContainer.setAlpha(targetAppsAlpha);
                if (targetAppsAlpha == 0f) appsContainer.setVisibility(View.GONE);
            }
        }

        // Apple-style Wallpaper Frosted Blur:
        // When dock is not visible (apps grid / row 3+), wallpaper blur fades in smoothly.
        // When dock becomes visible again, blur is wiped off smoothly.
        boolean dockVisible = (stage == STAGE_WATCH_NEXT || stage == STAGE_DOCK_FOCUSED || stage == STAGE_APPS_DOCK);
        if (wallpaperPlayer != null) {
            wallpaperPlayer.setBlurActive(!dockVisible, duration > 0);
        }
        updateBottomVignette(duration > 0);
    }

    private void updateBottomVignette(boolean animate) {
        if (bottomVignette == null) return;
        boolean hasCardsOnRow4 = false;

        // 1. Check if Dock is visible on Row 4
        if (dockContainer != null && dockContainer.getVisibility() == View.VISIBLE && dockContainer.getAlpha() > 0.5f) {
            hasCardsOnRow4 = true;
        }

        // 2. Check if Apps grid has cards visible in Row 4 bottom area
        if (!hasCardsOnRow4 && grid != null && grid.getVisibility() == View.VISIBLE && appsContainer != null && appsContainer.getAlpha() > 0.5f) {
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            float row4Threshold = screenHeight - (180f * density);

            for (int i = 0; i < grid.getChildCount(); i++) {
                View child = grid.getChildAt(i);
                if (child != null && child.getVisibility() == View.VISIBLE) {
                    int[] loc = new int[2];
                    child.getLocationOnScreen(loc);
                    int childTop = loc[1];
                    int childBottom = childTop + child.getHeight();
                    if (childBottom >= row4Threshold && childTop < screenHeight - (20f * density)) {
                        hasCardsOnRow4 = true;
                        break;
                    }
                }
            }
        }

        float targetAlpha = hasCardsOnRow4 ? 1.0f : 0.0f;
        if (bottomVignette.getAlpha() == targetAlpha) return;
        bottomVignette.animate().cancel();
        if (animate && animations && !reducedMotion) {
            bottomVignette.animate()
                    .alpha(targetAlpha)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.5f))
                    .start();
        } else {
            bottomVignette.setAlpha(targetAlpha);
        }
    }

    private View getFocusedDockTile() {
        if (dockPill != null) {
            for (int i = 0; i < dockPill.getChildCount(); i++) {
                View child = dockPill.getChildAt(i);
                if (child != null && child.isFocused()) return child;
            }
        }
        return null;
    }

    private void updateCurrentFocusGlass(boolean spring) {
        if (!spring && glassGlideAnimator != null && glassGlideAnimator.isRunning()) return;
        View focusedDock = getFocusedDockTile();
        if (focusedDock != null) {
            updateDockGlass(focusedDock, spring);
        } else if (focusedWatchNextCard != null && focusedWatchNextCard.isAttachedToWindow() && focusedWatchNextCard.isFocused()) {
            updateWatchNextGlass(focusedWatchNextCard, spring);
        } else if (lastFocusedTileCard != null && lastFocusedTileCard.isAttachedToWindow() && lastFocusedTileCard.isFocused()) {
            updateTileGlass(lastFocusedTileCard, lastFocused, spring);
        } else if (focusedAirPodsButton != null && focusedAirPodsButton.isAttachedToWindow() && focusedAirPodsButton.isFocused()) {
            updateAirPodsButtonGlass(focusedAirPodsButton, spring);
        } else if (focusedShareButton != null && focusedShareButton.isAttachedToWindow() && focusedShareButton.isFocused()) {
            updateShareButtonGlass(focusedShareButton, spring);
        } else {
            hideGlass();
        }
    }

    // ---- Mac Dock Row ----------------------------------------------------------------------------

    private Drawable getAppBanner(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.banner != 0) {
                return pm.getApplicationBanner(ai);
            }
            return pm.getApplicationIcon(ai);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void setupDockRow() {
        dockContainer = findViewById(R.id.dockContainer);
        dockPill = findViewById(R.id.dockPill);
        dockPillBg = findViewById(R.id.dockPillBg);
        if (dockContainer == null || dockPill == null) return;

        if (dockPillBg != null) {
            FrameLayout.LayoutParams bgLp = (FrameLayout.LayoutParams) dockPillBg.getLayoutParams();
            if (bgLp != null) {
                bgLp.width = Math.round(594f * density);
                bgLp.height = Math.round(97f * density);
                bgLp.gravity = Gravity.CENTER_HORIZONTAL;
                dockPillBg.setLayoutParams(bgLp);
            }
            dockPillBg.setTranslationX(0f);
        }

        dockContainer.setTranslationY(390f * density);
        currentTargetDockY = 390f * density;
        dockContainer.setAlpha(1.0f);
        dockContainer.setVisibility(View.VISIBLE);

        dockAppList.clear();
        dockPill.removeAllViews();
        dockPill.setSoundEffectsEnabled(false);

        Set<String> addedPkgs = new HashSet<>();

        // 1. Netflix
        AppAdapter.Entry netflix = adapter != null ? adapter.findEntry("com.netflix.ninja", "com.netflix.mediaclient") : null;
        if (netflix != null) {
            dockAppList.add(new DockApp(netflix.pkg, netflix.label, netflix.icon, netflix.banner, netflix.component, null));
            addedPkgs.add(netflix.pkg);
        } else {
            Intent netflixIntent = getPackageManager().getLaunchIntentForPackage("com.netflix.ninja");
            if (netflixIntent != null) {
                Drawable banner = getAppBanner("com.netflix.ninja");
                dockAppList.add(new DockApp("com.netflix.ninja", "Netflix", null, banner, null, netflixIntent));
                addedPkgs.add("com.netflix.ninja");
            }
        }

        // 2. Prime Video
        AppAdapter.Entry prime = adapter != null ? adapter.findEntry("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient", "com.amazon.cloud9") : null;
        if (prime != null && prime.banner != null) {
            dockAppList.add(new DockApp(prime.pkg, prime.label, prime.icon, prime.banner, prime.component, null));
            addedPkgs.add(prime.pkg);
        } else {
            // Fire TV Prime Video Slate
            Intent primeIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://app.primevideo.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Drawable banner = getDrawable(R.drawable.banner_prime_video);
            dockAppList.add(new DockApp("com.amazon.cloud9", "Prime Video", null, banner, null, primeIntent));
            addedPkgs.add("com.amazon.cloud9");
        }

        // 3. YouTube
        AppAdapter.Entry youtube = adapter != null ? adapter.findEntry("com.amazon.firetv.youtube", "com.google.android.youtube.tv") : null;
        if (youtube != null) {
            dockAppList.add(new DockApp(youtube.pkg, youtube.label, youtube.icon, youtube.banner, youtube.component, null));
            addedPkgs.add(youtube.pkg);
        } else {
            Intent ytIntent = getPackageManager().getLaunchIntentForPackage("com.amazon.firetv.youtube");
            if (ytIntent != null) {
                Drawable banner = getAppBanner("com.amazon.firetv.youtube");
                dockAppList.add(new DockApp("com.amazon.firetv.youtube", "YouTube", null, banner, null, ytIntent));
                addedPkgs.add("com.amazon.firetv.youtube");
            }
        }

        // 4. Jio Hotstar
        AppAdapter.Entry hotstar = adapter != null ? adapter.findEntry("in.startv.hotstar") : null;
        if (hotstar != null) {
            dockAppList.add(new DockApp(hotstar.pkg, hotstar.label, hotstar.icon, hotstar.banner, hotstar.component, null));
            addedPkgs.add(hotstar.pkg);
        } else {
            Intent hotstarIntent = getPackageManager().getLaunchIntentForPackage("in.startv.hotstar");
            if (hotstarIntent != null) {
                Drawable banner = getAppBanner("in.startv.hotstar");
                dockAppList.add(new DockApp("in.startv.hotstar", "Hotstar", null, banner, null, hotstarIntent));
                addedPkgs.add("in.startv.hotstar");
            }
        }

        // If fewer than 4 installed, fill up to 4 with other most used apps from adapter
        if (dockAppList.size() < 4 && adapter != null) {
            for (int i = 0; i < adapter.appCount() && dockAppList.size() < 4; i++) {
                AppAdapter.Entry entry = adapter.entryAt(i);
                if (entry != null && entry.kind != AppAdapter.Entry.EMPTY && !addedPkgs.contains(entry.pkg)) {
                    dockAppList.add(new DockApp(entry.pkg, entry.label, entry.icon, entry.banner, entry.component, null));
                    addedPkgs.add(entry.pkg);
                }
            }
        }

        // Inflate tiles into dockPill
        LayoutInflater inflater = LayoutInflater.from(this);
        // Apple continuous corner curvature: 18dp radius matching CW cards and CW glass slider
        final float cardCornerRadius = 18f * density;
        final ViewOutlineProvider cardOutlineProvider = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cardCornerRadius);
            }
        };

        for (int i = 0; i < dockAppList.size(); i++) {
            final int idx = i;
            DockApp app = dockAppList.get(i);
            View tile = inflater.inflate(R.layout.item_dock_app, dockPill, false);
            ImageView bannerView = tile.findViewById(R.id.dockBanner);
            ImageView iconView = tile.findViewById(R.id.dockIcon);
            TextView labelView = tile.findViewById(R.id.dockLabel);

            // Apple rounded corners: hardware-accelerated clipping for both tile and banner
            tile.setOutlineProvider(cardOutlineProvider);
            tile.setClipToOutline(true);
            tile.setSoundEffectsEnabled(false);
            bannerView.setOutlineProvider(cardOutlineProvider);
            bannerView.setClipToOutline(true);

            if (app.banner != null) {
                bannerView.setImageDrawable(app.banner);
                bannerView.setVisibility(View.VISIBLE);
                iconView.setVisibility(View.GONE);
                labelView.setVisibility(View.GONE);
            } else if (app.icon != null) {
                bannerView.setVisibility(View.GONE);
                iconView.setImageDrawable(app.icon);
                iconView.setVisibility(View.VISIBLE);
                labelView.setText(app.label);
                labelView.setVisibility(View.VISIBLE);
            }

            app.tileView = tile;

            tile.setPivotX(68f * density);
            tile.setPivotY(77f * density);

            tile.setOnFocusChangeListener((v, focused) -> onDockTileFocused(v, focused, idx));
            tile.setOnKeyListener((v, keyCode, event) -> onDockTileKey(v, keyCode, event, idx));
            tile.setOnClickListener(v -> launchDockApp(idx));

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tile.getLayoutParams();
            if (lp == null) {
                lp = new LinearLayout.LayoutParams(
                        Math.round(136f * density),
                        Math.round(77f * density)
                );
            }
            lp.setMarginStart(i > 0 ? Math.round(10f * density) : 0);
            lp.setMarginEnd(0);
            tile.setLayoutParams(lp);

            dockPill.addView(tile);
        }

        dockPill.post(() -> {
            int w = dockPill.getWidth();
            int h = dockPill.getHeight();
            if (dockPillBg != null && w > 0 && h > 0) {
                ViewGroup.LayoutParams lp = dockPillBg.getLayoutParams();
                if (lp != null) {
                    lp.width = w;
                    lp.height = h;
                    dockPillBg.setLayoutParams(lp);
                }
            }
        });
    }

    private void animateDockPillBg(float targetExtraWidth, float targetShiftX) {
        if (dockPillBg == null || dockPill == null) return;
        int restW = dockPill.getWidth() > 0 ? dockPill.getWidth() : Math.round(594f * density);
        int restH = dockPill.getHeight() > 0 ? dockPill.getHeight() : Math.round(97f * density);

        final float finalTargetW = restW + targetExtraWidth;
        final float targetScaleX = restW > 0 ? (finalTargetW / restW) : 1f;
        final float finalTargetX = targetShiftX;

        ViewGroup.LayoutParams curLp = dockPillBg.getLayoutParams();
        if (curLp != null && (curLp.width != restW || curLp.height != restH)) {
            curLp.width = restW;
            curLp.height = restH;
            dockPillBg.setLayoutParams(curLp);
        }
        dockPillBg.setPivotX(restW / 2f);
        dockPillBg.setPivotY(restH / 2f);

        if (dockPillBgAnimator != null) {
            dockPillBgAnimator.cancel();
            dockPillBgAnimator = null;
        }

        dockPillBg.animate().cancel();
        if (!animations || reducedMotion) {
            dockPillBg.setScaleX(targetScaleX);
            dockPillBg.setTranslationX(finalTargetX);
            return;
        }

        dockPillBg.animate()
                .scaleX(targetScaleX)
                .translationX(finalTargetX)
                .setDuration(180)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
    }

    private void onDockTileFocused(View tile, boolean hasFocus, int idx) {
        if (!hasFocus) {
            // Post check so if another dock tile is gaining focus, we don't flash back to rest
            tile.post(() -> {
                if (getFocusedDockTile() == null) {
                    animateDockPillBg(0f, 0f);
                    for (int i = 0; i < dockAppList.size(); i++) {
                        View t = dockAppList.get(i).tileView;
                        if (t != null) {
                            t.animate().cancel();
                            t.animate()
                                    .scaleX(1.0f)
                                    .scaleY(1.0f)
                                    .translationX(0f)
                                    .translationZ(0f)
                                    .setDuration(animations ? 180 : 0)
                                    .setInterpolator(new DecelerateInterpolator(1.4f))
                                    .start();
                        }
                    }
                }
            });
            return;
        }

        focusedDockIndex = idx;
        setStageDockFocused();

        // Mac Dock Magnification & Neighbor Physics:
        // Focused card grows 1.30x from the TOP side only (pivotY = height)
        // Neighbors stay at 1.0f scale and slide outward horizontally to make room
        for (int j = 0; j < dockAppList.size(); j++) {
            DockApp app = dockAppList.get(j);
            if (app == null || app.tileView == null) continue;
            View t = app.tileView;
            t.animate().cancel();

            float pivotX = t.getWidth() > 0 ? t.getWidth() / 2f : (136f * density) / 2f;
            float pivotY = t.getHeight() > 0 ? t.getHeight() : (77f * density);
            t.setPivotX(pivotX);
            t.setPivotY(pivotY);

            if (j == idx) {
                // Selected card grows 1.30x on top side, stays at translationX = 0f
                t.setTranslationZ(10f);
                t.animate()
                        .scaleX(1.30f)
                        .scaleY(1.30f)
                        .translationX(0f)
                        .translationZ(10f)
                        .setDuration(animations ? 200 : 0)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .start();
            } else {
                // Neighbor cards stay at 1.0f scale, push outward
                int dist = j - idx;
                float shiftX = 0f;
                if (dist == -1) shiftX = -22f * density;
                else if (dist == -2) shiftX = -28f * density;
                else if (dist <= -3) shiftX = -32f * density;
                else if (dist == 1) shiftX = 22f * density;
                else if (dist == 2) shiftX = 28f * density;
                else if (dist >= 3) shiftX = 32f * density;

                t.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationX(shiftX)
                        .translationZ(0f)
                        .setDuration(animations ? 200 : 0)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .start();
            }
        }

        // Animate dock pill background expansion to maintain 10dp side margins
        float leftDelta = 0f;
        float rightDelta = 0f;
        if (idx == 0) {
            leftDelta = (136f * 0.30f / 2f) * density;
            rightDelta = 32f * density;
        } else if (idx == 1) {
            leftDelta = 22f * density;
            rightDelta = 28f * density;
        } else if (idx == 2) {
            leftDelta = 28f * density;
            rightDelta = 22f * density;
        } else if (idx >= 3) {
            leftDelta = 32f * density;
            rightDelta = (136f * 0.30f / 2f) * density;
        }
        float extraWidth = leftDelta + rightDelta;
        float shiftX = (rightDelta - leftDelta) / 2f;
        animateDockPillBg(extraWidth, shiftX);

        updateDockGlass(tile, true);
    }

    private boolean onDockTileKey(View v, int keyCode, android.view.KeyEvent event, int idx) {
        if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
            return keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER
                    || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
        }
        if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
            if (idx > 0) {
                View prev = dockAppList.get(idx - 1).tileView;
                if (prev != null) {
                    focusedDockIndex = idx - 1;
                    prev.requestFocus();
                }
            }
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (idx < dockAppList.size() - 1) {
                View next = dockAppList.get(idx + 1).tileView;
                if (next != null) {
                    focusedDockIndex = idx + 1;
                    next.requestFocus();
                }
            }
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
            // Go back to Continue Watching directly above this dock tile
            if (watchNextList != null && watchNextList.getVisibility() == View.VISIBLE && watchNextAdapter != null && watchNextAdapter.getItemCount() > 0) {
                int targetCw = Math.min(idx, watchNextAdapter.getItemCount() - 1);
                focusWatchNext(targetCw);
                return true;
            }
            if (btnWifi != null && btnWifi.getVisibility() == View.VISIBLE) {
                btnWifi.requestFocus();
            } else if (btnSettings != null && btnSettings.getVisibility() == View.VISIBLE) {
                btnSettings.requestFocus();
            }
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
            // User scrolls down to discover more apps in the apps grid
            if (adapter != null && adapter.appCount() > 0) {
                int targetTile = Math.min(idx, adapter.appCount() - 1);
                focusTile(targetTile);
            }
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == android.view.KeyEvent.KEYCODE_ENTER
                || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
            launchDockApp(idx);
            return true;
        }
        return false;
    }

    private void launchDockApp(int idx) {
        if (idx < 0 || idx >= dockAppList.size()) return;
        DockApp app = dockAppList.get(idx);
        if (app.customIntent != null) {
            open(app.customIntent);
        } else if (app.component != null) {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            open(intent);
        } else if (app.pkg != null) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(app.pkg);
            if (intent != null) {
                open(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private float[] getCardCenter(View card, float scale, boolean isDock, boolean targetResting) {
        if (isDock) {
            float rx = card.getLeft() + card.getWidth() / 2f;
            float ry = card.getTop();
            View p = (View) card.getParent();
            while (p != null && p != root) {
                rx += p.getLeft();
                ry += p.getTop();
                p = (View) p.getParent();
            }
            float targetDockY = targetResting ? currentTargetDockY : (dockContainer != null ? dockContainer.getTranslationY() : 0f);
            float topInRoot = ry + targetDockY;
            float bottomInRoot = topInRoot + card.getHeight();
            float centerY = bottomInRoot - (card.getHeight() * scale) / 2f;
            return new float[]{rx, centerY};
        }

        int[] cardLoc = new int[2];
        card.getLocationInWindow(cardLoc);
        int[] rootLoc = new int[2];
        root.getLocationInWindow(rootLoc);

        float leftInRoot = cardLoc[0] - rootLoc[0];
        float topInRoot = cardLoc[1] - rootLoc[1];

        // Container translation compensation:
        // When navigating vertically between stages (Dock <-> CW <-> Apps Grid),
        // parent containers (dockContainer, watchNextSection, appsContainer) actively animate translationY.
        // If we query getLocationInWindow during or at the start of that animation, cardLoc[1]
        // reflects the in-flight coordinate rather than the final settled resting coordinate.
        // Adding the remaining translation (targetY - currentTranslationY) compensates for this
        // so the glass glides directly to the final resting position of the card, matching container arrival.
        if (targetResting) {
            if (watchNextSection != null && isDescendantOf(card, watchNextSection)) {
                topInRoot += (currentTargetWatchNextY - watchNextSection.getTranslationY());
            } else if (appsContainer != null && isDescendantOf(card, appsContainer)) {
                topInRoot += (currentTargetAppsY - appsContainer.getTranslationY()) - pendingGridScrollDy;
            }
        }

        // Center calculation:
        float centerX = leftInRoot + (card.getWidth() * card.getScaleX()) / 2f;
        float centerY = topInRoot + (card.getHeight() * card.getScaleY()) / 2f;
        return new float[]{centerX, centerY};
    }

    private void glideGlassTo(View card, float targetW, float targetH, float scale, float radius, int accent, boolean isDock) {
        if (card == null || root == null || glass == null || !card.isAttachedToWindow() || !card.isFocused()) {
            return;
        }

        currentFocusedCard = card;

        float[] targetCenter = getCardCenter(card, scale, isDock, true);
        final float finalTargetCenterX = targetCenter[0];
        final float finalTargetCenterY = targetCenter[1];
        final float finalTargetW = targetW;
        final float finalTargetH = targetH;
        final float finalRadius = radius;
        final int finalAccent = accent;

        // If glass is not shown yet or has no measured size, snap directly
        if (!glassShown || glass.getAlpha() < 0.1f || glass.getWidth() <= 0 || glass.getHeight() <= 0) {
            snapGlassTo(card, targetW, targetH, scale, radius, accent, isDock);
            return;
        }

        if (!animations || reducedMotion) {
            snapGlassTo(card, targetW, targetH, scale, radius, accent, isDock);
            return;
        }

        if (glassGlideAnimator != null) {
            glassGlideAnimator.cancel();
            glassGlideAnimator = null;
        }

        // Starting state from current in-flight visual bounds
        final float baseW = glass.getWidth();
        final float baseH = glass.getHeight();
        final float curVisualW = baseW * glass.getScaleX();
        final float curVisualH = baseH * glass.getScaleY();
        final float startCenterX = glass.getTranslationX() + curVisualW / 2f;
        final float startCenterY = glass.getTranslationY() + curVisualH / 2f;
        final float startRadius = glass.getRadius();

        glass.setPivotX(0f);
        glass.setPivotY(0f);
        glass.setAccent(accent);

        int duration = isFastNavigating ? Math.max(40, 180 - Math.min(navHoldStep * 18, 140)) : (reducedMotion ? 120 : (isDock ? 200 : 220));
        glassGlideAnimator = ValueAnimator.ofFloat(0f, 1f);
        glassGlideAnimator.setDuration(duration);
        glassGlideAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
        glassGlideAnimator.addUpdateListener(anim -> {
            float f = (float) anim.getAnimatedValue();
            float w = curVisualW + f * (finalTargetW - curVisualW);
            float h = curVisualH + f * (finalTargetH - curVisualH);
            float cx = startCenterX + f * (finalTargetCenterX - startCenterX);
            float cy = startCenterY + f * (finalTargetCenterY - startCenterY);

            glass.setScaleX(w / baseW);
            glass.setScaleY(h / baseH);
            glass.setTranslationX(cx - w / 2f);
            glass.setTranslationY(cy - h / 2f);
            glass.setRadius(startRadius + f * (finalRadius - startRadius));
        });
        glassGlideAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (glassGlideAnimator == animation) {
                    glassGlideAnimator = null;
                }
                if (!cancelled && currentFocusedCard == card && card.isAttachedToWindow() && card.isFocused()) {
                    snapGlassTo(card, finalTargetW, finalTargetH, scale, finalRadius, finalAccent, isDock);
                }
            }
        });
        glassGlideAnimator.start();
    }

    private void snapGlassTo(View card, float targetW, float targetH, float scale, float radius, int accent, boolean isDock) {
        if (card == null || root == null || glass == null || !card.isAttachedToWindow() || !card.isFocused()) {
            return;
        }

        currentFocusedCard = card;

        if (glassGlideAnimator != null) {
            glassGlideAnimator.cancel();
            glassGlideAnimator = null;
        }

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        int intW = Math.round(targetW);
        int intH = Math.round(targetH);
        if (lp.width != intW || lp.height != intH) {
            lp.width = intW;
            lp.height = intH;
            glass.setLayoutParams(lp);
            glass.measure(
                    View.MeasureSpec.makeMeasureSpec(intW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(intH, View.MeasureSpec.EXACTLY)
            );
            glass.layout(glass.getLeft(), glass.getTop(), glass.getLeft() + intW, glass.getTop() + intH);
        }
        glass.setScaleX(1.0f);
        glass.setScaleY(1.0f);
        glass.setPivotX(0f);
        glass.setPivotY(0f);
        glass.setRadius(radius);
        glass.setAccent(accent);

        float[] center = getCardCenter(card, scale, isDock, false);
        glass.setTranslationX(center[0] - targetW / 2f);
        glass.setTranslationY(center[1] - targetH / 2f);
        if (!glassShown || glass.getAlpha() < 1f) {
            if (animations) glass.animate().alpha(1f).setDuration(reducedMotion ? 100 : 160).start();
            else glass.setAlpha(1f);
            glassShown = true;
        }
    }

    private void updateDockGlass(View tile, boolean spring) {
        if (tile == null || root == null || glass == null || !tile.isAttachedToWindow() || !tile.isFocused()) {
            return;
        }

        if (tile.getWidth() == 0 || tile.getHeight() == 0) {
            tile.post(() -> {
                if (tile.isAttachedToWindow() && tile.isFocused() && tile.getWidth() > 0) {
                    updateDockGlass(tile, false);
                }
            });
            return;
        }

        float pad = 6f * density;
        float scale = 1.30f;
        float w = tile.getWidth() * scale + 2 * pad;
        float h = tile.getHeight() * scale + 2 * pad;
        float radius = 18f * density * scale;
        int accent = festive ? festival.accentColor() : 0;

        if (spring) {
            glideGlassTo(tile, w, h, scale, radius, accent, true);
        } else {
            snapGlassTo(tile, w, h, scale, radius, accent, true);
        }
    }

    private void setupWatchNext() {
        watchNextList = findViewById(R.id.watchNextList);
        watchNextSection = findViewById(R.id.watchNextSection);
        appsContainer = findViewById(R.id.appsContainer);
        clockContainer = findViewById(R.id.clockContainer);
        if (watchNextList == null) return;

        watchNextList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        watchNextList.setHasFixedSize(true);
        watchNextList.setSoundEffectsEnabled(false);
        watchNextList.setItemViewCacheSize(6);

        watchNextItems = WatchNextManager.load(this);
        if (watchNextItems == null || watchNextItems.isEmpty()) {
            if (watchNextSection != null) watchNextSection.setVisibility(View.GONE);
            currentStage = STAGE_DOCK_FOCUSED;
            return;
        }

        if (watchNextSection != null) watchNextSection.setVisibility(View.VISIBLE);

        watchNextAdapter = new WatchNextAdapter(this, watchNextItems, this::onWatchNextItemClick, (v, keyCode, event, position) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            lastWatchNextFocused = position;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (btnInputs != null && btnInputs.getVisibility() == View.VISIBLE && position >= 3) {
                    btnInputs.requestFocus();
                } else if (position >= 2) {
                    if (btnSettings != null && btnSettings.getVisibility() == View.VISIBLE) {
                        btnSettings.requestFocus();
                    } else if (btnWifi != null && btnWifi.getVisibility() == View.VISIBLE) {
                        btnWifi.requestFocus();
                    }
                } else {
                    if (btnWifi != null && btnWifi.getVisibility() == View.VISIBLE) {
                        btnWifi.requestFocus();
                    } else if (btnSettings != null && btnSettings.getVisibility() == View.VISIBLE) {
                        btnSettings.requestFocus();
                    }
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Stage 0.5: go to Dock row if it has apps, else go to apps grid
                if (!dockAppList.isEmpty() && dockPill != null && dockPill.getChildCount() > 0) {
                    int dockTarget = Math.min(position, dockAppList.size() - 1);
                    View dockTile = dockAppList.get(dockTarget).tileView;
                    if (dockTile != null) {
                        setStageDockFocused();
                        dockTile.requestFocus();
                        return true;
                    }
                }
                int targetTile = Math.min(position, Math.min(COLUMNS - 1, adapter.appCount() - 1));
                if (targetTile >= 0) {
                    focusTile(targetTile);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (position <= 0) return true;
                focusWatchNext(position - 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (position >= watchNextAdapter.getItemCount() - 1) return true;
                focusWatchNext(position + 1);
                return true;
            }
            return false;
        }, this::onWatchNextFocused);

        watchNextList.setAdapter(watchNextAdapter);
        watchNextList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (glassShown && (glassGlideAnimator == null || !glassGlideAnimator.isRunning())) {
                    if (focusedWatchNextCard != null && focusedWatchNextCard.isAttachedToWindow() && focusedWatchNextCard.isFocused()) {
                        updateWatchNextGlass(focusedWatchNextCard, false);
                    }
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && focusedWatchNextCard != null && focusedWatchNextCard.isFocused()) {
                    updateWatchNextGlass(focusedWatchNextCard, false);
                }
            }
        });

        setStageWatchNext();
        watchNextList.post(() -> focusWatchNext(0));
    }

    private void onWatchNextFocused(View card, boolean hasFocus, int position) {
        if (!hasFocus) {
            if (focusedWatchNextCard == card) focusedWatchNextCard = null;
            return;
        }
        lastFocusedTileCard = null;
        focusedWatchNextCard = card;
        lastWatchNextFocused = position;
        setStageWatchNext();
        updateWatchNextGlass(card, true);
    }

    private void updateWatchNextGlass(View card, boolean spring) {
        if (card == null || root == null || glass == null || !card.isAttachedToWindow() || !card.isFocused()) {
            hideGlass();
            return;
        }

        if (card.getWidth() == 0 || card.getHeight() == 0) {
            card.post(() -> {
                if (card.isAttachedToWindow() && card.isFocused() && card.getWidth() > 0) {
                    updateWatchNextGlass(card, false);
                }
            });
            return;
        }

        float pad = 6f * density;
        float scale = 1.04f;
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float radius = 18f * density * scale;
        int accent = 0;

        if (spring) {
            glideGlassTo(card, w, h, scale, radius, accent, false);
        } else {
            snapGlassTo(card, w, h, scale, radius, accent, false);
        }
    }

    private void focusWatchNext() {
        focusWatchNext(lastWatchNextFocused >= 0 ? lastWatchNextFocused : 0);
    }

    private void focusWatchNext(int targetPos) {
        if (watchNextList == null || watchNextList.getVisibility() != View.VISIBLE || watchNextAdapter == null || watchNextAdapter.getItemCount() == 0) {
            return;
        }
        setStageWatchNext();
        if (targetPos < 0 || targetPos >= watchNextAdapter.getItemCount()) targetPos = 0;
        lastWatchNextFocused = targetPos;
        RecyclerView.ViewHolder vh = watchNextList.findViewHolderForAdapterPosition(targetPos);
        if (vh != null && vh.itemView != null) {
            View card = vh.itemView.findViewById(R.id.cardContainer);
            if (card != null) {
                if (card.isFocused()) onWatchNextFocused(card, true, targetPos);
                else card.requestFocus();
                return;
            }
        }
        final int pos = targetPos;
        if (animations && !reducedMotion) {
            watchNextList.smoothScrollToPosition(pos);
        } else {
            watchNextList.scrollToPosition(pos);
        }
        watchNextList.post(() -> {
            RecyclerView.ViewHolder vh2 = watchNextList.findViewHolderForAdapterPosition(pos);
            if (vh2 != null && vh2.itemView != null) {
                View card = vh2.itemView.findViewById(R.id.cardContainer);
                if (card != null) {
                    if (card.isFocused()) onWatchNextFocused(card, true, pos);
                    else card.requestFocus();
                }
            } else if (watchNextList.getChildCount() > 0) {
                View card = watchNextList.getChildAt(0).findViewById(R.id.cardContainer);
                if (card != null) {
                    if (card.isFocused()) onWatchNextFocused(card, true, 0);
                    else card.requestFocus();
                }
            }
        });
    }

    private void onWatchNextItemClick(WatchNextItem item) {
        if (item.launchIntent != null) {
            open(item.launchIntent);
        } else if (item.packageName != null && !item.packageName.isEmpty()) {
            PackageManager pm = getPackageManager();
            Intent intent = pm.getLeanbackLaunchIntentForPackage(item.packageName);
            if (intent == null) intent = pm.getLaunchIntentForPackage(item.packageName);
            if (intent != null) {
                open(intent);
            } else {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void reloadWatchNext() {
        watchNextExecutor.execute(() -> {
            List<WatchNextItem> items = WatchNextManager.load(this);
            Log.i("TvHome", "reloadWatchNext loaded count=" + (items != null ? items.size() : 0));
            runOnUiThread(() -> applyWatchNextItems(items));
        });
    }

    private void applyWatchNextItems(List<WatchNextItem> items) {
        if (isFinishing() || isDestroyed()) return;
        this.watchNextItems = items;
        if (items == null || items.isEmpty()) {
            if (watchNextSection != null) {
                watchNextSection.setVisibility(View.GONE);
            }
            if (focusedWatchNextCard != null || (watchNextList != null && isDescendantOf(getCurrentFocus(), watchNextList))) {
                focusedWatchNextCard = null;
                focusTile(lastFocused >= 0 ? lastFocused : 0);
            }
            if (watchNextAdapter != null) {
                watchNextAdapter.setItems(Collections.emptyList());
            }
        } else {
            if (watchNextSection != null) {
                watchNextSection.setVisibility(View.VISIBLE);
            }
            if (watchNextAdapter == null) {
                setupWatchNext();
            } else {
                watchNextAdapter.setItems(items);
            }
        }
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.i("TvHome", "onNewIntent: action=" + (intent != null ? intent.getAction() : "null"));
        if (openLauncherSettingsIfAsked(intent)) return;
        if (intent != null && "com.gathin.tvhome.action.ADD_WATCH_NEXT".equals(intent.getAction())) {
            ContentValues cv = new ContentValues();
            String pkg = intent.getStringExtra("pkg");
            if (pkg == null) pkg = getPackageName();
            cv.put("package_name", pkg);
            cv.put("title", intent.getStringExtra("title"));
            if (intent.hasExtra("episode_title")) cv.put("episode_title", intent.getStringExtra("episode_title"));
            cv.put("last_playback_position_millis", intent.getLongExtra("pos", 120000L));
            cv.put("duration_millis", intent.getLongExtra("dur", 360000L));
            cv.put("type", 0);
            if (intent.hasExtra("poster")) cv.put("poster_art_uri", intent.getStringExtra("poster"));
            if (intent.hasExtra("intent_uri")) cv.put("intent_uri", intent.getStringExtra("intent_uri"));
            cv.put("last_engagement_time_utc_millis", System.currentTimeMillis());
            WatchNextManager.insertProgram(this, cv);
            return;
        }
        if (intent != null && "com.gathin.tvhome.action.CLEAR_WATCH_NEXT".equals(intent.getAction())) {
            getContentResolver().delete(WatchNextManager.WATCH_NEXT_URI, null, null);
            return;
        }
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        rebuildOnResume = true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        promptsPaused = false;
        refreshMangoEcosystem();
        if (rebuildOnResume) {
            tilesReady = false;
            reloadApps(true);
        }
        rebuildOnResume = false;
        updateGreeting();
        updateWifiLabel();
        updateInputsButton();
        if (showStats) startStats();
        sky.update();
        wallpaperPlayer.start();
        reloadWatchNext();
        updateBottomVignette(false);
        WeatherManager.registerListener(homeWeatherListener);
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (festive && diya != null) {
            diya.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.postDelayed(this::playPetalShower, 450);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        cancelPendingFocusScroll();
        RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
        final boolean restoreLibrary = adapter.isLibraryAt(adapter.appCount()) && footer != null
                && footer.itemView.findViewById(R.id.btnLibrary) != null
                && footer.itemView.findViewById(R.id.btnLibrary).hasFocus();
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = appLibrary == null && (animate || grid.hasFocus());
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            heroName.setAlpha(0f);
            if (ambient != null) ambient.setAlpha(0f);
        }
        reloadTiles();
        if (animate && animations && !reducedMotion) grid.scheduleLayoutAnimation();

        if (restoreFocus) {
            // Keep a focused footer at the end even when the app count changes.
            if (restoreLibrary) grid.post(this::focusLibrary);
            else restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.appCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        if (reordering) endReorder();   // an app installed or removed while reordering: keep the order so far
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, Cards.load(Cards.storeFile(this))), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none));
        setupDockRow();
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final boolean returnToLibrary = libraryFocusPending;
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            if (returnToLibrary || libraryFocusPending) focusLibrary();
            else if (watchNextList != null && watchNextList.getVisibility() == View.VISIBLE && watchNextAdapter != null && watchNextAdapter.getItemCount() > 0) {
                if (currentStage == STAGE_WATCH_NEXT || returnKey == null) {
                    focusWatchNext();
                } else {
                    focusTile(pos >= 0 ? pos : returnSlot);
                }
            } else focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        endReorder();   // Home or another app while reordering keeps the order so far
        stopNavHolding();
        WeatherManager.unregisterListener(homeWeatherListener);
        wallpaperPlayer.stop();
        stopAmbientAnimation();
        heroName.animate().cancel();
        if (ambient != null) ambient.animate().cancel();
        handler.removeCallbacks(minuteTick);
        stopStats();
        if (diya != null) diya.stop();
        cancelPendingFocusScroll();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        if (airPodsDetector != null) {
            airPodsDetector.stopScanning();
        }
        dismissAirPodsHud();
        dismissShareHud();
        dismissAirPlayHud();
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(packageReceiver);
        rebuildOnResume = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (libraryFocusPending && appLibrary == null) focusLibrary();
            showCardPrompt();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (airPodsDetector != null) {
            airPodsDetector.destroy();
            airPodsDetector = null;
        }
        if (mangoShareServer != null) {
            mangoShareServer.stop();
            mangoShareServer = null;
        }
        if (mangoAirPlayManager != null) {
            mangoAirPlayManager.stop();
            mangoAirPlayManager = null;
        }
        stopMangoRemote();
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        if (watchNextObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(watchNextObserver);
            } catch (Exception ignored) {}
            watchNextObserver = null;
        }
        watchNextExecutor.shutdown();
        wallpaperPlayer.close();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                open(settingsKeyPanel ? new Intent(this, QuickSettingsActivity.class)
                        : systemIntent(Settings.ACTION_SETTINGS));
            }
            return true;
        }
        return false;
    }

    // ---- reorder ---------------------------------------------------------------------------------

    /** Blue the glass turns while reorder mode is on. */
    private static final int REORDER_ACCENT = 0xFF6EC1FF;

    private void updateHomeWeather(int temp, int weatherCode, String condition) {
        if (homeWeatherIcon != null) {
            int iconRes = WeatherManager.getCurrentWeatherIconRes();
            homeWeatherIcon.setImageResource(iconRes);
        }
        if (homeWeatherTemp != null) {
            homeWeatherTemp.setText(temp + "°");
        }
    }

    private void startNavHolding(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return;
        }
        heldNavKeyCode = keyCode;
        navHoldStartTime = System.currentTimeMillis();
        navHoldStep = 0;
        isNavHolding = true;
        handler.removeCallbacks(navAccelerationRunnable);
        handler.postDelayed(navAccelerationRunnable, 260L);
    }

    private void stopNavHolding() {
        isNavHolding = false;
        heldNavKeyCode = 0;
        navHoldStep = 0;
        handler.removeCallbacks(navAccelerationRunnable);
    }

    private final Runnable navAccelerationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isNavHolding || heldNavKeyCode == 0) return;

            if (!canNavigate(heldNavKeyCode)) {
                stopNavHolding();
                return;
            }

            View beforeFocus = getCurrentFocus();

            isFastNavigating = true;
            isSyntheticNav = true;
            try {
                KeyEvent synthDown = new KeyEvent(System.currentTimeMillis(), System.currentTimeMillis(),
                        KeyEvent.ACTION_DOWN, heldNavKeyCode, 0);
                dispatchKeyEvent(synthDown);
                KeyEvent synthUp = new KeyEvent(System.currentTimeMillis(), System.currentTimeMillis(),
                        KeyEvent.ACTION_UP, heldNavKeyCode, 0);
                dispatchKeyEvent(synthUp);
            } finally {
                isSyntheticNav = false;
                isFastNavigating = false;
            }

            View afterFocus = getCurrentFocus();
            // If focus didn't move at all, or if we've hit a boundary, stop holding immediately!
            if (beforeFocus == afterFocus || !canNavigate(heldNavKeyCode)) {
                stopNavHolding();
                return;
            }

            navHoldStep++;
            long holdDuration = System.currentTimeMillis() - navHoldStartTime;

            // Only play click sound when navigation actually moved to the next item
            playAppleClickSoundAccelerated(navHoldStep);

            long nextDelay = calculateHoldDelay(holdDuration, navHoldStep);
            handler.postDelayed(this, nextDelay);
        }
    };

    private static long calculateHoldDelay(long durationMs, int step) {
        if (durationMs > 2000 || step > 14) return 40L;  // Maximum speed
        if (durationMs > 1200 || step > 8)  return 55L;  // High speed
        if (durationMs > 600  || step > 4)  return 85L;  // Medium-high
        if (durationMs > 300  || step > 2)  return 120L; // Medium
        return 160L;                                     // Initial acceleration
    }

    private void playAppleClickSoundAccelerated(int step) {
        if (soundPool != null && soundLoaded && clickSoundId != 0) {
            float rate = Math.min(1.45f, 1.0f + (step * 0.025f));
            soundPool.play(clickSoundId, 1.0f, 1.0f, 1, 0, rate);
        }
    }

    /**
     * Reorder mode (top-bar button). While it is on, every key comes here first:
     * - nothing carried: arrows move focus as usual; OK on a tile picks it up instead of opening the app;
     *   Back ends the mode and saves the order.
     * - a tile carried: arrows move it; Back (or OK) puts it down and the mode stays on for the next one.
     * Other keys (volume, Home) pass through; leaving the launcher ends the mode and saves.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isSyntheticNav) {
            if (handleSettingsKey(event)) return true;
            if (!reordering) return super.dispatchKeyEvent(event);
            if (dockEditing) return dispatchDockEdit(event);
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            int code = event.getKeyCode();
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
            if (movingKey != null) {
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (down) moveBy(code);
                        return true;
                    default:
                        if (ok || back) {
                            if (!down) drop();
                            return true;
                        }
                        return super.dispatchKeyEvent(event);
                }
            }
            if (back) {
                if (!down) endReorder();
                return true;
            }
            int pos = focusedTilePosition();
            if (ok && pos >= 0) {
                if (!down) pickUp(pos);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        // If AirPlay Photo is visible, intercept Back key to close photo
        if (airPlayPhotoView != null && airPlayPhotoView.getVisibility() == View.VISIBLE) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    airPlayPhotoView.setVisibility(View.GONE);
                }
                return true;
            }
        }

        // If Unified Mango Connectivity dialog is visible, intercept Back key to safely decline/dismiss
        if (connectivityCoordinator != null && connectivityCoordinator.isDialogVisible()) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    connectivityCoordinator.handleBackPressed();
                }
                return true;
            }
        }

        // If Mango Share HUD is visible, intercept Back key to cleanly decline/dismiss it
        if (shareHud != null && shareHud.getVisibility() == View.VISIBLE) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (currentShareSession != null && !currentShareSession.isFinished) {
                        currentShareSession.decline();
                    }
                    dismissShareHud();
                }
                return true;
            }
        }

        // If AirPods HUD is visible, intercept Back key to cleanly dismiss it
        if (airPodsHud != null && airPodsHud.getVisibility() == View.VISIBLE) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    dismissAirPodsHud();
                }
                return true;
            }
        }

        int action = event.getAction();
        int keyCode = event.getKeyCode();

        if (isNavKey(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    stopNavHolding();
                    if (canNavigate(keyCode)) {
                        playAppleClickSound();
                        startNavHolding(keyCode);
                    }
                } else {
                    // System hardware key-repeat tick: consumed so our custom non-linear acceleration ticker drives motion
                    return true;
                }
            } else if (action == KeyEvent.ACTION_UP) {
                stopNavHolding();
            }
        }

        if (handleSettingsKey(event)) return true;
        if (!reordering) return super.dispatchKeyEvent(event);
        if (dockEditing) return dispatchDockEdit(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (movingKey != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveBy(code);
                    return true;
                default:
                    if (ok || back) {
                        if (!down) drop();
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
            }
        }
        if (back) {
            if (!down) endReorder();
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) pickUp(pos);
            return true;   // never opens the app while reordering
        }
        return super.dispatchKeyEvent(event);   // arrows navigate; OK on a top-bar pill works as usual
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        return grid.getChildAdapterPosition((View) f.getParent());
    }

    /** The reorder button: turns the mode on (focus goes back to the last tile), or off again. */
    private void toggleReorder() {
        if (reordering) {
            endReorder();
            return;
        }
        if (!beginReorder()) return;
        focusTile(Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1)));
        Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
    }

    /** The start shared by reorder and edit-dock mode. False when there is nothing to move. */
    private boolean beginReorder() {
        if (adapter.movableCount() < 2) return false;
        adapter.setReordering(true);
        if (adapter.hasEmptySlots()) reloadTiles();   // every position must hold a tile that can move
        reordering = true;
        orderAtStart = adapter.movableKeys();
        glass.setAccent(REORDER_ACCENT);
        btnReorder.setActivated(true);
        return true;
    }

    /** Edit dock (Settings menu): focus goes to the first dock place, which OK then chooses. */
    private void startDockEdit() {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        dockEditSlot = -1;
        focusTile(0);
        Toast.makeText(this, R.string.dock_edit_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * Keys in edit-dock mode. First OK on a dock tile chooses that place (the other dock tiles dim) and focus moves
     * to the grid; then OK on any tile swaps that app into the place, saves the order and ends the mode. Back goes
     * one step back: from choosing the app to choosing the place, and from there out of the mode.
     */
    private boolean dispatchDockEdit(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (back) {
            if (!down) {
                if (dockEditSlot >= 0) {
                    int slot = dockEditSlot;
                    dockEditSlot = -1;
                    dimOthers();
                    focusTile(slot);
                } else {
                    endReorder();
                }
            }
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) {
                int n = adapter.movableCount();
                if (dockEditSlot < 0) {
                    if (pos < Math.min(COLUMNS, n)) {
                        dockEditSlot = pos;
                        dimOthers();
                        focusTile(Math.min(COLUMNS, n - 1));
                        Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
                    }
                } else if (pos < n) {
                    swapIntoDock(dockEditSlot, pos);
                }
            }
            return true;   // never opens the app while editing
        }
        return super.dispatchKeyEvent(event);   // arrows navigate as usual
    }

    /** Puts the app at pos into dock place slot, and the app that was there where the chosen one was; then saves. */
    private void swapIntoDock(int slot, int pos) {
        String slotKey = adapter.keyAt(slot);
        if (pos != slot) {
            keepScroll(() -> {
                adapter.move(pos, slot);
                adapter.move(adapter.positionOf(slotKey), pos);
            });
        }
        endReorder();
        refocusAfterLayout(slot);
    }

    private void pickUp(int pos) {
        if (pos >= adapter.movableCount()) return;   // the "Default home" tile stays put
        movingKey = adapter.keyAt(pos);
        dimOthers();
    }

    /** Puts the carried tile down where it is; the mode stays on. */
    private void drop() {
        movingKey = null;
        dimOthers();
    }

    /** While a tile is carried, every other tile is dimmed so the carried one stands out; otherwise all are full. */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    /** A tile's alpha: dim unless it is the carried one, or, in edit-dock mode, a dock tile other than the chosen place. */
    private float tileAlpha(int pos) {
        if (movingKey != null) {
            return pos != RecyclerView.NO_POSITION && movingKey.equals(adapter.keyAt(pos)) ? 1f : 0.45f;
        }
        if (dockEditSlot >= 0 && pos >= 0 && pos < COLUMNS && pos != dockEditSlot) return 0.45f;
        return 1f;
    }

    /** Ends the mode and saves the order if it changed since the mode started. */
    private void endReorder() {
        if (!reordering) return;
        dockEditing = false;
        dockEditSlot = -1;
        drop();
        reordering = false;
        adapter.setReordering(false);
        glass.setAccent(festive ? festival.accentColor() : 0);
        btnReorder.setActivated(false);
        List<String> keys = adapter.movableKeys();
        List<String> before = orderAtStart;
        orderAtStart = null;
        if (!keys.equals(before)) {
            Order order = new Order(Order.dockKey(dockApps), keys);
            if (Order.save(Order.file(this), order)) {
                adapter.setSavedOrder(Order.ranks(order, dockApps));
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
        if (fillDockLeavesSlots) reloadTiles();   // the empty dock slots left out while reordering come back
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            drop();
            return;
        }
        int to;
        // rtl_app_grid (Hebrew) mirrors the grid: position 0 is on the right, so Left moves towards the end.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = rtlAppGrid ? from + 1 : from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = rtlAppGrid ? from - 1 : from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - COLUMNS;
        else if (from + COLUMNS < n) to = from + COLUMNS;
        else to = from / COLUMNS < (n - 1) / COLUMNS ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        keepScroll(() -> adapter.move(from, to));
        refocusAfterLayout(to);
    }

    /**
     * Applies an adapter change without the grid jumping. After a move the layout manager anchors on the focused
     * tile, keeping it at its old place on screen and shifting every other row; pinning the first visible row at its
     * current offset as the next layout's anchor keeps the scroll where it was. onTileFocused still scrolls
     * afterwards if the moved tile itself ends up off screen.
     */
    private void keepScroll(Runnable change) {
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        int offset = firstView != null ? lm.getDecoratedTop(firstView) - grid.getPaddingTop() : 0;
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /**
     * Once the grid has applied the change, put the glass (and focus, if the move dropped it) on the tile at pos.
     * The grid has a fixed size, so it applies adapter changes on the next animation frame rather than in a layout
     * pass; until then it cannot find the tile by position. Wait frame by frame (at most 10) for that. No focus
     * change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();   // views that came into view or were rebound during the move
            }
        });
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onLibraryRequested() {
        if (appLibrary != null) appLibrary.close();
        if (openPopup != null) openPopup.dismiss();
        libraryFocusPending = false;
        appLibrary = new AppLibraryDialog(this, animations, reducedMotion, adapter::containsPackage, () -> reloadApps(false), () -> {
            appLibrary = null;
            openPopup = null;
            libraryFocusPending = true;
            grid.post(() -> {
                if (libraryFocusPending && appLibrary == null && hasWindowFocus() && !isFinishing()) focusLibrary();
            });
        });
        openPopup = appLibrary.popup;
        appLibrary.show();
    }

    @Override
    public void onTileFocused(int position, View card) {
        focusedWatchNextCard = null;
        lastFocusedTileCard = card;
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        setStageForApp(position);
        updateTileGlass(card, position, true);

        View item = (View) card.getParent();
        if (dockRow && position < COLUMNS) {
            showHero(position, card.getTop());
        } else {
            // A plain grid (dock_row false) has no dock to carry the glow, so it follows every tile there.
            if (dockRow) hideHero();
            else showGlowOnly(position);
        }
    }

    private void updateTileGlass(View card, int position, boolean spring) {
        if (card == null || root == null || glass == null || !card.isAttachedToWindow()
                || !card.isFocused()) {
            hideGlass();
            return;
        }

        if (card.getWidth() == 0 || card.getHeight() == 0) {
            // View is not laid out yet — wait for layout pass instead of prematurely hiding glass
            card.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    card.removeOnLayoutChangeListener(this);
                    if (card.isAttachedToWindow() && card.isFocused()) {
                        updateTileGlass(card, position, false);
                    }
                }
            });
            card.post(() -> {
                if (card.isAttachedToWindow() && card.isFocused() && card.getWidth() > 0) {
                    updateTileGlass(card, position, false);
                }
            });
            return;
        }

        float pad = 6f * density;
        float scale = adapter != null ? adapter.focusScale() : 1.10f;
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float radius = AppAdapter.cornerRadius(card.getWidth(), density) * scale;
        int accent = festive ? festival.accentColor() : 0;

        if (spring) {
            glideGlassTo(card, w, h, scale, radius, accent, false);
        } else {
            snapGlassTo(card, w, h, scale, radius, accent, false);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    // ---- top shelf & ambient gradient (disabled per user requirement so wallpapers are never masked) ----

    private void showHero(int position, float dockTopInRoot) {
        // Disabled: do not overlay any gradient wallpaper when dock card is selected
    }

    private void setAmbient(int color) {
        // Disabled
    }

    private void transitionAmbient(int color) {
        // Disabled
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    private void showGlowOnly(int position) {
        // Disabled: do not overlay any gradient wallpaper when dock card is selected
    }

    private void hideHero() {
        // Disabled
    }

    /** Returns true if {@code child} is the same view as {@code parent} or is contained within it. */
    private static boolean isDescendantOf(View child, View parent) {
        View v = child;
        while (v != null) {
            if (v == parent) return true;
            if (!(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
        return false;
    }

    private void hideGlass() {
        currentFocusedCard = null;
        if (glassGlideAnimator != null) {
            glassGlideAnimator.cancel();
            glassGlideAnimator = null;
        }
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations) {
            glass.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                glass.setScaleX(1.0f);
                glass.setScaleY(1.0f);
            }).start();
        } else {
            glass.setAlpha(0f);
            glass.setScaleX(1.0f);
            glass.setScaleY(1.0f);
        }
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        cpuTotalPrev = -1;
        idlePrev = -1;
        procStatDead = false;
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler, held by its task: stopStats on the main thread can clear the field
        // at any moment without the reader tripping over it.
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine();
                handler.post(() -> {
                    if (showStats && stats != null && statsBg == bg) stats.setText(line);
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  ·  Memory 430 MB free  ·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  ·  ");
        line.append(part);
    }

    private String readStatsLine() {
        int cpu = cpuPercent();
        if (cpu < 0) {
            try {
                Thread.sleep(200);             // second sample, so the first line already has a CPU figure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            cpu = cpuPercent();
        }
        // Android 8 and later hide /proc/stat from apps on many TVs: then the line leaves CPU out rather than
        // showing a dash that never fills in.
        StringBuilder line = new StringBuilder();
        if (cpu >= 0) line.append(getString(R.string.stats_cpu, cpu));
        long mem = memAvailableMb();
        if (mem >= 0) appendStat(line, getString(R.string.stats_memory, mem));
        String free = formatGb(Environment.getDataDirectory().getFreeSpace());
        if (!free.isEmpty()) appendStat(line, getString(R.string.stats_storage, free));
        return line.toString();
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    private int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    private int cpuFromProcStat() {
        String s = readFirstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return CPU_UNREADABLE;
        try {
            long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
            long total = user + nice + sys + idle + iowait + irq + soft;
            long idleAll = idle + iowait;
            if (cpuTotalPrev < 0) {
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                return -1;
            }
            long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            if (dt <= 0) return -1;
            int busy = (int) Math.round((dt - di) * 100.0 / dt);
            return Math.max(0, Math.min(100, busy));
        } catch (NumberFormatException e) {
            return CPU_UNREADABLE;
        }
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                        + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    private long memAvailableMb() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    String[] t = line.split("\\s+");
                    return t.length >= 2 ? Long.parseLong(t[1]) / 1024 : -1;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return -1;
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? String.format(Locale.US, "%.0f GB", gb)
                : String.format(Locale.US, "%.1f GB", gb);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.appCount();
        int target;
        // Horizontal: move directly across the row with precision, and swallow the key at a row's ends
        // so focus never wraps to the next row, gets stuck in FocusFinder, or escapes up into the top bar.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean isLeft = (keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
            boolean forward = rtlAppGrid ? isLeft : !isLeft;
            if (forward && position == adapter.focusablePosition(count - 1) && adapter.isLibraryAt(count)) {
                focusLibrary();
                return true;
            }
            int rowCount = position < COLUMNS ? Math.min(count, adapter.dockFilled()) : count;
            if (GridNavigation.atHorizontalEdge(position, rowCount, COLUMNS, rtlAppGrid, isLeft)) {
                return true; // At physical row edge — stop navigation here!
            }
            int step = forward ? 1 : -1;
            int targetPos = position + step;
            if (targetPos >= 0 && targetPos < count) {
                focusTile(targetPos);
                return true;
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) {
                // If Mac Dock has apps, move focus back to the Dock row
                if (!dockAppList.isEmpty() && dockPill != null && dockPill.getChildCount() > 0) {
                    int dockTarget = Math.min(position % COLUMNS, dockAppList.size() - 1);
                    View dockTile = dockAppList.get(dockTarget).tileView;
                    if (dockTile != null) {
                        setStageDockFocused();
                        if (!dockTile.requestFocus()) {
                            dockTile.post(dockTile::requestFocus);
                        }
                        return true;
                    }
                }
                if (watchNextList != null && watchNextList.getVisibility() == View.VISIBLE && watchNextAdapter != null && watchNextAdapter.getItemCount() > 0) {
                    setStageWatchNext();
                    int targetCw = Math.min(position, watchNextAdapter.getItemCount() - 1);
                    focusWatchNext(targetCw);
                    return true;
                }
                return false;          // dock row: let focus move to the top bar
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        return true;
    }

    @Override public void onLibraryFocused() {
        libraryFocusPending = false;
        hideGlass();
        hideHero();
    }

    @Override public void onLibraryUp() {
        if (adapter.appCount() == 0) { btnWifi.requestFocus(); return; }
        int lastRow = ((adapter.appCount() - 1) / COLUMNS) * COLUMNS;
        focusTile(Math.min(lastRow + lastFocused % COLUMNS, adapter.appCount() - 1));
    }

    private void focusLibrary() {
        cancelPendingFocusScroll();
        int position = adapter.appCount();
        if (!adapter.isLibraryAt(position)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View button = holder.itemView.findViewById(R.id.btnLibrary);
            Rect visible = new Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() >= button.getHeight()) {
                if (button.requestFocus()) libraryFocusPending = false;
                return;
            }
        }
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                if (appLibrary != null || !hasWindowFocus()) return;
                RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
                if (footer != null && adapter.isLibraryAt(adapter.appCount())
                        && footer.itemView.findViewById(R.id.btnLibrary).requestFocus()) libraryFocusPending = false;
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder footer = rv.findViewHolderForAdapterPosition(position);
                if (footer != null && footer.itemView.findViewById(R.id.btnLibrary).requestFocus())
                    libraryFocusPending = false;
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(position);
    }

    private void cancelPendingFocusScroll() {
        if (pendingFocusScroll != null) {
            grid.removeOnScrollListener(pendingFocusScroll);
            pendingFocusScroll = null;
            grid.stopScroll();
        }
    }

    private void focusTile(int position) {
        libraryFocusPending = false;
        cancelPendingFocusScroll();
        if (adapter.appCount() == 0) {
            if (adapter.isLibraryAt(0)) focusLibrary();
            else btnWifi.requestFocus();
            return;
        }
        final int pos = adapter.focusablePosition(Math.max(0, Math.min(position, adapter.appCount() - 1)));
        setStageForApp(pos);
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null && vh.itemView != null) {
            View card = vh.itemView.findViewById(R.id.card);
            if (card != null) {
                if (card.isFocused()) onTileFocused(pos, card);
                else card.requestFocus();
                return;
            }
        }
        grid.scrollToPosition(pos);
        grid.post(() -> {
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
            if (h != null && h.itemView != null) {
                View card = h.itemView.findViewById(R.id.card);
                if (card != null) {
                    if (card.isFocused()) onTileFocused(pos, card);
                    else card.requestFocus();
                    return;
                }
            }
            grid.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    grid.removeOnLayoutChangeListener(this);
                    grid.post(() -> {
                        RecyclerView.ViewHolder h2 = grid.findViewHolderForAdapterPosition(pos);
                        if (h2 != null && h2.itemView != null) {
                            View card = h2.itemView.findViewById(R.id.card);
                            if (card != null) {
                                if (card.isFocused()) onTileFocused(pos, card);
                                else card.requestFocus();
                            }
                        }
                    });
                }
            });
        });
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        String festiveGreeting = festive ? festival.greetingText() : "";
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            greeting.setText(greetingName.isEmpty() ? getString(res)
                    : getString(R.string.greeting_with_name, getString(res), greetingName));
            greeting.setTextColor(greetingColor);
        }
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        // The diya's flame: 0 is out (offline), 1 to 4 follow the Wi-Fi signal, Ethernet burns steady at 4.
        int flame = 0;
        boolean steady = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
                flame = 4;
                steady = true;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                flame = info != null ? Math.max(1, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5))) : 3;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            text = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            btnWifi.setText(label);
        } else {
            btnWifi.setText(text);
        }
        if (festive && diya != null) {
            if (online && flame == 0) flame = 3;   // online some other way (cellular, USB tethering)
            diya.setSteady(steady);
            diya.setFlame(flame);
        } else {
            btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        }
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        bell = new BellDrawable(density);
        marigold = new MarigoldDrawable(density);
        diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (isFinishing() || isDestroyed()) return;
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    private static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        open(i != null ? i : systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Inputs hidden in Settings > Inputs are left out of the menu, so they don't count here either.
                if (info.isPassthroughInput() && !info.isHidden(this)) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                CharSequence custom = info.loadCustomLabel(this);
                if (custom != null && custom.length() > 0 && !custom.toString().equals(label)) {
                    label = custom + " \u00B7 " + label;
                }
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId),
                            "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
            if (tuned.resolveActivity(getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * density))));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** Keep a normal Settings click for the TV; the launcher's choices live behind hold OK. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        addSettingsMenuRow(rows, popup, R.string.launcher_settings, () -> startActivityForResult(
                new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        if (moduleCards) addSettingsMenuRow(rows, popup, R.string.module_cards, this::showModuleCards);
        if (dockRow) addSettingsMenuRow(rows, popup, R.string.dock_edit, this::startDockEdit);
        addSettingsMenuRow(rows, popup, R.string.reorder, this::toggleReorder);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // Hebrew puts Settings at the left of the header: grow the menu inward from it.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()
                || reordering) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }

    // ---- AirPods Detection & Apple TV HUD ----------------------------------------------------

    private void initAirPodsViews() {
        airPodsHud = findViewById(R.id.airPodsHud);
        if (airPodsHud == null) return;

        airPodsIcon = findViewById(R.id.airPodsIcon);
        airPodsTitle = findViewById(R.id.airPodsTitle);
        airPodsStatus = findViewById(R.id.airPodsStatus);
        airPodsLeftBattery = findViewById(R.id.airPodsLeftBattery);
        airPodsLeftBolt = findViewById(R.id.airPodsLeftBolt);
        airPodsCaseBattery = findViewById(R.id.airPodsCaseBattery);
        airPodsCaseBolt = findViewById(R.id.airPodsCaseBolt);
        airPodsRightBattery = findViewById(R.id.airPodsRightBattery);
        airPodsRightBolt = findViewById(R.id.airPodsRightBolt);

        airPodsBtnConnect = findViewById(R.id.airPodsBtnConnect);
        airPodsBtnAudio = findViewById(R.id.airPodsBtnAudio);
        airPodsBtnDismiss = findViewById(R.id.airPodsBtnDismiss);

        View.OnFocusChangeListener btnFocusListener = (v, focused) -> {
            if (focused) {
                updateAirPodsButtonGlass(v, true);
            }
        };

        if (airPodsBtnConnect != null) {
            airPodsBtnConnect.setOnFocusChangeListener(btnFocusListener);
            airPodsBtnConnect.setOnClickListener(v -> connectAirPods(currentAirPodsDevice));
        }
        if (airPodsBtnAudio != null) {
            airPodsBtnAudio.setOnFocusChangeListener(btnFocusListener);
            airPodsBtnAudio.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent("android.settings.BLUETOOTH_SETTINGS"));
                    } catch (Exception ignored) {}
                }
                dismissAirPodsHud();
            });
        }
        if (airPodsBtnDismiss != null) {
            airPodsBtnDismiss.setOnFocusChangeListener(btnFocusListener);
            airPodsBtnDismiss.setOnClickListener(v -> dismissAirPodsHud());
        }
    }

    private void initAirPodsDetector() {
        try {
            airPodsDetector = new AirPodsDetector(this);
            airPodsDetector.setListener(new AirPodsDetector.Listener() {
                @Override
                public void onAirPodsLidOpened(AirPodsDevice device) {
                    SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
                    if (!prefs.getBoolean("mango_prompts_enabled", true) || !prefs.getBoolean("mango_buds_enabled", true)) return;
                    if (connectivityCoordinator != null) {
                        connectivityCoordinator.showBudsPrompt(device);
                    } else {
                        showAirPodsHud(device);
                    }
                }

                @Override
                public void onAirPodsStatusUpdated(AirPodsDevice device) {
                    if (connectivityCoordinator != null) {
                        connectivityCoordinator.updateBudsStatus(device);
                    } else if (airPodsHud != null && airPodsHud.getVisibility() == View.VISIBLE) {
                        updateAirPodsHudData(device);
                    }
                }

                @Override
                public void onAirPodsLidClosed(AirPodsDevice device) {
                    // Closed state handled gracefully by timeout
                }
            });
        } catch (Throwable t) {
            Log.w("MainActivity", "AirPods detector init failed", t);
        }
    }

    private void showAirPodsHud(AirPodsDevice device) {
        if (airPodsHud == null || device == null) return;
        currentAirPodsDevice = device;
        updateAirPodsHudData(device);

        if (airPodsHud.getVisibility() != View.VISIBLE) {
            airPodsHud.setVisibility(View.VISIBLE);
            airPodsHud.setAlpha(0f);
            airPodsHud.setTranslationY(-24f * density);
            airPodsHud.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();

            preAirPodsFocusedView = getCurrentFocus();
            if (airPodsBtnConnect != null) {
                airPodsBtnConnect.post(() -> {
                    airPodsBtnConnect.requestFocus();
                    updateAirPodsButtonGlass(airPodsBtnConnect, true);
                });
            }
        }

        if (airPodsAutoDismissRunnable != null) {
            handler.removeCallbacks(airPodsAutoDismissRunnable);
        }
        airPodsAutoDismissRunnable = this::dismissAirPodsHud;
        handler.postDelayed(airPodsAutoDismissRunnable, 12000L);
    }

    private void updateAirPodsHudData(AirPodsDevice device) {
        if (device == null) return;
        if (airPodsTitle != null) airPodsTitle.setText(device.modelName);
        if (airPodsStatus != null) {
            airPodsStatus.setText(device.lidOpen ? "Lid Open • Nearby" : "In Range");
        }

        if (airPodsLeftBattery != null) {
            airPodsLeftBattery.setText(device.leftBattery >= 0 ? (device.leftBattery + "%") : "--%");
        }
        if (airPodsLeftBolt != null) {
            airPodsLeftBolt.setVisibility(device.isLeftCharging ? View.VISIBLE : View.GONE);
        }

        if (airPodsCaseBattery != null) {
            airPodsCaseBattery.setText(device.caseBattery >= 0 ? (device.caseBattery + "%") : "--%");
        }
        if (airPodsCaseBolt != null) {
            airPodsCaseBolt.setVisibility(device.isCaseCharging ? View.VISIBLE : View.GONE);
        }

        if (airPodsRightBattery != null) {
            airPodsRightBattery.setText(device.rightBattery >= 0 ? (device.rightBattery + "%") : "--%");
        }
        if (airPodsRightBolt != null) {
            airPodsRightBolt.setVisibility(device.isRightCharging ? View.VISIBLE : View.GONE);
        }
    }

    private void dismissAirPodsHud() {
        if (airPodsHud == null || airPodsHud.getVisibility() != View.VISIBLE) return;
        if (airPodsAutoDismissRunnable != null) {
            handler.removeCallbacks(airPodsAutoDismissRunnable);
            airPodsAutoDismissRunnable = null;
        }

        airPodsHud.animate()
                .alpha(0f)
                .translationY(-20f * density)
                .setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    airPodsHud.setVisibility(View.GONE);
                    focusedAirPodsButton = null;
                    if (preAirPodsFocusedView != null && preAirPodsFocusedView.isAttachedToWindow()) {
                        preAirPodsFocusedView.requestFocus();
                        updateCurrentFocusGlass(true);
                    } else if (dockPill != null) {
                        View dockTile = getFocusedDockTile();
                        if (dockTile != null) {
                            dockTile.requestFocus();
                            updateDockGlass(dockTile, true);
                        }
                    }
                })
                .start();
    }

    private void updateAirPodsButtonGlass(View button, boolean spring) {
        if (button == null || root == null || glass == null || !button.isAttachedToWindow() || !button.isFocused()) {
            return;
        }
        // User is actively navigating the AirPods HUD: extend auto-dismiss timer
        if (airPodsAutoDismissRunnable != null) {
            handler.removeCallbacks(airPodsAutoDismissRunnable);
            handler.postDelayed(airPodsAutoDismissRunnable, 15000L);
        }
        focusedAirPodsButton = button;
        float w = button.getWidth();
        float h = button.getHeight();
        float radius = 14f * density;
        int accent = 0;
        if (spring) {
            glideGlassTo(button, w, h, 1.0f, radius, accent, false);
        } else {
            snapGlassTo(button, w, h, 1.0f, radius, accent, false);
        }
    }

    private void connectAirPods(AirPodsDevice airPods) {
        if (airPods == null) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception ignored) {}
            return;
        }

        BluetoothDevice targetDev = null;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice dev : bonded) {
                    if (dev.getAddress() != null && dev.getAddress().equalsIgnoreCase(airPods.address)) {
                        targetDev = dev;
                        break;
                    }
                }
                if (targetDev == null) {
                    for (BluetoothDevice dev : bonded) {
                        String name = dev.getName();
                        if (name != null && name.toLowerCase().contains("airpods")) {
                            targetDev = dev;
                            break;
                        }
                    }
                }
            }
        } catch (SecurityException ignored) {}

        if (targetDev != null) {
            final BluetoothDevice devToConnect = targetDev;
            if (airPodsBtnConnect != null) {
                airPodsBtnConnect.setText("Connecting...");
            }
            adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP) {
                        try {
                            Method connectMethod = proxy.getClass().getMethod("connect", BluetoothDevice.class);
                            connectMethod.invoke(proxy, devToConnect);
                            if (airPodsBtnConnect != null) {
                                airPodsBtnConnect.setText("Connected");
                            }
                        } catch (Exception e) {
                            try {
                                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                            } catch (Exception ignored) {}
                        }
                        handler.postDelayed(() -> {
                            try {
                                adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                            } catch (Exception ignored) {}
                            dismissAirPodsHud();
                        }, 1200L);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {}
            }, BluetoothProfile.A2DP);
        } else {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception e) {
                try {
                    startActivity(new Intent("android.settings.BLUETOOTH_SETTINGS"));
                } catch (Exception ignored) {}
            }
            dismissAirPodsHud();
        }
    }

    // ---- Mango TV Ecosystem (Share, AirPlay & Buds) -----------------------------------------------

    private void initConnectivityCoordinator() {
        View overlayRoot = findViewById(R.id.connectivityOverlayRoot);
        View statusPill = findViewById(R.id.connectivityStatusPill);
        if (overlayRoot == null) return;

        connectivityCoordinator = new MangoConnectivityCoordinator(this, overlayRoot, statusPill);
        connectivityCoordinator.setListener(new MangoConnectivityCoordinator.Listener() {
            @Override
            public void onConnectBuds(AirPodsDevice device) {
                connectAirPods(device);
            }

            @Override
            public void onDeclineBuds(AirPodsDevice device) {
                Log.d("MainActivity", "Declined AirPods connection: " + device.modelName);
            }

            @Override
            public void onAcceptShare(MangoShareServer.TransferSession session) {
                if (session != null) session.accept();
            }

            @Override
            public void onDeclineShare(MangoShareServer.TransferSession session) {
                if (session != null) session.decline();
            }

            @Override
            public void onCancelShare(MangoShareServer.TransferSession session) {
                if (session != null) session.decline();
            }

            @Override
            public void onPlayHandoffConfirmed(PlayHandoffRequest request) {
                if (request != null && request.streamUrl != null) {
                    playMediaStream(request.streamUrl, request.positionMs);
                }
            }

            @Override
            public void onPlayHandoffDismissed(PlayHandoffRequest request) {
                Log.d("MainActivity", "Media handoff dismissed: " + request.title);
            }

            @Override
            public void playNavSound() {
                playAppleClickSound();
            }
        });
    }

    private void playMediaStream(String videoUrl, long startPositionMs) {
        playAirPlayVideo(videoUrl, startPositionMs / 1000f);
    }

    private void initShareViews() {
        shareHud = findViewById(R.id.shareHud);
        if (shareHud == null) return;
        shareHudTitle = findViewById(R.id.shareHudTitle);
        shareHudStatus = findViewById(R.id.shareHudStatus);
        shareSenderDetails = findViewById(R.id.shareSenderDetails);
        shareProgressBar = findViewById(R.id.shareProgressBar);
        shareButtonsRow = findViewById(R.id.shareButtonsRow);
        btnShareAccept = findViewById(R.id.btnShareAccept);
        btnShareDecline = findViewById(R.id.btnShareDecline);

        View.OnFocusChangeListener focusListener = (v, focused) -> {
            if (focused) updateShareButtonGlass(v, true);
        };

        if (btnShareAccept != null) {
            btnShareAccept.setOnFocusChangeListener(focusListener);
            btnShareAccept.setOnClickListener(v -> {
                if (currentShareSession != null) {
                    currentShareSession.accept();
                    if (shareButtonsRow != null) shareButtonsRow.setVisibility(View.GONE);
                    if (shareProgressBar != null) {
                        shareProgressBar.setVisibility(View.VISIBLE);
                        shareProgressBar.setProgress(0);
                    }
                    if (shareHudStatus != null) shareHudStatus.setText("Receiving Files...");
                    hideGlass();
                }
            });
        }
        if (btnShareDecline != null) {
            btnShareDecline.setOnFocusChangeListener(focusListener);
            btnShareDecline.setOnClickListener(v -> {
                if (currentShareSession != null) {
                    currentShareSession.decline();
                }
                dismissShareHud();
            });
        }
    }

    private void initAirPlayViews() {
        airPlayHud = findViewById(R.id.airPlayHud);
        airPlayHudTitle = findViewById(R.id.airPlayHudTitle);
        airPlayHudStatus = findViewById(R.id.airPlayHudStatus);
        airPlayPhotoView = findViewById(R.id.airPlayPhotoView);
        if (airPlayPhotoView != null) {
            airPlayPhotoView.setOnClickListener(v -> airPlayPhotoView.setVisibility(View.GONE));
        }
    }

    private void initMangoEcosystem() {
        refreshMangoEcosystem();
    }

    private void refreshMangoEcosystem() {
        SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        long clearedTime = prefs.getLong("cooldowns_cleared_time", 0L);
        if (clearedTime > lastCooldownsClearedTime) {
            lastCooldownsClearedTime = clearedTime;
            if (connectivityCoordinator != null) connectivityCoordinator.clearCooldowns();
        }

        boolean shareEnabled = prefs.getBoolean("mango_share_enabled", true);
        if (shareEnabled && mangoShareServer == null) {
            startMangoShare();
        } else if (!shareEnabled && mangoShareServer != null) {
            stopMangoShare();
        }

        boolean airPlayEnabled = prefs.getBoolean("mango_airplay_enabled", true);
        if (airPlayEnabled && mangoAirPlayManager == null) {
            startMangoAirPlay();
        } else if (!airPlayEnabled && mangoAirPlayManager != null) {
            stopMangoAirPlay();
        }

        boolean budsEnabled = prefs.getBoolean("mango_buds_enabled", true);
        if (budsEnabled && airPodsDetector == null) {
            initAirPodsDetector();
            if (airPodsDetector != null) airPodsDetector.startScanning();
        } else if (!budsEnabled && airPodsDetector != null) {
            airPodsDetector.stopScanning();
            airPodsDetector.destroy();
            airPodsDetector = null;
        } else if (budsEnabled && airPodsDetector != null) {
            airPodsDetector.startScanning();
        }

        boolean remoteEnabled = prefs.getBoolean("mango_remote_enabled", true);
        if (remoteEnabled && mangoRemoteServer == null) {
            startMangoRemote();
        } else if (!remoteEnabled && mangoRemoteServer != null) {
            stopMangoRemote();
        }
    }

    private void startMangoRemote() {
        try {
            if (remotePairingManager == null) {
                remotePairingManager = new MangoRemotePairingManager(this);
            }
            if (remoteKeyInjector == null) {
                remoteKeyInjector = new MangoRemoteKeyInjector(this);
                remoteKeyInjector.setDirectReceiver(new MangoRemoteKeyInjector.RemoteKeyReceiver() {
                    @Override
                    public boolean onDirectKeyEvent(int keyCode) {
                        return dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                                && dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                    }

                    @Override
                    public void onDirectTextEntered(String text) {
                        // Direct text entered
                    }
                });
            }
            mangoRemoteServer = new MangoRemoteServer(this, MangoRemoteProtocol.DEFAULT_PORT, remotePairingManager, remoteKeyInjector);
            mangoRemoteServer.setListener(new MangoRemoteServer.RemoteServerListener() {
                @Override
                public void onServerStarted(int port, String localIp) {
                    Log.i("MainActivity", "Mango Remote server listening on " + localIp + ":" + port);
                }

                @Override
                public void onClientConnected(String clientIp) {
                    runOnUiThread(() -> {
                        if (connectivityCoordinator != null) {
                            connectivityCoordinator.showStatusPill("Mango Remote", "Phone connected", R.drawable.ic_airplay);
                        }
                    });
                }

                @Override
                public void onClientDisconnected(String clientIp) {
                    // Handled gracefully
                }

                @Override
                public void onCommandReceived(String action, JSONObject payload) {
                    // Handled
                }
            });
            mangoRemoteServer.start();
        } catch (Throwable t) {
            Log.e("MainActivity", "Error starting Mango Remote server", t);
        }
    }

    private void stopMangoRemote() {
        if (mangoRemoteServer != null) {
            mangoRemoteServer.stop();
            mangoRemoteServer = null;
        }
    }

    public void startMangoRemotePairing() {
        runOnUiThread(() -> {
            if (remotePairingManager == null) {
                remotePairingManager = new MangoRemotePairingManager(this);
            }
            String pin = remotePairingManager.startPairingSession();
            String localIp = mangoRemoteServer != null ? mangoRemoteServer.getLocalIpAddress() : "127.0.0.1";
            String url = "http://" + localIp + ":" + MangoRemoteProtocol.DEFAULT_PORT;
            if (connectivityCoordinator != null) {
                connectivityCoordinator.showRemotePairingPrompt(pin, url, null);
            }
        });
    }

    private void startMangoShare() {
        try {
            mangoShareServer = new MangoShareServer(this, "Mango TV");
            mangoShareServer.setListener(new MangoShareServer.Listener() {
                @Override
                public void onIncomingTransferRequested(MangoShareServer.TransferSession session) {
                    SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
                    if (!prefs.getBoolean("mango_prompts_enabled", true) || !prefs.getBoolean("mango_share_enabled", true)) {
                        session.decline();
                        return;
                    }
                    if (connectivityCoordinator != null) {
                        connectivityCoordinator.showSharePrompt(session);
                    } else {
                        showShareHud(session);
                    }
                }

                @Override
                public void onTransferProgress(MangoShareServer.TransferSession session, MangoShareServer.FileItem item, long bytesReceived, long totalBytes) {
                    if (connectivityCoordinator != null && totalBytes > 0) {
                        int percent = (int) ((bytesReceived * 100) / totalBytes);
                        connectivityCoordinator.updateShareProgress(percent, bytesReceived, totalBytes);
                    } else if (shareProgressBar != null && totalBytes > 0) {
                        int progress = (int) ((bytesReceived * 100) / totalBytes);
                        shareProgressBar.setProgress(progress);
                    }
                    if (shareSenderDetails != null) {
                        shareSenderDetails.setText(item.fileName + " • " + formatFileSize(bytesReceived) + " / " + formatFileSize(totalBytes));
                    }
                }

                @Override
                public void onTransferComplete(MangoShareServer.TransferSession session) {
                    if (connectivityCoordinator != null) {
                        connectivityCoordinator.onShareComplete();
                    } else {
                        if (shareHudStatus != null) {
                            shareHudStatus.setText("Transfer Complete");
                            shareHudStatus.setTextColor(0xFF34C759);
                        }
                        if (shareSenderDetails != null) {
                            shareSenderDetails.setText("Saved " + session.files.size() + " files to Downloads/MangoShare");
                        }
                        if (shareProgressBar != null) {
                            shareProgressBar.setProgress(100);
                        }
                        handler.postDelayed(MainActivity.this::dismissShareHud, 3500L);
                    }
                }

                @Override
                public void onTransferError(MangoShareServer.TransferSession session, String reason) {
                    if (connectivityCoordinator != null) {
                        connectivityCoordinator.dismissDialog();
                    } else {
                        if (shareHudStatus != null) {
                            shareHudStatus.setText("Transfer Failed");
                            shareHudStatus.setTextColor(0xFFFF3B30);
                        }
                        if (shareSenderDetails != null) {
                            shareSenderDetails.setText(reason);
                        }
                        handler.postDelayed(MainActivity.this::dismissShareHud, 2500L);
                    }
                }
            });
            mangoShareServer.start();
        } catch (Throwable t) {
            Log.w("MainActivity", "Failed to start Mango Share server", t);
        }
    }

    private void stopMangoShare() {
        if (mangoShareServer != null) {
            mangoShareServer.stop();
            mangoShareServer = null;
        }
        dismissShareHud();
    }

    private void showShareHud(MangoShareServer.TransferSession session) {
        if (shareHud == null || session == null) return;
        currentShareSession = session;

        if (shareHudTitle != null) shareHudTitle.setText("Mango Share");
        if (shareHudStatus != null) {
            shareHudStatus.setText("Incoming Transfer");
            shareHudStatus.setTextColor(0xFF007AFF);
        }
        if (shareSenderDetails != null) {
            shareSenderDetails.setText(session.senderAlias + " • " + session.files.size() + " items (" + formatFileSize(session.totalBytes) + ")");
        }
        if (shareButtonsRow != null) shareButtonsRow.setVisibility(View.VISIBLE);
        if (shareProgressBar != null) shareProgressBar.setVisibility(View.GONE);

        if (shareHud.getVisibility() != View.VISIBLE) {
            shareHud.setVisibility(View.VISIBLE);
            shareHud.setAlpha(0f);
            shareHud.setTranslationY(-24f * density);
            shareHud.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();

            preShareFocusedView = getCurrentFocus();
            if (btnShareAccept != null) {
                btnShareAccept.post(() -> {
                    btnShareAccept.requestFocus();
                    updateShareButtonGlass(btnShareAccept, true);
                });
            }
        }

        if (shareAutoDismissRunnable != null) {
            handler.removeCallbacks(shareAutoDismissRunnable);
        }
        shareAutoDismissRunnable = () -> {
            if (currentShareSession != null && !currentShareSession.isFinished) {
                currentShareSession.decline();
            }
            dismissShareHud();
        };
        handler.postDelayed(shareAutoDismissRunnable, 30000L);
    }

    private void dismissShareHud() {
        if (shareHud == null || shareHud.getVisibility() != View.VISIBLE) return;
        if (shareAutoDismissRunnable != null) {
            handler.removeCallbacks(shareAutoDismissRunnable);
            shareAutoDismissRunnable = null;
        }

        shareHud.animate()
                .alpha(0f)
                .translationY(-20f * density)
                .setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    shareHud.setVisibility(View.GONE);
                    focusedShareButton = null;
                    currentShareSession = null;
                    if (preShareFocusedView != null && preShareFocusedView.isAttachedToWindow()) {
                        preShareFocusedView.requestFocus();
                        updateCurrentFocusGlass(true);
                    } else if (dockPill != null) {
                        View dockTile = getFocusedDockTile();
                        if (dockTile != null) {
                            dockTile.requestFocus();
                            updateDockGlass(dockTile, true);
                        }
                    }
                })
                .start();
    }

    private void updateShareButtonGlass(View button, boolean spring) {
        if (button == null || root == null || glass == null || !button.isAttachedToWindow() || !button.isFocused()) {
            return;
        }
        if (shareAutoDismissRunnable != null) {
            handler.removeCallbacks(shareAutoDismissRunnable);
            handler.postDelayed(shareAutoDismissRunnable, 30000L);
        }
        focusedShareButton = button;
        float w = button.getWidth();
        float h = button.getHeight();
        float radius = 14f * density;
        int accent = 0;
        if (spring) {
            glideGlassTo(button, w, h, 1.0f, radius, accent, false);
        } else {
            snapGlassTo(button, w, h, 1.0f, radius, accent, false);
        }
    }

    private void startMangoAirPlay() {
        try {
            mangoAirPlayManager = new MangoAirPlayManager(this, "Mango TV");
            mangoAirPlayManager.setListener(new MangoAirPlayManager.Listener() {
                @Override
                public void onAirPlayClientConnected(String clientIp, String deviceName) {
                    if (connectivityCoordinator != null) {
                        connectivityCoordinator.showStatusPill("AirPlay", deviceName + " connected", R.drawable.ic_airplay);
                    } else {
                        showAirPlayHud("Connected", deviceName);
                    }
                }

                @Override
                public void onPhotoReceived(Bitmap photo) {
                    showAirPlayPhoto(photo);
                }

                @Override
                public void onVideoPlayRequested(String videoUrl, float startPosition) {
                    SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
                    if (!prefs.getBoolean("mango_play_enabled", true) || !prefs.getBoolean("mango_prompts_enabled", true)) {
                        playAirPlayVideo(videoUrl, startPosition);
                        return;
                    }
                    if (connectivityCoordinator != null) {
                        PlayHandoffRequest req = new PlayHandoffRequest(
                                "airplay-" + System.currentTimeMillis(),
                                "Apple Device",
                                "AirPlay Video",
                                "Streaming Video • 1080p",
                                videoUrl,
                                (long) (startPosition * 1000),
                                0,
                                null
                        );
                        connectivityCoordinator.showPlayHandoffPrompt(req);
                    } else {
                        playAirPlayVideo(videoUrl, startPosition);
                    }
                }

                @Override
                public void onPlaybackRateChanged(float rate) {}

                @Override
                public void onPlaybackScrub(float position) {}

                @Override
                public void onAirPlayStopped() {
                    if (connectivityCoordinator != null) {
                        connectivityCoordinator.dismissDialog();
                        connectivityCoordinator.dismissStatusPill();
                    } else {
                        dismissAirPlayHud();
                    }
                    if (airPlayPhotoView != null) airPlayPhotoView.setVisibility(View.GONE);
                }
            });
            mangoAirPlayManager.start();
        } catch (Throwable t) {
            Log.w("MainActivity", "Failed to start Mango AirPlay manager", t);
        }
    }

    private void stopMangoAirPlay() {
        if (mangoAirPlayManager != null) {
            mangoAirPlayManager.stop();
            mangoAirPlayManager = null;
        }
        stopMangoRemote();
        dismissAirPlayHud();
        if (airPlayPhotoView != null) airPlayPhotoView.setVisibility(View.GONE);
    }

    private void showAirPlayHud(String status, String clientName) {
        if (airPlayHud == null) return;
        if (airPlayHudTitle != null) airPlayHudTitle.setText("AirPlay • " + clientName);
        if (airPlayHudStatus != null) {
            airPlayHudStatus.setText(status);
            airPlayHudStatus.setTextColor(0xFF34C759);
        }

        if (airPlayHud.getVisibility() != View.VISIBLE) {
            airPlayHud.setVisibility(View.VISIBLE);
            airPlayHud.setAlpha(0f);
            airPlayHud.setTranslationY(-24f * density);
            airPlayHud.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
        }

        if (airPlayAutoDismissRunnable != null) {
            handler.removeCallbacks(airPlayAutoDismissRunnable);
        }
        airPlayAutoDismissRunnable = this::dismissAirPlayHud;
        handler.postDelayed(airPlayAutoDismissRunnable, 6000L);
    }

    private void dismissAirPlayHud() {
        if (airPlayHud == null || airPlayHud.getVisibility() != View.VISIBLE) return;
        if (airPlayAutoDismissRunnable != null) {
            handler.removeCallbacks(airPlayAutoDismissRunnable);
            airPlayAutoDismissRunnable = null;
        }

        airPlayHud.animate()
                .alpha(0f)
                .translationY(-20f * density)
                .setDuration(200)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.4f))
                .withEndAction(() -> airPlayHud.setVisibility(View.GONE))
                .start();
    }

    private void showAirPlayPhoto(Bitmap photo) {
        if (photo == null || airPlayPhotoView == null) return;
        airPlayPhotoView.setImageBitmap(photo);
        airPlayPhotoView.setVisibility(View.VISIBLE);
        airPlayPhotoView.setAlpha(0f);
        airPlayPhotoView.animate()
                .alpha(1f)
                .setDuration(300)
                .start();
        showAirPlayHud("Photo Cast", "AirPlay");
    }

    private void playAirPlayVideo(String videoUrl, float startPosition) {
        if (videoUrl == null || videoUrl.isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(android.net.Uri.parse(videoUrl), "video/*");
            intent.putExtra("position", (long) (startPosition * 1000));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            showAirPlayHud("Playing Stream", "AirPlay");
        } catch (Throwable t) {
            Log.w("MainActivity", "Failed to launch video player for AirPlay", t);
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}

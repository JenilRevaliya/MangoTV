package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView mangoShareChoice;
    private TextView mangoAirPlayChoice;
    private TextView mangoBudsChoice;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;
    private boolean mangoShare;
    private boolean mangoAirPlay;
    private boolean mangoBuds;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        mangoShare = choices.getBoolean("mango_share_enabled", true);
        mangoAirPlay = choices.getBoolean("mango_airplay_enabled", true);
        mangoBuds = choices.getBoolean("mango_buds_enabled", true);

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            LinearLayout row = (LinearLayout) columnChoices[0].getParent();
            row.removeAllViews();
            for (int i = columnChoices.length - 1; i >= 0; i--) row.addView(columnChoices[i]);
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").commit();
            Config current = Config.load(this);
            columns = current.columns;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));

        mangoShareChoice = findViewById(R.id.mangoShareChoice);
        mangoShareChoice.setOnClickListener(v -> {
            mangoShare = !mangoShare;
            choices.edit().putBoolean("mango_share_enabled", mangoShare).apply();
            setResult(RESULT_OK);
            refresh();
        });

        mangoAirPlayChoice = findViewById(R.id.mangoAirPlayChoice);
        mangoAirPlayChoice.setOnClickListener(v -> {
            mangoAirPlay = !mangoAirPlay;
            choices.edit().putBoolean("mango_airplay_enabled", mangoAirPlay).apply();
            setResult(RESULT_OK);
            refresh();
        });

        mangoBudsChoice = findViewById(R.id.mangoBudsChoice);
        mangoBudsChoice.setOnClickListener(v -> {
            mangoBuds = !mangoBuds;
            choices.edit().putBoolean("mango_buds_enabled", mangoBuds).apply();
            setResult(RESULT_OK);
            refresh();
        });

        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        followThemeChoice.setSelected(!hasOnTvChoices());
        if (mangoShareChoice != null) {
            mangoShareChoice.setText("Mango Share: " + (mangoShare ? getString(R.string.on) : getString(R.string.off)));
            mangoShareChoice.setSelected(mangoShare);
        }
        if (mangoAirPlayChoice != null) {
            mangoAirPlayChoice.setText("Mango AirPlay: " + (mangoAirPlay ? getString(R.string.on) : getString(R.string.off)));
            mangoAirPlayChoice.setSelected(mangoAirPlay);
        }
        if (mangoBudsChoice != null) {
            mangoBudsChoice.setText("Mango Buds: " + (mangoBuds ? getString(R.string.on) : getString(R.string.off)));
            mangoBudsChoice.setSelected(mangoBuds);
        }
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("animations")
                || choices.contains("advanced_effects");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}

package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * The launcher-side view of the companion overrides. Compiled in every build, but inert unless the
 * opt-in build set {@link BuildConfig#COMPANION_BRIDGE}; with the flag off every method returns the
 * theme's own value and nothing is read from the companion directory.
 */
final class CompanionBridge {
    private static final String TAG = "ThemeSchedule";
    /** Last rule logged, so a minute tick does not repeat the same line. */
    private static String loggedRule = "";
    private static CompanionStore instance;
    private static volatile Runnable rebuild;

    private CompanionBridge() {}

    /**
     * Registers Home's rebuild hook while it is started, or clears it with null. The provider runs in
     * this process, so an in-process hook is enough; a broadcast receiver would be exported to other
     * apps on API levels before 33, where RECEIVER_NOT_EXPORTED does not exist.
     */
    static void listen(Runnable hook) {
        rebuild = hook;
    }

    /** Called by the provider after an activation has committed, from its binder thread. */
    static void activated() {
        Runnable hook = rebuild;
        if (hook != null) hook.run();
    }

    static synchronized CompanionStore store(Context context) throws Exception {
        Context app = context.getApplicationContext();
        if (instance == null) {
            instance = new CompanionStore(new File(app.getFilesDir(), "companion"), () -> {
                File dir = Config.dir(app);
                JSONObject theme = dir == null ? null : Config.read(new File(dir, Config.FILE_NAME));
                return new JSONObject()
                        .put("settings", theme == null ? new JSONObject() : theme)
                        .put("preferences", new JSONObject(app.getSharedPreferences(
                                Config.SETTINGS_NAME, Context.MODE_PRIVATE).getAll()));
            });
        }
        return instance;
    }

    /** Applies companion overrides and, when enabled, the current scheduled theme. */
    static JSONObject overlay(Context context, JSONObject theme) {
        if (!BuildConfig.COMPANION_BRIDGE) return theme;
        try {
            JSONObject result = theme == null ? new JSONObject() : CompanionStore.copy(theme);
            JSONObject settings = effective(context);
            for (Iterator<String> it = settings.keys(); it.hasNext(); ) {
                String key = it.next();
                result.put(key, settings.get(key));
            }
            return result;
        } catch (Exception failure) {
            return theme;
        }
    }

    /** The active collection's local files, or an empty list when none is selected. */
    static List<File> selectedAssets(Context context) {
        if (!BuildConfig.COMPANION_BRIDGE) return Collections.emptyList();
        try {
            String selected = effective(context).optString("active_collection", "");
            if (!CompanionProtocol.hex(selected, 32)) return Collections.emptyList();
            return store(context).selectedAssets(selected);
        } catch (Exception failure) {
            return Collections.emptyList();
        }
    }

    /**
     * Overrides after the schedule is applied. When the schedule is disabled, invalid or absent this
     * is exactly {@link CompanionStore#overrides()}, so turning it off restores the previous values.
     */
    static JSONObject effective(Context context) throws Exception {
        JSONObject settings = store(context).overrides();
        if (schedules()) applySchedule(settings, Instant.now());
        return settings;
    }

    private static void applySchedule(JSONObject settings, Instant now) {
        if (!settings.optBoolean("theme_schedule_enabled", false)) return;
        JSONObject raw = settings.optJSONObject("theme_schedule");
        if (raw == null) return;
        try {
            ThemeSchedule.Schedule schedule = ThemeSchedule.validate(raw);
            ThemeSchedule.ManualOverride manual = ThemeSchedule.manualOverride(
                    settings.optJSONObject("theme_manual_override"));
            ThemeSchedule.Effective effective = ThemeSchedule.effective(schedule, now, manual);
            for (Iterator<String> it = effective.theme.keys(); it.hasNext(); ) {
                String key = it.next();
                settings.put(key, effective.theme.get(key));
            }
            // The rule and boundary, never the theme or the saved config.
            String rule = effective.ruleId + "@" + (effective.transitionAt == null
                    ? "default" : effective.transitionAt.toEpochMilli());
            if (!rule.equals(loggedRule)) {
                loggedRule = rule;
                Log.i(TAG, "applied rule=" + effective.ruleId + " manual=" + effective.manual
                        + " fallback=" + effective.fallback
                        + " transition=" + (effective.transitionAt == null
                        ? "default" : effective.transitionAt));
            }
        } catch (Exception invalid) {
            // A corrupt schedule must never stop Home from loading; keep the static theme.
            Log.w(TAG, "ignored invalid or disabled schedule");
        }
    }

    /** True when this build carries the provider; used by the settings UI and diagnostics. */
    static boolean enabled() {
        return BuildConfig.COMPANION_BRIDGE;
    }

    /** True when this bridge build also evaluates theme schedules. */
    static boolean schedules() {
        return BuildConfig.COMPANION_BRIDGE && BuildConfig.COMPANION_SCHEDULES;
    }

    /** The schedule readback used by state and by the TV settings row, or null when none is saved. */
    static JSONObject schedule(Context context) {
        if (!schedules()) return null;
        try {
            CompanionStore source = store(context);
            JSONObject overrides = source.overrides();
            if (overrides.optJSONObject("theme_schedule") == null) return null;
            return CompanionStore.scheduleInfo(overrides, Instant.now());
        } catch (Exception failure) {
            return null;
        }
    }

    /** The on-TV switch writes one override; the saved schedule stays for a later re-enable. */
    static boolean setScheduleEnabled(Context context, boolean enabled) {
        if (!schedules()) return false;
        try {
            store(context).setScheduleEnabled(enabled);
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    /**
     * A cheap value that changes only when the schedule changes what Home draws. The minute tick and
     * clock/timezone broadcasts compare it, so missed transitions are reconciled once, never replayed.
     */
    static String effectiveThemeFingerprint(Context context) {
        if (!schedules()) return "";
        try {
            JSONObject settings = effective(context);
            JSONObject theme = new JSONObject();
            for (String key : new String[]{"wallpaper", "wallpaper_dim", "background_color",
                    "accent_color", "active_collection"}) {
                if (settings.has(key)) theme.put(key, settings.get(key));
            }
            return CompanionProtocol.sha256(CompanionProtocol.canonical(theme)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception failure) {
            return "";
        }
    }
}

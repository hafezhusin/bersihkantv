package com.gathin.tvhome;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * The launcher-side view of the companion overrides. Compiled in every build, but inert unless the
 * opt-in build set {@link BuildConfig#COMPANION_BRIDGE}; with the flag off every method returns the
 * theme's own value and nothing is read from the companion directory.
 */
final class CompanionBridge {
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

    /** Applies only companion overrides; the theme file is not rewritten. */
    static JSONObject overlay(Context context, JSONObject theme) {
        if (!BuildConfig.COMPANION_BRIDGE) return theme;
        try {
            JSONObject result = theme == null ? new JSONObject() : CompanionStore.copy(theme);
            JSONObject overrides = store(context).overrides();
            for (Iterator<String> it = overrides.keys(); it.hasNext(); ) {
                String key = it.next();
                result.put(key, overrides.get(key));
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
            JSONObject overrides = store(context).overrides();
            String selected = overrides.optString("active_collection", "");
            if (!CompanionProtocol.hex(selected, 32)) return Collections.emptyList();
            return store(context).selectedAssets(selected);
        } catch (Exception failure) {
            return Collections.emptyList();
        }
    }

    /** True when this build carries the provider; used by the settings UI and diagnostics. */
    static boolean enabled() {
        return BuildConfig.COMPANION_BRIDGE;
    }
}

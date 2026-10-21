package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Apps hidden from Home by long-pressing their tile and choosing "Hide App", on top of the
 * hidden_apps in res/values/config.xml (or a theme's config.json). Stored as a string set in
 * SharedPreferences, so a hide survives restarts and rebuilds with no file or database to maintain.
 *
 * Hiding is not uninstalling: the app stays installed and can be brought back from Launcher
 * settings ("Hidden apps"), or by adding it to Home again from the App library.
 */
final class HiddenApps {
    private static final String PREFS = "hidden_apps";
    private static final String KEY = "packages";

    private HiddenApps() {
    }

    /** The packages hidden from the TV UI; empty when nothing was ever hidden there. */
    static Set<String> load(Context ctx) {
        Set<String> saved = prefs(ctx).getStringSet(KEY, Collections.emptySet());
        // The set getStringSet hands out is the stored set itself: never mutate or return it.
        return saved == null ? new HashSet<>() : new HashSet<>(saved);
    }

    /** Hides this package from Home from the next tile reload on. */
    static void hide(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> next = load(ctx);
        if (next.add(pkg)) prefs(ctx).edit().putStringSet(KEY, next).apply();
    }

    /** Brings a hidden package back to Home from the next tile reload on. */
    static void unhide(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Set<String> next = load(ctx);
        if (next.remove(pkg)) prefs(ctx).edit().putStringSet(KEY, next).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

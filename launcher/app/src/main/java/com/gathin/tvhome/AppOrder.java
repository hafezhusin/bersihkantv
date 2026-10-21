package com.gathin.tvhome;

import java.util.Comparator;
import java.util.List;

/**
 * How the apps below the dock are ordered (Launcher settings, App order). The dock row always keeps the apps the
 * owner pinned there; only the tiles after it follow this choice:
 * - custom: the owner's own order (Move, reorder), new installs alphabetical after it, as before this setting;
 * - az: alphabetical by tile name, so an app taken out of the dock drops into its alphabetical place;
 * - newest: most recently installed first.
 * Stored in the launcher's settings (Config.SETTINGS_NAME) under {@link #KEY}. No Android types: tested on the JVM.
 */
final class AppOrder {

    static final String KEY = "app_order";
    static final String CUSTOM = "custom", AZ = "az", NEWEST = "newest";
    /** In the order the settings dialog lists them. */
    static final String[] MODES = {CUSTOM, AZ, NEWEST};

    private AppOrder() {
    }

    /** A stored value, or custom for anything unknown (an older or edited settings file). */
    static String parse(String value) {
        for (String mode : MODES) if (mode.equals(value)) return mode;
        return CUSTOM;
    }

    /** Sorts items[from..] in place with this comparator and leaves items[0..from) (the dock) as they are. */
    static <T> void sortFrom(List<T> items, int from, Comparator<T> order) {
        if (from < 0) from = 0;
        if (from >= items.size() - 1) return;
        items.subList(from, items.size()).sort(order);
    }
}

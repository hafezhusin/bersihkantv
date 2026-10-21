package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The read half of the companion store. Write paths live in the separate staging issue, so this class
 * can only read the active state and the theme overlay. Paths, credentials and unknown settings never
 * leave it.
 */
final class CompanionStore {
    static final long ASSET_BYTES = 20L * 1024 * 1024;
    static final long COLLECTION_BYTES = 256L * 1024 * 1024;
    static final int ASSET_COUNT = 200;

    /** Settings the companion owns and may read back. Credentials are never in this list. */
    static final Set<String> SAFE_KEYS = new HashSet<>(Arrays.asList(
            "wallpaper", "wallpaper_rotation_minutes", "wallpaper_dim", "wallpaper_order",
            "active_collection", "background_color", "accent_color", "reduced_motion",
            "theme_schedule", "theme_schedule_enabled"));

    interface Source {
        JSONObject read() throws Exception;
    }

    private final File root;
    private final Source source;

    CompanionStore(File root, Source source) {
        this.root = root;
        this.source = source;
    }

    static JSONObject copy(JSONObject object) throws JSONException {
        return new JSONObject(object.toString());
    }

    private JSONObject active() throws Exception {
        File file = new File(root, "active.json");
        if (!file.isFile()) {
            return new JSONObject().put("settings", new JSONObject())
                    .put("collections", new JSONArray()).put("operations", new JSONObject());
        }
        if (file.length() > CompanionProtocol.MAX_ENVELOPE) throw new CompanionProtocol.Failure("invalid_request");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    static JSONObject safe(JSONObject object) throws JSONException {
        JSONObject result = new JSONObject();
        for (String key : SAFE_KEYS) if (object.has(key)) result.put(key, object.get(key));
        return result;
    }

    static String revision(JSONObject active, JSONObject legacy) throws Exception {
        return CompanionProtocol.sha256(CompanionProtocol.canonical(new JSONObject()
                .put("settings", active.optJSONObject("settings") == null ? new JSONObject() : active.getJSONObject("settings"))
                .put("collections", active.optJSONArray("collections") == null ? new JSONArray() : active.getJSONArray("collections"))
                .put("source", legacy)));
    }

    /**
     * Exposure is deliberately narrow: opaque IDs, hashes, sizes and playback preferences. A malformed
     * record is skipped rather than echoed, so a broken active file cannot leak a path.
     */
    static JSONArray collections(JSONObject active) {
        JSONArray result = new JSONArray();
        JSONArray input = active.optJSONArray("collections");
        if (input == null) return result;
        for (int i = 0; i < input.length(); i++) {
            JSONObject collection = input.optJSONObject(i);
            if (collection == null || !CompanionProtocol.hex(collection.optString("id", ""), 32)) continue;
            String order = collection.optString("order", "");
            if (!"sequential".equals(order) && !"shuffle".equals(order)) continue;
            if (!CompanionProtocol.integer(collection.opt("interval_minutes"), 1, 1440)) continue;
            JSONArray assets = new JSONArray();
            JSONArray declared = collection.optJSONArray("assets");
            if (declared == null) continue;
            for (int j = 0; j < declared.length(); j++) {
                JSONObject asset = declared.optJSONObject(j);
                if (asset == null || !CompanionProtocol.hex(asset.optString("sha256", ""), 64)) continue;
                if (!CompanionProtocol.integer(asset.opt("bytes"), 1, ASSET_BYTES)) continue;
                String mime = asset.optString("mime", "");
                if (!Arrays.asList("image/jpeg", "image/png", "image/webp").contains(mime)) continue;
                try {
                    assets.put(new JSONObject().put("sha256", asset.getString("sha256"))
                            .put("bytes", asset.getLong("bytes")).put("mime", mime));
                } catch (JSONException impossible) {
                    // skip a malformed asset
                }
            }
            try {
                result.put(new JSONObject().put("id", collection.getString("id"))
                        .put("assets", assets).put("order", order)
                        .put("interval_minutes", collection.getLong("interval_minutes")));
            } catch (JSONException impossible) {
                // skip a malformed collection
            }
        }
        return result;
    }

    static JSONObject operation(JSONObject active) {
        JSONObject last = active.optJSONObject("last_operation");
        if (last == null) return new JSONObject();
        JSONObject result = new JSONObject();
        try {
            if (CompanionProtocol.hex(last.optString("request_id", ""), 32)) result.put("request_id", last.getString("request_id"));
            if (CompanionProtocol.hex(last.optString("session_id", ""), 32)) result.put("session_id", last.getString("session_id"));
            if (CompanionProtocol.hex(last.optString("revision", ""), 64)) result.put("revision", last.getString("revision"));
        } catch (JSONException impossible) {
            // keep the fields that did parse
        }
        return result;
    }

    synchronized JSONObject state() throws Exception {
        JSONObject active = active();
        JSONObject legacy = source.read();
        JSONObject overrides = active.optJSONObject("settings") == null
                ? new JSONObject() : active.getJSONObject("settings");
        JSONObject effective = copy(legacy.optJSONObject("settings") == null
                ? new JSONObject() : legacy.getJSONObject("settings"));
        for (Iterator<String> it = overrides.keys(); it.hasNext(); ) {
            String key = it.next();
            effective.put(key, overrides.get(key));
        }
        JSONArray collections = collections(active);
        return new JSONObject()
                .put("revision", revision(active, legacy))
                .put("settings", safe(effective))
                .put("overrides", safe(overrides))
                .put("collections", collections)
                .put("active_collection", effective.opt("active_collection"))
                .put("operation", operation(active));
    }

    static JSONObject capabilities(String launcherVersion) {
        try {
            return new JSONObject()
                    .put("protocol_version", CompanionProtocol.VERSION)
                    .put("launcher_version", launcherVersion)
                    .put("features", new JSONArray(Arrays.asList(
                            "collections_v1", "atomic_activation_v1",
                            "revision_checks_v1", "readback_v1")))
                    .put("limits", new JSONObject()
                            .put("asset_bytes", ASSET_BYTES)
                            .put("collection_bytes", COLLECTION_BYTES)
                            .put("asset_count", ASSET_COUNT));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

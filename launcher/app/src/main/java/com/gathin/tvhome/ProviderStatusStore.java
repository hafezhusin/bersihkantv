package com.gathin.tvhome;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Safe, non-secret provider state: enabled, last success/failure, rate-limit/offline state and the
 * asset attribution. It never stores a credential, a URL, a request token or a filesystem path.
 *
 * Retrieval callers record outcomes here. Home keeps showing the last verified local asset while a
 * provider is offline, rate-limited or returning invalid media; this record explains why it did.
 */
final class ProviderStatusStore {
    static final String OFFLINE = "offline";
    static final String RATE_LIMITED = "rate_limited";
    static final String INVALID_MEDIA = "invalid_media";
    static final String AUTH_ERROR = "auth_error";
    static final String UNAVAILABLE = "unavailable";
    static final String OK = "ok";

    private static final String[] STATES = {
            "idle", OK, OFFLINE, RATE_LIMITED, INVALID_MEDIA, AUTH_ERROR, UNAVAILABLE};

    private final File file;

    ProviderStatusStore(File root) {
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("provider status storage unavailable");
        }
        this.file = new File(root, "status.json");
    }

    synchronized JSONObject status(String provider) {
        JSONObject entry = read().optJSONObject(provider);
        JSONObject result = new JSONObject();
        try {
            result.put("provider", provider);
            result.put("enabled", entry != null && entry.optBoolean("enabled", false));
            result.put("state", entry == null ? "idle" : entry.optString("state", "idle"));
            if (entry != null) {
                if (entry.has("last_success")) result.put("last_success", entry.get("last_success"));
                if (entry.has("last_failure")) result.put("last_failure", entry.getJSONObject("last_failure"));
                if (entry.has("attribution")) result.put("attribution", entry.getJSONObject("attribution"));
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return result;
    }

    synchronized void setEnabled(String provider, boolean enabled) {
        JSONObject all = read();
        JSONObject entry = entry(all, provider);
        try {
            entry.put("enabled", enabled);
            if (!enabled) entry.remove("attribution");
            write(all);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Records a successful, validated retrieval and which provider supplied the active asset. */
    synchronized void recordSuccess(String provider, String asset) {
        JSONObject all = read();
        JSONObject entry = entry(all, provider);
        try {
            entry.put("state", OK).put("enabled", true)
                    .put("last_success", System.currentTimeMillis())
                    .put("attribution", new JSONObject().put("asset", safeAsset(asset))
                            .put("at", System.currentTimeMillis()));
            write(all);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Maps a safe failure code to a state; unknown codes become a generic offline state. */
    synchronized void recordFailure(String provider, String code) {
        JSONObject all = read();
        JSONObject entry = entry(all, provider);
        try {
            String state = Arrays.asList(RATE_LIMITED, INVALID_MEDIA, AUTH_ERROR, OFFLINE).contains(code)
                    ? code : OFFLINE;
            entry.put("state", state).put("enabled", true)
                    .put("last_failure", new JSONObject().put("at", System.currentTimeMillis())
                            .put("code", state));
            write(all);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Revocation clears enabled, health and attribution together. */
    synchronized void revoke(String provider) {
        JSONObject all = read();
        all.remove(provider);
        write(all);
    }

    /** A credential file that cannot be decrypted is reported as unavailable, not as configured. */
    synchronized JSONObject withConfigured(String provider, boolean configured) {
        JSONObject result = status(provider);
        try {
            result.put("configured", configured && !UNAVAILABLE.equals(result.optString("state")));
            if (!configured) {
                result.put("enabled", false).put("state", result.has("last_failure") ? UNAVAILABLE : "idle");
                result.remove("attribution");
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return result;
    }

    private static JSONObject entry(JSONObject all, String provider) {
        JSONObject entry = all.optJSONObject(provider);
        if (entry == null) {
            entry = new JSONObject();
            try {
                all.put(provider, entry);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return entry;
    }

    private static String safeAsset(String asset) {
        if (asset == null) return "";
        return asset.matches("[A-Za-z0-9_-]{1,128}") ? asset : "";
    }

    private JSONObject read() {
        if (!file.isFile() || file.length() > 64 * 1024) return new JSONObject();
        try {
            JSONObject all = new JSONObject(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8));
            // Drop any entry whose state is not from the known vocabulary.
            java.util.List<String> providers = new java.util.ArrayList<>();
            Iterator<String> keys = all.keys();
            while (keys.hasNext()) providers.add(keys.next());
            for (String provider : providers) {
                JSONObject entry = all.optJSONObject(provider);
                if (entry == null || !Arrays.asList(STATES).contains(entry.optString("state", "idle"))) {
                    all.remove(provider);
                }
            }
            return all;
        } catch (Exception malformed) {
            return new JSONObject();
        }
    }

    private void write(JSONObject all) {
        try {
            byte[] bytes = CompanionProtocol.canonical(all).getBytes(StandardCharsets.UTF_8);
            File temp = new File(file.getParentFile(), "status.json.tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            Files.move(temp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception failure) {
            throw new IllegalStateException("provider status unavailable");
        }
    }
}

package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Transactional companion store. Transport and Android caller checks live in the provider; this class
 * is plain Java so the staging, revision and crash-boundary rules can be tested on the JVM.
 *
 * Paths, credentials and unknown settings never leave it.
 */
final class CompanionStore {
    static final long ASSET_BYTES = 20L * 1024 * 1024;
    static final long COLLECTION_BYTES = 256L * 1024 * 1024;
    static final int ASSET_COUNT = 200;
    static final int COLLECTIONS = 20;
    static final int OPERATIONS = 1000;

    /** Settings the companion owns and may read back. Credentials are never in this list. */
    static final Set<String> SAFE_KEYS = new HashSet<>(Arrays.asList(
            "wallpaper", "wallpaper_rotation_minutes", "wallpaper_dim", "wallpaper_order",
            "active_collection", "background_color", "accent_color", "reduced_motion",
            "theme_schedule", "theme_schedule_enabled"));
    /** Public patch keys. Internal journal keys such as theme_manual_override are not writable here. */
    static final Set<String> PATCH_KEYS = new HashSet<>(Arrays.asList(
            "wallpaper", "wallpaper_rotation_minutes", "wallpaper_dim", "wallpaper_order",
            "active_collection", "background_color", "accent_color", "reduced_motion",
            "theme_schedule", "theme_schedule_enabled"));

    interface Source {
        JSONObject read() throws Exception;
    }

    interface Sync {
        void directory(File directory) throws Exception;
    }

    interface ImageCheck {
        void verify(File image, String mime) throws Exception;
    }

    private final File root;
    private final File sessions;
    private final File assets;
    private final Source source;
    private final Sync sync;
    private final ImageCheck images;
    private final Set<String> writers = new HashSet<>();

    CompanionStore(File root, Source source) {
        this(root, source, directory -> {}, (file, mime) -> {});
    }

    CompanionStore(File root, Source source, Sync sync, ImageCheck images) {
        this.root = root;
        this.source = source;
        this.sync = sync;
        this.images = images;
        this.sessions = new File(root, "sessions");
        this.assets = new File(root, "assets");
        if ((!sessions.isDirectory() && !sessions.mkdirs())
                || (!assets.isDirectory() && !assets.mkdirs())) {
            throw new IllegalStateException("companion storage unavailable");
        }
    }

    static JSONObject copy(JSONObject object) throws JSONException {
        return new JSONObject(object.toString());
    }

    private JSONObject read(File file, JSONObject fallback) throws Exception {
        if (!file.isFile()) return copy(fallback);
        if (file.length() > CompanionProtocol.MAX_ENVELOPE) {
            throw new CompanionProtocol.Failure("invalid_request");
        }
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private void atomic(File file, JSONObject value) throws Exception {
        byte[] bytes = CompanionProtocol.canonical(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CompanionProtocol.MAX_ENVELOPE) {
            throw new CompanionProtocol.Failure("storage_full");
        }
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("storage");
        File temp = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        Files.move(temp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        sync.directory(parent);
    }

    private JSONObject active() throws Exception {
        return read(new File(root, "active.json"), new JSONObject()
                .put("settings", new JSONObject())
                .put("collections", new JSONArray())
                .put("operations", new JSONObject()));
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
        return state(true);
    }

    /** The provider passes its build's schedules flag, so a build without the feature cannot report it. */
    synchronized JSONObject state(boolean schedules) throws Exception {
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
        return new JSONObject()
                .put("revision", revision(active, legacy))
                .put("settings", safe(effective))
                .put("overrides", safe(overrides))
                .put("collections", collections(active))
                .put("active_collection", effective.opt("active_collection"))
                .put("operation", operation(active))
                .put("schedule", scheduleInfo(schedules ? overrides : new JSONObject(), Instant.now()));
    }

    static JSONObject capabilities(String launcherVersion) {
        return capabilities(launcherVersion, false, false);
    }

    static JSONObject capabilities(String launcherVersion, boolean schedules) {
        return capabilities(launcherVersion, schedules, false);
    }

    static JSONObject capabilities(String launcherVersion, boolean schedules, boolean credentials) {
        try {
            JSONArray features = new JSONArray(Arrays.asList(
                    "collections_v1", "atomic_activation_v1",
                    "revision_checks_v1", "readback_v1"));
            if (schedules) features.put("theme_schedules_v1");
            if (credentials) features.put("private_credentials_v1");
            return new JSONObject()
                    .put("protocol_version", CompanionProtocol.VERSION)
                    .put("launcher_version", launcherVersion)
                    .put("features", features)
                    .put("limits", new JSONObject()
                            .put("asset_bytes", ASSET_BYTES)
                            .put("collection_bytes", COLLECTION_BYTES)
                            .put("asset_count", ASSET_COUNT));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Readback for the schedules feature. The effective rule is the last evaluated transition; the
     * upcoming list lets the companion show the next change without guessing. Invalid or disabled
     * schedules report enabled=false and never affect Config.
     */
    static JSONObject scheduleInfo(JSONObject settings, Instant now) {
        JSONObject result = new JSONObject();
        try {
            result.put("enabled", false);
            JSONObject raw = settings.optJSONObject("theme_schedule");
            if (raw == null || !settings.optBoolean("theme_schedule_enabled", false)) return result;
            ThemeSchedule.Schedule schedule = ThemeSchedule.validate(raw);
            ThemeSchedule.Effective effective = ThemeSchedule.effective(schedule, now);
            JSONObject current = new JSONObject().put("rule_id", effective.ruleId)
                    .put("manual", effective.manual).put("fallback", effective.fallback)
                    .put("theme", effective.theme);
            if (effective.transitionAt != null) {
                current.put("transition_at", effective.transitionAt.toEpochMilli());
            }
            JSONArray upcoming = new JSONArray();
            for (ThemeSchedule.Transition transition : ThemeSchedule.upcoming(schedule, now, 8)) {
                upcoming.put(new JSONObject().put("at", transition.instant.toEpochMilli())
                        .put("rule_id", transition.ruleId)
                        .put("theme", transition.theme)
                        .put("fallback", transition.fallback));
            }
            result.put("enabled", true).put("timezone", schedule.zone.getId())
                    .put("last_evaluated", current).put("upcoming", upcoming);
        } catch (Exception invalid) {
            try {
                result.put("enabled", false).put("invalid", true);
            } catch (JSONException impossible) {
                // keep the minimal object
            }
        }
        return result;
    }

    /** The on-TV disable switch writes one override; it does not delete the saved schedule. */
    synchronized JSONObject setScheduleEnabled(boolean enabled) throws Exception {
        JSONObject active = active();
        active.getJSONObject("settings").put("theme_schedule_enabled", enabled);
        atomic(new File(root, "active.json"), active);
        return new JSONObject().put("enabled", enabled);
    }

    // ---------------------------------------------------------------- staging and activation

    static void require(boolean valid, String code) throws CompanionProtocol.Failure {
        if (!valid) throw new CompanionProtocol.Failure(code);
    }

    static void patch(JSONObject patch) throws Exception {
        patch(patch, false);
    }

    static void patch(JSONObject patch, boolean schedules) throws Exception {
        for (Iterator<String> it = patch.keys(); it.hasNext(); ) {
            require(Arrays.asList("set", "remove").contains(it.next()), "invalid_request");
        }
        JSONObject set = patch.optJSONObject("set");
        JSONArray remove = patch.optJSONArray("remove");
        require(!patch.has("set") || set != null, "invalid_request");
        require(!patch.has("remove") || remove != null, "invalid_request");
        if (set == null) set = new JSONObject();
        if (remove == null) remove = new JSONArray();
        Set<String> removed = new HashSet<>();
        for (int i = 0; i < remove.length(); i++) {
            String key = remove.getString(i);
            require(PATCH_KEYS.contains(key) && !set.has(key) && removed.add(key), "invalid_request");
        }
        for (Iterator<String> it = set.keys(); it.hasNext(); ) {
            String key = it.next();
            require(PATCH_KEYS.contains(key), "unsupported_feature");
            Object value = set.get(key);
            boolean valid;
            switch (key) {
                case "wallpaper":
                case "reduced_motion":
                case "theme_schedule_enabled":
                    valid = value instanceof Boolean;
                    break;
                case "wallpaper_rotation_minutes":
                    valid = CompanionProtocol.integer(value, 1, 1440);
                    break;
                case "wallpaper_dim":
                    valid = CompanionProtocol.integer(value, 0, 100);
                    break;
                case "wallpaper_order":
                    valid = value instanceof String
                            && ("sequential".equals(value) || "shuffle".equals(value));
                    break;
                case "active_collection":
                    valid = value instanceof String && CompanionProtocol.hex((String) value, 32);
                    break;
                case "theme_schedule":
                    // Accepted only when theme_schedules_v1 is advertised by this build.
                    valid = schedules && value instanceof JSONObject;
                    if (valid) {
                        try {
                            ThemeSchedule.validate((JSONObject) value);
                        } catch (IllegalArgumentException invalid) {
                            valid = false;
                        }
                    }
                    break;
                default:
                    valid = value instanceof String && ((String) value).matches("#[a-fA-F0-9]{6}");
            }
            require(valid, "invalid_request");
        }
    }

    private static void manifest(JSONArray collections, long usable) throws Exception {
        require(collections.length() <= COLLECTIONS, "invalid_request");
        long total = 0;
        int count = 0;
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < collections.length(); i++) {
            JSONObject collection = collections.getJSONObject(i);
            for (Iterator<String> key = collection.keys(); key.hasNext(); ) {
                require(Arrays.asList("id", "assets", "order", "interval_minutes")
                        .contains(key.next()), "invalid_request");
            }
            String id = collection.getString("id");
            require(CompanionProtocol.hex(id, 32) && ids.add(id), "invalid_request");
            require(Arrays.asList("sequential", "shuffle").contains(collection.getString("order")), "invalid_request");
            require(CompanionProtocol.integer(collection.get("interval_minutes"), 1, 1440), "invalid_request");
            JSONArray list = collection.getJSONArray("assets");
            require(list.length() > 0, "invalid_request");
            Set<String> hashes = new HashSet<>();
            for (int j = 0; j < list.length(); j++) {
                JSONObject asset = list.getJSONObject(j);
                for (Iterator<String> key = asset.keys(); key.hasNext(); ) {
                    require(Arrays.asList("sha256", "bytes", "mime").contains(key.next()), "invalid_request");
                }
                String sha = asset.getString("sha256");
                require(CompanionProtocol.hex(sha, 64) && hashes.add(sha)
                        && CompanionProtocol.integer(asset.get("bytes"), 1, ASSET_BYTES), "invalid_asset");
                require(Arrays.asList("image/jpeg", "image/png", "image/webp")
                        .contains(asset.getString("mime")), "invalid_asset");
                total += asset.getLong("bytes");
                count++;
            }
        }
        require(total <= COLLECTION_BYTES && count <= ASSET_COUNT, "storage_full");
        // Keep a margin for the previous collection, so activation never evicts active media.
        require(usable > total + 1024 * 1024, "storage_full");
    }

    private File sessionFile(String id) throws Exception {
        require(CompanionProtocol.hex(id, 32), "invalid_request");
        File file = new File(sessions, id + ".json");
        require(file.getParentFile().getCanonicalFile().equals(sessions.getCanonicalFile()), "invalid_request");
        return file;
    }

    JSONObject session(String id) throws Exception {
        File file = sessionFile(id);
        require(file.isFile(), "unknown_session");
        JSONObject value = read(file, new JSONObject());
        require(!value.optBoolean("discarded", false), "unknown_session");
        return value;
    }

    private JSONObject manifestAsset(JSONObject session, String sha) throws Exception {
        require(CompanionProtocol.hex(sha, 64), "invalid_request");
        JSONArray collections = session.getJSONArray("collections");
        for (int i = 0; i < collections.length(); i++) {
            JSONArray list = collections.getJSONObject(i).getJSONArray("assets");
            for (int j = 0; j < list.length(); j++) {
                if (sha.equals(list.getJSONObject(j).getString("sha256"))) {
                    return list.getJSONObject(j);
                }
            }
        }
        throw new CompanionProtocol.Failure("invalid_asset");
    }

    synchronized JSONObject stage(JSONObject request) throws Exception {
        return stage(request, false);
    }

    synchronized JSONObject stage(JSONObject request, boolean schedules) throws Exception {
        String id = request.getString("request_id");
        File file = sessionFile(id);
        if (file.exists()) {
            JSONObject old = read(file, new JSONObject());
            require(!old.optBoolean("discarded", false), "request_conflict");
            require(CompanionProtocol.canonical(old.getJSONObject("request"))
                    .equals(CompanionProtocol.canonical(request)), "request_conflict");
            return new JSONObject().put("session_id", id);
        }
        require(request.getString("expected_revision").equals(state().getString("revision")), "revision_conflict");
        patch(request.getJSONObject("patch"), schedules);
        JSONArray collections = request.getJSONArray("collections");
        manifest(collections, root.getUsableSpace());
        JSONObject session = new JSONObject()
                .put("request", copy(request))
                .put("expected_revision", request.getString("expected_revision"))
                .put("patch", request.getJSONObject("patch"))
                .put("collections", collections)
                .put("created", System.currentTimeMillis());
        atomic(file, session);
        return new JSONObject().put("session_id", id);
    }

    /** Writes one declared asset and verifies it before it becomes visible. EOF alone is not success. */
    void receive(String id, String sha, InputStream in) throws Exception {
        JSONObject spec;
        String token = id + ":" + sha;
        synchronized (this) {
            spec = manifestAsset(session(id), sha);
            require(writers.add(token), "busy");
        }
        File temp = new File(assets, sha + "." + id + ".part");
        require(!Files.isSymbolicLink(temp.toPath()), "invalid_asset");
        try {
            long total = 0;
            byte[] bytes = new byte[65536];
            try (FileOutputStream out = new FileOutputStream(temp)) {
                int read;
                while ((read = in.read(bytes)) != -1) {
                    total += read;
                    require(total <= spec.getLong("bytes") && total <= ASSET_BYTES, "invalid_asset");
                    out.write(bytes, 0, read);
                }
                require(total == spec.getLong("bytes"), "invalid_asset");
                out.getFD().sync();
            }
            require(CompanionProtocol.sha256(readAll(temp)).equals(sha), "invalid_asset");
            images.verify(temp, spec.getString("mime"));
            synchronized (this) {
                session(id);
                File target = new File(assets, sha);
                require(!Files.isSymbolicLink(target.toPath()), "invalid_asset");
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                sync.directory(assets);
            }
        } finally {
            Files.deleteIfExists(temp.toPath());
            synchronized (this) {
                writers.remove(token);
            }
        }
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    synchronized JSONObject validate(String id) throws Exception {
        JSONObject session = session(id);
        require(session.getString("expected_revision").equals(state().getString("revision")), "revision_conflict");
        JSONArray collections = session.getJSONArray("collections");
        manifest(collections, root.getUsableSpace());
        JSONArray missing = new JSONArray();
        for (int i = 0; i < collections.length(); i++) {
            JSONArray list = collections.getJSONObject(i).getJSONArray("assets");
            for (int j = 0; j < list.length(); j++) {
                JSONObject spec = list.getJSONObject(j);
                String sha = spec.getString("sha256");
                File file = new File(assets, sha);
                if (!file.isFile() || Files.isSymbolicLink(file.toPath())
                        || file.length() != spec.getLong("bytes")
                        || !CompanionProtocol.sha256(readAll(file)).equals(sha)) {
                    missing.put(sha);
                }
            }
        }
        return new JSONObject().put("ready", missing.length() == 0)
                .put("missing_assets", missing).put("revision", state().getString("revision"));
    }

    synchronized JSONObject activate(JSONObject request) throws Exception {
        String id = request.getString("session_id");
        String op = request.getString("request_id");
        require(CompanionProtocol.hex(op, 32), "invalid_request");
        JSONObject active = active();
        JSONObject operations = active.getJSONObject("operations");
        if (operations.has(op)) {
            require(id.equals(operations.getJSONObject(op).getString("session_id")), "request_conflict");
            JSONObject done = operations.getJSONObject(op);
            return new JSONObject().put("revision", done.getString("revision")).put("operation_id", op);
        }
        // Repeating activation of the same session is idempotent even with a fresh operation id.
        for (Iterator<String> it = operations.keys(); it.hasNext(); ) {
            String previous = it.next();
            JSONObject done = operations.getJSONObject(previous);
            if (id.equals(done.getString("session_id"))) {
                return new JSONObject().put("revision", done.getString("revision")).put("operation_id", previous);
            }
        }
        require(request.getString("expected_revision").equals(state().getString("revision")), "revision_conflict");
        JSONObject checked = validate(id);
        require(checked.getBoolean("ready"), "missing_assets");
        JSONObject session = session(id);
        JSONObject patch = session.getJSONObject("patch");
        JSONObject values = active.getJSONObject("settings");
        JSONObject set = patch.optJSONObject("set");
        JSONArray remove = patch.optJSONArray("remove");
        if (remove != null) {
            for (int i = 0; i < remove.length(); i++) values.remove(remove.getString(i));
        }
        if (set != null) {
            for (Iterator<String> it = set.keys(); it.hasNext(); ) {
                String key = it.next();
                values.put(key, set.get(key));
            }
        }
        JSONArray merged = active.getJSONArray("collections");
        JSONArray incoming = session.getJSONArray("collections");
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject collection = incoming.getJSONObject(i);
            int found = -1;
            for (int j = 0; j < merged.length(); j++) {
                if (merged.getJSONObject(j).getString("id").equals(collection.getString("id"))) found = j;
            }
            if (found < 0) merged.put(collection);
            else merged.put(found, collection);
        }
        String selected = values.optString("active_collection", "");
        if (!selected.isEmpty()) {
            boolean exists = false;
            for (int i = 0; i < merged.length(); i++) {
                exists |= merged.getJSONObject(i).getString("id").equals(selected);
            }
            require(exists, "invalid_request");
        }
        active.put("collections", merged);
        String revision = revision(active, source.read());
        // Recheck immediately before the single atomic replacement.
        require(state().getString("revision").equals(request.getString("expected_revision")), "revision_conflict");
        require(operations.length() < OPERATIONS, "storage_full");
        JSONObject result = new JSONObject().put("session_id", id).put("revision", revision);
        operations.put(op, result);
        active.put("last_operation", new JSONObject().put("request_id", op)
                .put("session_id", id).put("revision", revision));
        atomic(new File(root, "active.json"), active);
        return new JSONObject().put("revision", revision).put("operation_id", op);
    }

    synchronized JSONObject result(String operation) throws Exception {
        require(CompanionProtocol.hex(operation, 32), "invalid_request");
        JSONObject found = active().getJSONObject("operations").optJSONObject(operation);
        return found == null ? new JSONObject().put("status", "unknown")
                : copy(found).put("status", "activated");
    }

    synchronized JSONObject discard(String id) throws Exception {
        for (Iterator<String> it = writers.iterator(); it.hasNext(); ) {
            require(!it.next().startsWith(id + ":"), "busy");
        }
        JSONObject operations = active().getJSONObject("operations");
        for (Iterator<String> it = operations.keys(); it.hasNext(); ) {
            require(!id.equals(operations.getJSONObject(it.next()).getString("session_id")), "request_conflict");
        }
        File file = sessionFile(id);
        if (file.isFile()) {
            JSONObject value = read(file, new JSONObject());
            value.put("discarded", true);
            atomic(file, value);
        }
        return new JSONObject().put("status", "discarded");
    }

    /** The effective overrides, used by the launcher overlay. */
    synchronized JSONObject overrides() throws Exception {
        return copy(active().getJSONObject("settings"));
    }

    /** Files for one active collection; never exposed over the protocol. */
    synchronized List<File> selectedAssets(String selected) throws Exception {
        JSONObject active = active();
        List<File> result = new ArrayList<>();
        JSONArray collections = active.getJSONArray("collections");
        for (int i = 0; i < collections.length(); i++) {
            JSONObject collection = collections.getJSONObject(i);
            if (!selected.equals(collection.getString("id"))) continue;
            JSONArray list = collection.getJSONArray("assets");
            for (int j = 0; j < list.length(); j++) {
                String sha = list.getJSONObject(j).getString("sha256");
                if (!CompanionProtocol.hex(sha, 64)) continue;
                File file = new File(assets, sha);
                if (file.isFile() && !Files.isSymbolicLink(file.toPath())) result.add(file);
            }
        }
        return result;
    }
}

package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public class CompanionStoreTest {
    private static CompanionStore store(File root, JSONObject legacy) {
        return new CompanionStore(root, () -> legacy);
    }

    private static JSONObject legacy(String wallpaper, JSONObject unknown) throws Exception {
        JSONObject settings = new JSONObject().put("wallpaper", wallpaper);
        if (unknown != null) {
            for (java.util.Iterator<String> it = unknown.keys(); it.hasNext(); ) {
                String key = it.next();
                settings.put(key, unknown.get(key));
            }
        }
        return new JSONObject().put("settings", settings).put("preferences", new JSONObject().put("columns", 5));
    }

    @Test
    public void stateReportsSafeSettingsAndSeparateOverrides() throws Exception {
        File root = Files.createTempDirectory("companion-read").toFile();
        Files.write(new File(root, "active.json").toPath(), new JSONObject()
                .put("settings", new JSONObject().put("wallpaper_dim", 40))
                .put("collections", new JSONArray()).put("operations", new JSONObject())
                .toString().getBytes(StandardCharsets.UTF_8));
        JSONObject state = store(root, legacy("theme.jpg", null)).state();
        assertEquals(64, state.getString("revision").length());
        assertEquals(40, state.getJSONObject("settings").getInt("wallpaper_dim"));
        assertEquals(40, state.getJSONObject("overrides").getInt("wallpaper_dim"));
        assertEquals("theme.jpg", state.getJSONObject("settings").getString("wallpaper"));
        assertFalse(state.getJSONObject("overrides").has("wallpaper"));
    }

    @Test
    public void unknownAndCredentialSettingsAreNeverReadBackButChangeTheRevision() throws Exception {
        File root = Files.createTempDirectory("companion-read").toFile();
        JSONObject first = legacy("a.jpg", new JSONObject().put("private_api_key", "secret-value")
                .put("tv_only_setting", "keep"));
        JSONObject state = store(root, first).state();
        assertFalse(state.getJSONObject("settings").has("private_api_key"));
        assertFalse(state.getJSONObject("settings").has("tv_only_setting"));
        assertFalse(state.toString().contains("secret-value"));

        JSONObject second = legacy("a.jpg", new JSONObject().put("private_api_key", "secret-value")
                .put("tv_only_setting", "changed"));
        assertNotEquals(state.getString("revision"), store(root, second).state().getString("revision"));
    }

    @Test
    public void collectionsExposeOnlyOpaqueIdsHashesSizesAndPlayback() throws Exception {
        File root = Files.createTempDirectory("companion-read").toFile();
        JSONObject asset = new JSONObject().put("sha256", repeat('a', 64)).put("bytes", 123)
                .put("mime", "image/jpeg").put("path", "/sdcard/Android/data/com.gathin.tvhome/files/wallpapers/secret.jpg");
        JSONObject collection = new JSONObject().put("id", repeat('b', 32))
                .put("assets", new JSONArray().put(asset)).put("order", "sequential")
                .put("interval_minutes", 30).put("folder", "/home/owner/Pictures");
        Files.write(new File(root, "active.json").toPath(), new JSONObject()
                .put("settings", new JSONObject()).put("collections", new JSONArray().put(collection))
                .put("operations", new JSONObject()).toString().getBytes(StandardCharsets.UTF_8));
        JSONObject exposed = store(root, legacy("a.jpg", null)).state().getJSONArray("collections").getJSONObject(0);
        assertEquals(4, exposed.length());
        assertTrue(exposed.has("assets") && exposed.has("id")
                && exposed.has("interval_minutes") && exposed.has("order"));
        assertFalse(exposed.has("folder"));
        assertFalse(exposed.getJSONArray("assets").getJSONObject(0).has("path"));
        assertFalse(exposed.toString().contains("/sdcard"));
        assertFalse(exposed.toString().contains("Pictures"));
    }

    @Test
    public void malformedCollectionRecordsAreSkippedRatherThanEchoed() throws Exception {
        File root = Files.createTempDirectory("companion-read").toFile();
        JSONObject collection = new JSONObject().put("id", "not-an-id")
                .put("folder", "/private/path").put("order", "sequential").put("interval_minutes", 5)
                .put("assets", new JSONArray());
        Files.write(new File(root, "active.json").toPath(), new JSONObject()
                .put("settings", new JSONObject()).put("collections", new JSONArray().put(collection))
                .put("operations", new JSONObject()).toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(0, store(root, legacy("a.jpg", null)).state().getJSONArray("collections").length());
    }

    @Test
    public void capabilitiesAdvertiseOnlyImplementedMandatoryFeatures() throws Exception {
        JSONObject capabilities = CompanionStore.capabilities("2.5");
        assertEquals(1, capabilities.getInt("protocol_version"));
        assertEquals("2.5", capabilities.getString("launcher_version"));
        JSONArray features = capabilities.getJSONArray("features");
        assertEquals("[\"collections_v1\",\"atomic_activation_v1\",\"revision_checks_v1\",\"readback_v1\"]",
                features.toString());
        assertFalse(features.toString().contains("theme_schedules_v1"));
        assertFalse(features.toString().contains("private_credentials_v1"));
        JSONObject limits = capabilities.getJSONObject("limits");
        assertEquals(CompanionStore.ASSET_BYTES, limits.getLong("asset_bytes"));
        assertEquals(CompanionStore.COLLECTION_BYTES, limits.getLong("collection_bytes"));
        assertEquals(CompanionStore.ASSET_COUNT, limits.getInt("asset_count"));
    }

    @Test
    public void themeSchedulesAreAdvertisedOnlyWhenThisBuildEnablesThem() throws Exception {
        assertFalse(CompanionStore.capabilities("2.5").getJSONArray("features")
                .toString().contains("theme_schedules_v1"));
        assertTrue(CompanionStore.capabilities("2.5", true).getJSONArray("features")
                .toString().contains("theme_schedules_v1"));
    }

    @Test
    public void scheduleReadbackStaysOffUntilExplicitlyEnabled() throws Exception {
        File root = Files.createTempDirectory("companion-schedule").toFile();
        JSONObject stored = new JSONObject().put("theme_schedule", schedule())
                .put("theme_schedule_enabled", false);
        writeActive(root, stored);
        JSONObject disabled = store(root, legacy("a.jpg", null)).state().getJSONObject("schedule");
        assertFalse(disabled.getBoolean("enabled"));

        stored.put("theme_schedule_enabled", true);
        writeActive(root, stored);
        JSONObject enabled = store(root, legacy("a.jpg", null)).state().getJSONObject("schedule");
        assertTrue(enabled.getBoolean("enabled"));
        assertFalse(store(root, legacy("a.jpg", null)).state(false)
                .getJSONObject("schedule").getBoolean("enabled"));
        assertEquals("UTC", enabled.getString("timezone"));
        assertEquals("evening", enabled.getJSONObject("last_evaluated").getString("rule_id"));
        assertTrue(enabled.getJSONArray("upcoming").length() > 0);
        assertEquals("evening", enabled.getJSONArray("upcoming").getJSONObject(0).getString("rule_id"));
    }

    @Test
    public void invalidScheduleReadbackIsMarkedAndNeverAffectsSettings() throws Exception {
        File root = Files.createTempDirectory("companion-schedule").toFile();
        writeActive(root, new JSONObject().put("theme_schedule_enabled", true)
                .put("theme_schedule", new JSONObject().put("version", 1).put("timezone", "Not/AZone")));
        JSONObject state = store(root, legacy("a.jpg", null)).state();
        assertFalse(state.getJSONObject("schedule").getBoolean("enabled"));
        assertTrue(state.getJSONObject("schedule").getBoolean("invalid"));
        assertEquals("a.jpg", state.getJSONObject("settings").getString("wallpaper"));
    }

    @Test
    public void schedulePatchIsRejectedUnlessTheFeatureIsAdvertised() throws Exception {
        JSONObject patch = new JSONObject().put("set", new JSONObject().put("theme_schedule", schedule()));
        expectFailure(() -> CompanionStore.patch(patch, false), "invalid_request");
        CompanionStore.patch(patch, true);
    }

    @Test
    public void invalidSchedulePatchIsRejectedWithoutSavingAnything() throws Exception {
        JSONObject patch = new JSONObject().put("set", new JSONObject().put("theme_schedule",
                new JSONObject().put("version", 1).put("timezone", "UTC")));
        expectFailure(() -> CompanionStore.patch(patch, true), "invalid_request");
    }

    @Test
    public void onTvDisableKeepsTheSavedScheduleForALaterReEnable() throws Exception {
        File root = Files.createTempDirectory("companion-schedule").toFile();
        CompanionStore store = store(root, legacy("a.jpg", null));
        writeActive(root, new JSONObject().put("theme_schedule", schedule())
                .put("theme_schedule_enabled", true));
        assertTrue(store.state().getJSONObject("schedule").getBoolean("enabled"));

        store.setScheduleEnabled(false);
        assertFalse(store.state().getJSONObject("schedule").getBoolean("enabled"));
        assertTrue(store.overrides().has("theme_schedule"));

        // Survives a fresh store instance: the override is durable, not process state.
        CompanionStore reopened = store(root, legacy("a.jpg", null));
        assertFalse(reopened.state().getJSONObject("schedule").getBoolean("enabled"));
        reopened.setScheduleEnabled(true);
        assertTrue(reopened.state().getJSONObject("schedule").getBoolean("enabled"));
    }

    @Test
    public void stateDoesNotExposeSourcePathsInAnErrorOrARevision() throws Exception {
        File root = Files.createTempDirectory("companion-read").toFile();
        JSONObject state = store(root, legacy("a.jpg", null)).state();
        assertFalse(state.toString().contains(root.getAbsolutePath()));
        assertTrue(state.getString("revision").matches("[a-f0-9]{64}"));
    }

    private static JSONObject schedule() throws Exception {
        return new JSONObject().put("version", 1).put("timezone", "UTC")
                .put("location", new JSONObject().put("latitude", 0).put("longitude", 0))
                .put("default_theme", new JSONObject().put("background_color", "#000000"))
                .put("rules", new JSONArray().put(new JSONObject().put("id", "evening")
                        .put("priority", 10)
                        .put("trigger", new JSONObject().put("type", "fixed").put("time", "18:30"))
                        .put("theme", new JSONObject().put("accent_color", "#ffffff"))));
    }

    private static void writeActive(File root, JSONObject settings) throws Exception {
        Files.write(new File(root, "active.json").toPath(), new JSONObject()
                .put("settings", settings).put("collections", new JSONArray())
                .put("operations", new JSONObject()).toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void expectFailure(Throwing action, String code) throws Exception {
        try {
            action.run();
            org.junit.Assert.fail("expected " + code);
        } catch (CompanionProtocol.Failure failure) {
            assertEquals(code, failure.code);
        }
    }

    private interface Throwing { void run() throws Exception; }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append(value);
        return text.toString();
    }
}

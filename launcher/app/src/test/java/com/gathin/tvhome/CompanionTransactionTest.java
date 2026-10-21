package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public class CompanionTransactionTest {
    private static final String SESSION = "0123456789abcdef0123456789abcdef";
    private static final String OP = "fedcba9876543210fedcba9876543210";
    private static final String COLLECTION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static final class Fixture {
        final File root;
        final CompanionStore store;
        JSONObject theme;

        Fixture() throws Exception {
            root = Files.createTempDirectory("companion-write").toFile();
            theme = new JSONObject().put("wallpaper", "theme.jpg").put("unknown_theme_key", "keep")
                    .put("wallpaper_rotation_minutes", 30);
            store = new CompanionStore(root,
                    () -> new JSONObject().put("settings", theme).put("preferences", new JSONObject()),
                    directory -> {},
                    (file, mime) -> {});
        }

        String revision() throws Exception {
            return store.state().getString("revision");
        }

        String sha(String text) {
            return CompanionProtocol.sha256(text.getBytes(StandardCharsets.UTF_8));
        }

        JSONObject asset(String text) throws Exception {
            return new JSONObject().put("sha256", sha(text))
                    .put("bytes", text.getBytes(StandardCharsets.UTF_8).length)
                    .put("mime", "image/jpeg");
        }

        JSONObject request(String id, String expected, JSONObject patch, JSONArray collections) throws Exception {
            return new JSONObject().put("protocol_version", 1).put("request_id", id)
                    .put("expected_revision", expected).put("patch", patch)
                    .put("collections", collections);
        }

        JSONArray collection(JSONObject asset) throws Exception {
            return new JSONArray().put(new JSONObject().put("id", COLLECTION)
                    .put("assets", new JSONArray().put(asset))
                    .put("order", "sequential").put("interval_minutes", 30));
        }

        void send(String text) throws Exception {
            store.receive(SESSION, sha(text),
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    public void stageValidateActivateAppliesExactlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        JSONObject asset = fixture.asset("image-bytes");
        JSONObject patch = new JSONObject().put("set", new JSONObject()
                .put("wallpaper_dim", 40).put("wallpaper_order", "sequential")
                .put("active_collection", COLLECTION));
        JSONObject request = fixture.request(SESSION, fixture.revision(), patch,
                fixture.collection(asset));

        assertEquals(SESSION, fixture.store.stage(request).getString("session_id"));
        assertTrue(fixture.store.validate(SESSION).getBoolean("ready") == false);
        fixture.send("image-bytes");
        JSONObject checked = fixture.store.validate(SESSION);
        assertTrue(checked.getBoolean("ready"));
        assertEquals(0, checked.getJSONArray("missing_assets").length());

        JSONObject activated = fixture.store.activate(new JSONObject().put("protocol_version", 1)
                .put("request_id", OP).put("session_id", SESSION)
                .put("expected_revision", fixture.revision()));
        assertEquals(OP, activated.getString("operation_id"));
        assertEquals(64, activated.getString("revision").length());

        JSONObject state = fixture.store.state();
        assertEquals(40, state.getJSONObject("settings").getInt("wallpaper_dim"));
        assertEquals("sequential", state.getJSONObject("overrides").getString("wallpaper_order"));
        assertEquals(COLLECTION, state.getString("active_collection"));
        // Unknown theme settings stay on the TV but are not part of the safe readback.
        assertFalse(state.getJSONObject("settings").has("unknown_theme_key"));
        assertEquals(1, state.getJSONArray("collections").length());
        assertEquals(OP, state.getJSONObject("operation").getString("request_id"));
        assertFalse(state.toString().contains(fixture.root.getAbsolutePath()));

        JSONObject result = fixture.store.result(OP);
        assertEquals("activated", result.getString("status"));
        assertEquals(SESSION, result.getString("session_id"));
    }

    @Test
    public void stageIsIdempotentAndRejectsAChangedPayload() throws Exception {
        Fixture fixture = new Fixture();
        JSONObject request = fixture.request(SESSION, fixture.revision(),
                new JSONObject().put("set", new JSONObject().put("wallpaper_dim", 10)),
                new JSONArray());
        assertEquals(SESSION, fixture.store.stage(request).getString("session_id"));
        assertEquals(SESSION, fixture.store.stage(request).getString("session_id"));

        JSONObject changed = fixture.request(SESSION, fixture.revision(),
                new JSONObject().put("set", new JSONObject().put("wallpaper_dim", 11)),
                new JSONArray());
        assertFailure("request_conflict", () -> fixture.store.stage(changed));
    }

    @Test
    public void staleExpectedRevisionChangesNothing() throws Exception {
        Fixture fixture = new Fixture();
        File active = new File(fixture.root, "active.json");
        byte[] before = active.isFile() ? Files.readAllBytes(active.toPath()) : null;
        JSONObject request = fixture.request(SESSION, "0".repeat(64),
                new JSONObject().put("set", new JSONObject().put("wallpaper_dim", 10)),
                new JSONArray());
        assertFailure("revision_conflict", () -> fixture.store.stage(request));
        assertFalse(new File(fixture.root, "sessions/" + SESSION + ".json").exists());
        if (before == null) assertFalse(active.exists());
        else assertTrue(Arrays.equals(before, Files.readAllBytes(active.toPath())));
    }

    @Test
    public void invalidPatchesAndUnsafeIdentifiersAreRejectedBeforeAnyWrite() throws Exception {
        Fixture fixture = new Fixture();
        String revision = fixture.revision();
        assertFailure("invalid_request", () -> fixture.store.stage(fixture.request(SESSION, revision,
                new JSONObject().put("set", new JSONObject().put("wallpaper_rotation_minutes", 0)),
                new JSONArray())));
        assertFailure("invalid_request", () -> fixture.store.stage(fixture.request(SESSION, revision,
                new JSONObject().put("set", new JSONObject().put("wallpaper_order", "random")),
                new JSONArray())));
        assertFailure("invalid_request", () -> fixture.store.stage(fixture.request(SESSION, revision,
                new JSONObject().put("set", new JSONObject().put("accent_color", "red")),
                new JSONArray())));
        assertFailure("unsupported_feature", () -> fixture.store.stage(fixture.request(SESSION, revision,
                new JSONObject().put("set", new JSONObject().put("credentials", "secret")),
                new JSONArray())));
        assertFailure("invalid_request", () -> fixture.store.stage(fixture.request(SESSION, revision,
                new JSONObject().put("set", new JSONObject().put("wallpaper_dim", 10))
                        .put("remove", new JSONArray().put("wallpaper_dim")),
                new JSONArray())));
        assertFailure("invalid_request", () -> fixture.store.stage(fixture.request("../escape", revision,
                new JSONObject().put("set", new JSONObject()), new JSONArray())));
        assertFalse(new File(fixture.root, "sessions").listFiles().length > 0);
    }

    @Test
    public void truncatedWrongHashAndOversizedAssetsAreDiscarded() throws Exception {
        Fixture fixture = new Fixture();
        JSONObject asset = fixture.asset("the-real-bytes");
        fixture.store.stage(fixture.request(SESSION, fixture.revision(),
                new JSONObject().put("set", new JSONObject()), fixture.collection(asset)));

        assertFailure("invalid_asset", () -> fixture.store.receive(SESSION, fixture.sha("the-real-bytes"),
                new ByteArrayInputStream("short".getBytes(StandardCharsets.UTF_8))));
        assertFailure("invalid_asset", () -> fixture.store.receive(SESSION, fixture.sha("the-real-bytes"),
                new ByteArrayInputStream("wrong-bytes!!".getBytes(StandardCharsets.UTF_8))));
        assertFalse(new File(fixture.root, "assets/" + fixture.sha("the-real-bytes")).exists());
        assertEquals(1, fixture.store.validate(SESSION).getJSONArray("missing_assets").length());

        JSONObject huge = new JSONObject().put("sha256", fixture.sha("x"))
                .put("bytes", CompanionStore.ASSET_BYTES + 1).put("mime", "image/jpeg");
        String other = "dddddddddddddddddddddddddddddddd";
        assertFailure("invalid_asset", () -> fixture.store.stage(fixture.request(other, fixture.revision(),
                new JSONObject().put("set", new JSONObject()), fixture.collection(huge))));
    }

    @Test
    public void activationIsIdempotentAndMissingAssetsLeaveTheOldState() throws Exception {
        Fixture fixture = new Fixture();
        JSONObject asset = fixture.asset("bytes");
        JSONObject request = fixture.request(SESSION, fixture.revision(),
                new JSONObject().put("set", new JSONObject().put("wallpaper_dim", 20)),
                fixture.collection(asset));
        fixture.store.stage(request);
        JSONObject activate = new JSONObject().put("protocol_version", 1)
                .put("request_id", OP).put("session_id", SESSION)
                .put("expected_revision", fixture.revision());

        assertFailure("missing_assets", () -> fixture.store.activate(activate));
        assertFalse(new File(fixture.root, "active.json").exists());

        fixture.send("bytes");
        JSONObject first = fixture.store.activate(activate);
        JSONObject second = fixture.store.activate(activate);
        assertEquals(first.getString("revision"), second.getString("revision"));
        assertEquals(OP, fixture.store.state().getJSONObject("operation").getString("request_id"));
    }

    @Test
    public void activationRejectsAnUnsolicitedAssetAndStaleRevision() throws Exception {
        Fixture fixture = new Fixture();
        JSONObject asset = fixture.asset("bytes");
        fixture.store.stage(fixture.request(SESSION, fixture.revision(),
                new JSONObject().put("set", new JSONObject()), fixture.collection(asset)));
        assertFailure("invalid_asset", () -> fixture.store.receive(SESSION,
                fixture.sha("not-declared"), new ByteArrayInputStream("not-declared".getBytes(StandardCharsets.UTF_8))));

        fixture.send("bytes");
        JSONObject stale = new JSONObject().put("protocol_version", 1)
                .put("request_id", OP).put("session_id", SESSION)
                .put("expected_revision", "0".repeat(64));
        assertFailure("revision_conflict", () -> fixture.store.activate(stale));
        assertFalse(new File(fixture.root, "active.json").exists());
    }

    @Test
    public void resultDistinguishesUnknownFromActivated() throws Exception {
        Fixture fixture = new Fixture();
        assertEquals("unknown", fixture.store.result(OP).getString("status"));
        JSONObject asset = fixture.asset("bytes");
        fixture.store.stage(fixture.request(SESSION, fixture.revision(),
                new JSONObject().put("set", new JSONObject()), fixture.collection(asset)));
        fixture.send("bytes");
        fixture.store.activate(new JSONObject().put("protocol_version", 1).put("request_id", OP)
                .put("session_id", SESSION).put("expected_revision", fixture.revision()));
        assertEquals("activated", fixture.store.result(OP).getString("status"));
    }

    @Test
    public void discardOnlyRemovesUnactivatedStagingAndIsIdempotent() throws Exception {
        Fixture fixture = new Fixture();
        JSONObject asset = fixture.asset("bytes");
        fixture.store.stage(fixture.request(SESSION, fixture.revision(),
                new JSONObject().put("set", new JSONObject()), fixture.collection(asset)));
        assertEquals("discarded", fixture.store.discard(SESSION).getString("status"));
        assertEquals("discarded", fixture.store.discard(SESSION).getString("status"));
        assertFailure("unknown_session", () -> fixture.store.session(SESSION));

        Fixture activated = new Fixture();
        JSONObject live = activated.asset("live");
        activated.store.stage(activated.request(SESSION, activated.revision(),
                new JSONObject().put("set", new JSONObject()), activated.collection(live)));
        activated.send("live");
        activated.store.activate(new JSONObject().put("protocol_version", 1).put("request_id", OP)
                .put("session_id", SESSION).put("expected_revision", activated.revision()));
        assertFailure("request_conflict", () -> activated.store.discard(SESSION));
        assertEquals(1, activated.store.state().getJSONArray("collections").length());
    }

    @Test
    public void removingAnOverrideRevealsTheThemeValueAgain() throws Exception {
        Fixture fixture = new Fixture();
        JSONObject setRequest = fixture.request(SESSION, fixture.revision(),
                new JSONObject().put("set", new JSONObject().put("wallpaper_dim", 30))
                        .put("remove", new JSONArray()),
                new JSONArray());
        fixture.store.stage(setRequest);
        fixture.store.activate(new JSONObject().put("protocol_version", 1).put("request_id", OP)
                .put("session_id", SESSION).put("expected_revision", fixture.revision()));
        assertEquals(30, fixture.store.state().getJSONObject("settings").getInt("wallpaper_dim"));

        String removeSession = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String removeOp = "cccccccccccccccccccccccccccccccc";
        fixture.store.stage(fixture.request(removeSession, fixture.revision(),
                new JSONObject().put("set", new JSONObject()).put("remove", new JSONArray().put("wallpaper_dim")),
                new JSONArray()));
        fixture.store.activate(new JSONObject().put("protocol_version", 1).put("request_id", removeOp)
                .put("session_id", removeSession).put("expected_revision", fixture.revision()));
        JSONObject state = fixture.store.state();
        assertFalse(state.getJSONObject("overrides").has("wallpaper_dim"));
        assertFalse(state.getJSONObject("settings").has("wallpaper_dim"));
        assertEquals("theme.jpg", state.getJSONObject("settings").getString("wallpaper"));
        assertFalse(state.getJSONObject("settings").has("unknown_theme_key"));
    }

    @Test
    public void noSecretOrSourcePathAppearsInRepliesOrMetadata() throws Exception {
        Fixture fixture = new Fixture();
        fixture.theme.put("private_api_key", "super-secret");
        JSONObject asset = fixture.asset("bytes");
        fixture.store.stage(fixture.request(SESSION, fixture.revision(),
                new JSONObject().put("set", new JSONObject()), fixture.collection(asset)));
        fixture.send("bytes");
        fixture.store.activate(new JSONObject().put("protocol_version", 1).put("request_id", OP)
                .put("session_id", SESSION).put("expected_revision", fixture.revision()));
        String metadata = new String(Files.readAllBytes(
                new File(fixture.root, "sessions/" + SESSION + ".json").toPath()), StandardCharsets.UTF_8);
        String state = fixture.store.state().toString();
        for (String text : Arrays.asList(metadata, state, fixture.store.result(OP).toString())) {
            assertFalse(text.contains("super-secret"));
            assertFalse(text.contains(fixture.root.getAbsolutePath()));
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void assertFailure(String code, Action action) {
        try {
            action.run();
            throw new AssertionError("expected " + code);
        } catch (CompanionProtocol.Failure failure) {
            assertEquals(code, failure.code);
        } catch (Exception other) {
            throw new AssertionError("expected " + code + " but got " + other, other);
        }
    }
}

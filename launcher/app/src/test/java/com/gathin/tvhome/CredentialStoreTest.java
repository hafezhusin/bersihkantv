package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class CredentialStoreTest {
    /** Deterministic stand-in for the Android Keystore; the store only relies on seal/open. */
    private static final class FakeVault implements CredentialStore.Vault {
        boolean failSeal;
        boolean corruptOpen;

        @Override public byte[] seal(byte[] plaintext) throws Exception {
            if (failSeal) throw new IllegalStateException("keystore unavailable");
            byte[] sealed = new byte[plaintext.length + 1];
            sealed[0] = 42;
            for (int i = 0; i < plaintext.length; i++) sealed[i + 1] = (byte) ~plaintext[i];
            return sealed;
        }

        @Override public byte[] open(byte[] sealed) throws Exception {
            if (corruptOpen || sealed.length == 0 || sealed[0] != 42) {
                throw new IllegalArgumentException("bad ciphertext");
            }
            byte[] plaintext = new byte[sealed.length - 1];
            for (int i = 1; i < sealed.length; i++) plaintext[i - 1] = (byte) ~sealed[i];
            return plaintext;
        }
    }

    private static JSONObject secret(String value) throws Exception {
        return new JSONObject().put("access_key", value).put("collections", "abc,def");
    }

    private static File root() throws Exception {
        return Files.createTempDirectory("credentials").toFile();
    }

    @Test public void credentialRoundTripsAndNeverAppearsInPlaintextOnDisk() throws Exception {
        File root = root();
        CredentialStore store = new CredentialStore(root, new FakeVault());
        store.set("unsplash", secret("super-secret-key"));
        assertTrue(store.configured("unsplash"));
        assertEquals("super-secret-key", store.read("unsplash").getString("access_key"));

        File sealed = new File(root, "unsplash.enc");
        assertTrue(sealed.isFile());
        String raw = new String(Files.readAllBytes(sealed.toPath()), StandardCharsets.UTF_8);
        assertFalse(raw.contains("super-secret-key"));
        assertFalse(raw.contains("access_key"));
        assertFalse(new File(root, "unsplash.enc.tmp").exists());
    }

    @Test public void rotationReplacesTheSecretAndInvalidatesTheOldValue() throws Exception {
        CredentialStore store = new CredentialStore(root(), new FakeVault());
        store.set("unsplash", secret("first"));
        store.set("unsplash", secret("second"));
        assertEquals("second", store.read("unsplash").getString("access_key"));
    }

    @Test public void interruptedUpdateLeavesThePreviousCredentialUsable() throws Exception {
        FakeVault vault = new FakeVault();
        CredentialStore store = new CredentialStore(root(), vault);
        store.set("unsplash", secret("first"));
        vault.failSeal = true;
        try {
            store.set("unsplash", secret("second"));
            org.junit.Assert.fail("seal failure must propagate");
        } catch (IllegalStateException expected) {
            // interrupted before the atomic move
        }
        assertTrue(store.configured("unsplash"));
        assertEquals("first", store.read("unsplash").getString("access_key"));
    }

    @Test public void unreadableCiphertextIsReportedAsNotConfigured() throws Exception {
        FakeVault vault = new FakeVault();
        CredentialStore store = new CredentialStore(root(), vault);
        store.set("unsplash", secret("first"));
        vault.corruptOpen = true;
        assertFalse(store.configured("unsplash"));
    }

    @Test public void revokeRemovesTheEncryptedMaterial() throws Exception {
        File root = root();
        CredentialStore store = new CredentialStore(root, new FakeVault());
        store.set("unsplash", secret("first"));
        assertTrue(store.revoke("unsplash"));
        assertFalse(new File(root, "unsplash.enc").exists());
        assertFalse(store.configured("unsplash"));
    }

    @Test public void unknownProviderNestedSecretAndOversizeValuesAreRejected() throws Exception {
        CredentialStore store = new CredentialStore(root(), new FakeVault());
        expectInvalid(() -> store.set("evil", secret("value")));
        expectInvalid(() -> store.set("unsplash", new JSONObject()
                .put("access_key", new JSONObject().put("nested", "value"))));
        expectInvalid(() -> store.set("unsplash", new JSONObject()
                .put("access_key", repeat('a', 2049))));
        expectInvalid(() -> store.set("unsplash", new JSONObject()));
    }

    @Test public void statusReportsHealthAndAttributionWithoutSecrets() throws Exception {
        ProviderCredentials credentials = new ProviderCredentials(root(), new FakeVault());
        JSONObject idle = credentials.status("unsplash");
        assertFalse(idle.getBoolean("configured"));
        assertFalse(idle.getBoolean("enabled"));
        assertEquals("idle", idle.getString("state"));

        expectInvalid(() -> credentials.enable("unsplash", true));
        credentials.set("unsplash", secret("super-secret-key"));
        assertTrue(credentials.status("unsplash").getBoolean("configured"));
        credentials.enable("unsplash", true);
        credentials.recordFailure("unsplash", "rate_limited");
        JSONObject limited = credentials.status("unsplash");
        assertTrue(limited.getBoolean("enabled"));
        assertEquals("rate_limited", limited.getString("state"));
        assertEquals("rate_limited", limited.getJSONObject("last_failure").getString("code"));
        assertFalse(limited.toString().contains("super-secret-key"));

        credentials.recordSuccess("unsplash", "photo_1");
        JSONObject ok = credentials.status("unsplash");
        assertEquals("ok", ok.getString("state"));
        assertEquals("photo_1", ok.getJSONObject("attribution").getString("asset"));
        assertFalse(ok.toString().contains("super-secret-key"));

        credentials.recordFailure("unsplash", "invalid_media");
        assertEquals("invalid_media", credentials.status("unsplash").getString("state"));
        assertTrue(credentials.retrievable("unsplash"));
    }

    @Test public void revokeClearsStatusAndEveryProviderIsReported() throws Exception {
        ProviderCredentials credentials = new ProviderCredentials(root(), new FakeVault());
        credentials.set("unsplash", secret("super-secret-key"));
        credentials.enable("unsplash", true);
        credentials.recordSuccess("unsplash", "photo_1");
        credentials.revoke("unsplash");

        JSONObject status = credentials.status("unsplash");
        assertFalse(status.getBoolean("configured"));
        assertFalse(status.getBoolean("enabled"));
        assertFalse(status.has("attribution"));
        assertFalse(credentials.retrievable("unsplash"));

        JSONObject all = credentials.status(null).getJSONObject("providers");
        assertTrue(all.has("unsplash"));
        assertFalse(all.toString().contains("super-secret-key"));
    }

    @Test public void onlyTheKnownProviderIsAccepted() throws Exception {
        ProviderCredentials credentials = new ProviderCredentials(root(), new FakeVault());
        expectInvalid(() -> credentials.status("other"));
        expectInvalid(() -> credentials.revoke("other"));
    }

    private static void expectInvalid(Throwing action) throws Exception {
        try {
            action.run();
            org.junit.Assert.fail("expected rejection");
        } catch (CompanionProtocol.Failure failure) {
            assertEquals("invalid_request", failure.code);
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) text.append(value);
        return text.toString();
    }

    private interface Throwing { void run() throws Exception; }
}

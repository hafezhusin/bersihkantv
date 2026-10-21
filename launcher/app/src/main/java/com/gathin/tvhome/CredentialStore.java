package com.gathin.tvhome;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Encrypted, app-private storage for provider credentials. A credential is a flat JSON object of
 * short scalar values, sealed by {@link Vault} before it ever touches the filesystem. There is no
 * readback method on the protocol side: only the retrieval code may call {@link #read(String)}.
 *
 * The file name carries no secret and the directory is the launcher's private files directory, so
 * Android removes both on uninstall. A failed or interrupted write leaves the previous credential
 * untouched because the new value is only moved into place after it decrypts back to the same bytes.
 */
final class CredentialStore {
    /** Providers the launcher is allowed to hold credentials for. */
    static final Set<String> PROVIDERS = new HashSet<>(Arrays.asList("unsplash"));
    static final int MAX_CREDENTIAL_BYTES = 4096;

    interface Vault {
        byte[] seal(byte[] plaintext) throws Exception;

        byte[] open(byte[] sealed) throws Exception;
    }

    private final File root;
    private final Vault vault;

    CredentialStore(File root, Vault vault) {
        this.root = root;
        this.vault = vault;
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IllegalStateException("credential storage unavailable");
        }
    }

    static void requireProvider(String provider) throws CompanionProtocol.Failure {
        if (provider == null || !PROVIDERS.contains(provider)) {
            throw new CompanionProtocol.Failure("invalid_request");
        }
    }

    private File file(String provider) {
        return new File(root, provider + ".enc");
    }

    /** True only when the file exists and the vault can actually decrypt it. */
    synchronized boolean configured(String provider) {
        try {
            read(provider);
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    /**
     * Replaces the credential atomically. The old secret stays usable until the new one has been
     * sealed, opened again and moved into place; a failure before that point changes nothing.
     */
    synchronized void set(String provider, JSONObject credential) throws Exception {
        requireProvider(provider);
        validate(credential);
        byte[] plaintext = CompanionProtocol.canonical(credential).getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > MAX_CREDENTIAL_BYTES) throw new CompanionProtocol.Failure("invalid_request");
        byte[] sealed = vault.seal(plaintext);
        // Prove the new secret is readable before invalidating the previous value.
        if (!Arrays.equals(plaintext, vault.open(sealed))) {
            throw new CompanionProtocol.Failure("internal_error");
        }
        File target = file(provider);
        File temp = new File(root, provider + ".enc.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(sealed);
            out.getFD().sync();
        }
        Files.move(temp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(temp.toPath());
    }

    /** Retrieval-only. Never expose this object through the companion protocol. */
    synchronized JSONObject read(String provider) throws Exception {
        requireProvider(provider);
        File file = file(provider);
        if (!file.isFile() || file.length() == 0 || file.length() > MAX_CREDENTIAL_BYTES + 256) {
            throw new CompanionProtocol.Failure("invalid_request");
        }
        JSONObject credential = new JSONObject(new String(
                vault.open(Files.readAllBytes(file.toPath())), StandardCharsets.UTF_8));
        validate(credential);
        return credential;
    }

    /** One action clears the secret; status and attribution are cleared by {@link ProviderStatusStore}. */
    synchronized boolean revoke(String provider) throws Exception {
        requireProvider(provider);
        return Files.deleteIfExists(file(provider).toPath());
    }

    private static void validate(JSONObject credential) throws CompanionProtocol.Failure {
        if (credential == null || credential.length() == 0 || credential.length() > 16) {
            throw new CompanionProtocol.Failure("invalid_request");
        }
        for (Iterator<String> it = credential.keys(); it.hasNext(); ) {
            String key = it.next();
            if (!key.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new CompanionProtocol.Failure("invalid_request");
            }
            Object value = credential.opt(key);
            // Flat scalars only: provider payloads never need nested objects or arrays.
            if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
                throw new CompanionProtocol.Failure("invalid_request");
            }
            if (value instanceof String && ((String) value).length() > 2048) {
                throw new CompanionProtocol.Failure("invalid_request");
            }
        }
        try {
            if (CompanionProtocol.canonical(credential).length() > MAX_CREDENTIAL_BYTES) {
                throw new CompanionProtocol.Failure("invalid_request");
            }
        } catch (JSONException impossible) {
            throw new CompanionProtocol.Failure("invalid_request");
        }
    }
}

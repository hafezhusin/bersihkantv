package com.gathin.tvhome;

import org.json.JSONObject;

import java.io.File;

/**
 * Narrow companion-facing facade over the encrypted credential store and safe status record.
 *
 * No method returns a secret. {@link #credential(String)} is for retrieval code only and must never
 * be reached from the companion IPC switch.
 */
final class ProviderCredentials {
    private final CredentialStore secrets;
    private final ProviderStatusStore status;

    ProviderCredentials(File root, CredentialStore.Vault vault) {
        this.secrets = new CredentialStore(new File(root, "secrets"), vault);
        this.status = new ProviderStatusStore(new File(root, "status"));
    }

    /** Set or rotate. Rotation replaces the sealed value and keeps the enabled flag unchanged. */
    synchronized JSONObject set(String provider, JSONObject credential) throws Exception {
        CredentialStore.requireProvider(provider);
        secrets.set(provider, credential);
        return status.withConfigured(provider, true);
    }

    /** Enabling is a separate owner action and requires a credential that actually decrypts. */
    synchronized JSONObject enable(String provider, boolean enabled) throws Exception {
        CredentialStore.requireProvider(provider);
        if (enabled && !secrets.configured(provider)) {
            throw new CompanionProtocol.Failure("invalid_request");
        }
        status.setEnabled(provider, enabled);
        return status.withConfigured(provider, secrets.configured(provider));
    }

    /** One action clears the secret, health, attribution and pending retrieval state. */
    synchronized JSONObject revoke(String provider) throws Exception {
        CredentialStore.requireProvider(provider);
        secrets.revoke(provider);
        status.revoke(provider);
        return status.withConfigured(provider, false);
    }

    /** Safe status for one provider, or every approved provider when {@code provider} is null. */
    synchronized JSONObject status(String provider) throws Exception {
        if (provider != null) {
            CredentialStore.requireProvider(provider);
            return status.withConfigured(provider, secrets.configured(provider));
        }
        JSONObject all = new JSONObject();
        for (String known : CredentialStore.PROVIDERS) {
            all.put(known, status.withConfigured(known, secrets.configured(known)));
        }
        return new JSONObject().put("providers", all);
    }

    /** Retrieval-only secret access. Never call from an IPC reply path. */
    synchronized JSONObject credential(String provider) throws Exception {
        return secrets.read(provider);
    }

    /** Retrieval gate: both a usable credential and an explicit owner enable are required. */
    synchronized boolean retrievable(String provider) throws Exception {
        return secrets.configured(provider) && status.status(provider).optBoolean("enabled", false);
    }

    synchronized void recordSuccess(String provider, String asset) {
        status.recordSuccess(provider, asset);
    }

    synchronized void recordFailure(String provider, String code) {
        status.recordFailure(provider, code);
    }

    static boolean knownProvider(String provider) {
        return provider != null && CredentialStore.PROVIDERS.contains(provider);
    }
}

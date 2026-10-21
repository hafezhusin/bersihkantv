package com.gathin.tvhome;

/**
 * The companion boundary is ADB: only the Android shell UID (2000) and the launcher's own process may
 * call the provider. The check runs before any file is read or any caller identity is cleared.
 */
final class CompanionAccess {
    static final int SHELL_UID = 2000;

    private CompanionAccess() {}

    static boolean allowed(int callingUid, int ownUid) {
        return callingUid == SHELL_UID || callingUid == ownUid;
    }
}

package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CompanionAccessTest {
    private static final int OWN_UID = 10123;

    @Test
    public void acceptsTheShellUid() {
        assertTrue(CompanionAccess.allowed(2000, OWN_UID));
    }

    @Test
    public void acceptsTheLaunchersOwnUid() {
        assertTrue(CompanionAccess.allowed(OWN_UID, OWN_UID));
    }

    @Test
    public void rejectsAnOrdinaryApp() {
        assertFalse(CompanionAccess.allowed(10456, OWN_UID));
        assertFalse(CompanionAccess.allowed(0, OWN_UID));
        assertFalse(CompanionAccess.allowed(1000, OWN_UID));
    }
}

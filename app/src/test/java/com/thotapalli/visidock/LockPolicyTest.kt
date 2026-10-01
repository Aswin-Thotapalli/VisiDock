package com.thotapalli.visidock

import org.junit.Assert.*
import org.junit.Test

class LockPolicyTest {
    @Test fun coldEntryAlwaysLocksWhenEnabledButDisabledDoesNot() {
        assertTrue(LockPolicy.needsAuthentication(true,true,0,300))
        assertFalse(LockPolicy.needsAuthentication(false,true,999999,0))
    }
    @Test fun resumeHonorsTheExactConfiguredBoundary() {
        assertFalse(LockPolicy.needsAuthentication(true,false,29999,30))
        assertTrue(LockPolicy.needsAuthentication(true,false,30000,30))
        assertTrue(LockPolicy.needsAuthentication(true,false,0,0))
    }
}

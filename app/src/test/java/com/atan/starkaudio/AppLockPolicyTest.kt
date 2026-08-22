package com.atan.starkaudio

import com.atan.starkaudio.ui.AppLockPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    @Test fun doesNotLockBeforeTheSelectedGracePeriod() {
        assertFalse(AppLockPolicy.locksOnReturn(appLockEnabled = true, elapsedBackgroundMs = 59_999L, graceSeconds = 60))
    }

    @Test fun locksAtGraceBoundaryAndWhenImmediateLockIsSelected() {
        assertTrue(AppLockPolicy.locksOnReturn(appLockEnabled = true, elapsedBackgroundMs = 60_000L, graceSeconds = 60))
        assertTrue(AppLockPolicy.locksOnReturn(appLockEnabled = true, elapsedBackgroundMs = 0L, graceSeconds = 0))
    }

    @Test fun neverLocksWhenAppLockIsDisabled() {
        assertFalse(AppLockPolicy.locksOnReturn(appLockEnabled = false, elapsedBackgroundMs = Long.MAX_VALUE, graceSeconds = 0))
    }
}

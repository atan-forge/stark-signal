package com.atan.starkaudio

import com.atan.starkaudio.ui.MainNavigationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationPolicyTest {
    @Test fun primaryNavigationAppearsOnEveryTopLevelRoute() {
        listOf("home", "library", "transcripts", "settings").forEach {
            assertTrue(MainNavigationPolicy.isTopLevelRoute(it))
        }
    }

    @Test fun vaultDetailDoesNotShowPrimaryNavigation() {
        assertFalse(MainNavigationPolicy.isTopLevelRoute("vault"))
    }
}

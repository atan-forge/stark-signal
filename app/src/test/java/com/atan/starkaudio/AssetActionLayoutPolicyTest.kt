package com.atan.starkaudio

import com.atan.starkaudio.ui.AssetActionLayoutPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetActionLayoutPolicyTest {
    @Test fun stacksActionsWhenDialogWidthCannotSafelyFitTwoLabelledButtons() {
        assertTrue(AssetActionLayoutPolicy.shouldStack(359f, 1f))
        assertFalse(AssetActionLayoutPolicy.shouldStack(360f, 1f))
    }

    @Test fun stacksActionsForLargeTextEvenWhenTheDialogIsWide() {
        assertTrue(AssetActionLayoutPolicy.shouldStack(520f, 1.16f))
        assertFalse(AssetActionLayoutPolicy.shouldStack(520f, 1.15f))
    }
}

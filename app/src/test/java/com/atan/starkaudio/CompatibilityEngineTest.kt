package com.atan.starkaudio

import com.atan.starkaudio.compatibility.ProviderProfiles
import com.atan.starkaudio.compatibility.SizeEstimator
import org.junit.Assert.*
import org.junit.Test

class CompatibilityEngineTest {
    @Test fun providerUsesConservativeSafeLimit() { assertTrue(ProviderProfiles.openAi.safeBytes < ProviderProfiles.openAi.maxBytes) }
    @Test fun estimateIncludesContainerOverhead() { assertTrue(SizeEstimator.estimateBytes(3_600_000, 32_000) > 14_400_000) }
    @Test(expected = IllegalArgumentException::class) fun estimateRejectsNegativeDuration() { SizeEstimator.estimateBytes(-1, 32_000) }
}

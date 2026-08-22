package com.atan.starkaudio

import com.atan.starkaudio.core.model.RecordingPreset
import com.atan.starkaudio.core.model.TrimRange
import org.junit.Assert.assertEquals
import org.junit.Test

class DomainInvariantTest {
    @Test(expected = IllegalArgumentException::class) fun trimRejectsEmptyRange() { TrimRange(1_000, 1_000) }
    @Test(expected = IllegalArgumentException::class) fun trimRejectsNegativeStart() { TrimRange(-1, 1_000) }
    @Test fun speechPresetsAreMono() {
        assertEquals(1, RecordingPreset.AI_READY.channels)
        assertEquals(1, RecordingPreset.SMALLEST_SPEECH.channels)
        assertEquals(1, RecordingPreset.CLEAR_SPEECH.channels)
    }
}

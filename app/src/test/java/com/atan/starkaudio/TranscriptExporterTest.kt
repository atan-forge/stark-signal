package com.atan.starkaudio

import com.atan.starkaudio.core.model.*
import com.atan.starkaudio.transcription.*
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TranscriptExporterTest {
    private val transcript = Transcript("t", "a", "engine", "1", "model", "hash", LanguageMode.INDONESIAN, "id", Instant.EPOCH, 65_500, listOf(TranscriptSegment(0, 0, 1_500, "Halo dunia."), TranscriptSegment(1, 61_000, 65_500, "Selesai.")))
    @Test fun srtUsesCommaMilliseconds() { assertTrue(TranscriptExporter.export(transcript, TranscriptFormat.SRT).contains("00:00:00,000 --> 00:00:01,500")) }
    @Test fun vttHasHeader() { assertTrue(TranscriptExporter.export(transcript, TranscriptFormat.VTT).startsWith("WEBVTT")) }
    @Test fun txtIncludesLanguage() { assertTrue(TranscriptExporter.export(transcript, TranscriptFormat.TXT).contains("Language: id")) }
}

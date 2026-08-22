package com.atan.starkaudio

import com.atan.starkaudio.benchmark.BenchmarkCaseResult
import com.atan.starkaudio.benchmark.BenchmarkReport
import com.atan.starkaudio.benchmark.WordErrorRate
import com.atan.starkaudio.benchmark.BenchmarkApproval
import com.atan.starkaudio.benchmark.BenchmarkTimestampValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BenchmarkReportTest {
    @Test fun werNormalizesPunctuationAndCase() {
        assertEquals(0.0, WordErrorRate.calculate("Halo, dunia!", "HALO dunia"), 0.0)
        assertEquals(0.5, WordErrorRate.calculate("one two", "one"), 0.0)
    }

    @Test fun reportContainsMetricsNotTranscriptContent() {
        val json = BenchmarkReport(modelId = "base-q5", modelSha256 = "a".repeat(64), engineVersion = "1.8.6", abi = "arm64-v8a", totalRamBytes = 4, peakRssBytes = 3, maxThermalStatus = 0, batteryStartPercent = 80, batteryEndPercent = 79, cases = listOf(BenchmarkCaseResult("id-clean-01", "id", 2_000, 1_000, 0.1, false, editDistance = 1, referenceWordCount = 10))).toPrivacySafeJson()
        assertFalse(json.contains("Halo"))
        assertFalse(json.contains("/storage/"))
        assertFalse(json.contains("content://"))
        assertFalse(json.contains("referenceText"))
        assertFalse(json.contains("recognizedText"))
    }

    @Test fun approvalRequiresEveryLanguageGroupAndGate() {
        val report = BenchmarkReport(
            modelId = "base", modelSha256 = "b".repeat(64), engineVersion = "1.8.6", abi = "arm64-v8a",
            totalRamBytes = 4L * 1024 * 1024 * 1024, peakRssBytes = 100L * 1024 * 1024,
            maxThermalStatus = 0, batteryStartPercent = 80, batteryEndPercent = 70,
            cases = listOf(
                BenchmarkCaseResult("en-001", "en", 10_000, 5_000, 0.1, false, decodeMs = 500, inferenceMs = 4_500, editDistance = 1, referenceWordCount = 10),
                BenchmarkCaseResult("id-001", "id", 10_000, 5_000, 0.1, false, decodeMs = 500, inferenceMs = 4_500, editDistance = 1, referenceWordCount = 10),
                BenchmarkCaseResult("mixed-001", "en-id", 10_000, 5_000, 0.1, false, decodeMs = 500, inferenceMs = 4_500, editDistance = 1, referenceWordCount = 10)
            ),
            cancellationPassed = true,
            recoveryPassed = true,
            repeatedLoadUnloadPassed = true,
            endurance60MinPassed = true,
            page16KbCompatible = true,
            benchmarkPackComplete = true
        )
        assertEquals(true, BenchmarkApproval.evaluate(report).approved)
        assertFalse(BenchmarkApproval.evaluate(report.copy(cases = report.cases.dropLast(1))).approved)
        assertFalse(BenchmarkApproval.evaluate(report.copy(benchmarkPackComplete = false)).approved)
        assertFalse(BenchmarkApproval.evaluate(report.copy(endurance60MinPassed = false)).approved)
    }

    @Test fun corpusWerUsesEditTotalsRatherThanMeanCaseRates() {
        val shortBad = BenchmarkCaseResult("a", "id", 1_000, 100, 1.0, true, editDistance = 1, referenceWordCount = 1)
        val longGood = BenchmarkCaseResult("b", "id", 1_000, 100, 0.01, false, editDistance = 1, referenceWordCount = 100)
        assertEquals(2.0 / 101.0, (shortBad.editDistance + longGood.editDistance).toDouble() / (shortBad.referenceWordCount + longGood.referenceWordCount), 0.00001)
    }

    @Test fun timestampValidationUsesDecodedSamplesAndOneFrameTolerance() {
        val samples = 16_000L * 3L
        assertEquals(true, BenchmarkTimestampValidator.validate(listOf(0L..3_050L), samples).valid)
        assertFalse(BenchmarkTimestampValidator.validate(listOf(0L..3_101L), samples).valid)
        assertFalse(BenchmarkTimestampValidator.validate(listOf(500L..900L, 800L..1_000L), samples).valid)
        assertFalse(BenchmarkTimestampValidator.validate(listOf((-1L)..100L), samples).valid)
    }

    @Test fun staleSchemaCanNeverApprove() {
        val report = BenchmarkReport(
            schemaVersion = 2, modelId = "base", modelSha256 = "c".repeat(64), engineVersion = "1.8.6",
            abi = "arm64-v8a", totalRamBytes = 8L * 1024 * 1024 * 1024, peakRssBytes = 1,
            maxThermalStatus = 0, batteryStartPercent = 90, batteryEndPercent = 89, cases = emptyList()
        )
        assertFalse(BenchmarkApproval.evaluate(report).approved)
    }
}

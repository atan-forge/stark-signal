package com.atan.starkaudio.benchmark

import java.text.Normalizer

data class BenchmarkCaseResult(
    val caseId: String,
    val languageGroup: String,
    val audioDurationMs: Long,
    val elapsedMs: Long,
    val wer: Double,
    val catastrophic: Boolean,
    val errorCode: String? = null,
    val decodeMs: Long = 0L,
    val inferenceMs: Long = 0L,
    val editDistance: Int = 0,
    val referenceWordCount: Int = 0,
    val detectedLanguage: String? = null,
    val manifestDurationMs: Long = audioDurationMs,
    val decodedSampleCount: Long = 0L,
    val decodedDurationMs: Long = audioDurationMs,
    val maximumTimestampMs: Long = 0L,
    val timestampToleranceMs: Long = 0L
)

data class ReliabilityCheckResult(
    val id: String,
    val passed: Boolean,
    val errorCode: String? = null,
    val completedWorkMs: Long = 0L
)

enum class BenchmarkRunStatus { RUNNING, INCOMPLETE, COMPLETE, CANCELLED, FAILED }

data class CandidateApproval(val candidateId: String, val approved: Boolean, val reasons: List<String>)

data class BenchmarkReport(
    val schemaVersion: Int = 3,
    val modelId: String,
    val modelSha256: String,
    val engineVersion: String,
    val abi: String,
    val deviceModel: String = "",
    val androidSdk: Int = -1,
    val buildVariant: String = "benchmark",
    val totalRamBytes: Long,
    val peakRssBytes: Long,
    val maxThermalStatus: Int,
    val batteryStartPercent: Int,
    val batteryEndPercent: Int,
    val cases: List<BenchmarkCaseResult>,
    val modelLoadMs: Long = 0L,
    val cancellationPassed: Boolean = false,
    val recoveryPassed: Boolean = false,
    val repeatedLoadUnloadPassed: Boolean = false,
    val endurance60MinPassed: Boolean = false,
    val page16KbCompatible: Boolean = false,
    val benchmarkPackComplete: Boolean = false,
    val devicePageSizeBytes: Long = 0L,
    val nativeLoadAlignmentBytes: Long = 0L,
    val reliabilityChecks: List<ReliabilityCheckResult> = emptyList()
) {
    fun toPrivacySafeJson(): String = buildString {
        append("{\n")
        appendJsonNumber("schemaVersion", schemaVersion, comma = true)
        appendJsonString("modelId", modelId, comma = true)
        appendJsonString("modelSha256", modelSha256, comma = true)
        appendJsonString("engineVersion", engineVersion, comma = true)
        appendJsonString("abi", abi, comma = true)
        appendJsonString("deviceModel", deviceModel, comma = true)
        appendJsonNumber("androidSdk", androidSdk, comma = true)
        appendJsonString("buildVariant", buildVariant, comma = true)
        appendJsonNumber("totalRamBytes", totalRamBytes, comma = true)
        appendJsonNumber("peakRssBytes", peakRssBytes, comma = true)
        appendJsonNumber("maxThermalStatus", maxThermalStatus, comma = true)
        appendJsonNumber("batteryStartPercent", batteryStartPercent, comma = true)
        appendJsonNumber("batteryEndPercent", batteryEndPercent, comma = true)
        appendJsonNumber("modelLoadMs", modelLoadMs, comma = true)
        appendJsonBoolean("cancellationPassed", cancellationPassed, comma = true)
        appendJsonBoolean("recoveryPassed", recoveryPassed, comma = true)
        appendJsonBoolean("repeatedLoadUnloadPassed", repeatedLoadUnloadPassed, comma = true)
        appendJsonBoolean("endurance60MinPassed", endurance60MinPassed, comma = true)
        appendJsonBoolean("page16KbCompatible", page16KbCompatible, comma = true)
        appendJsonBoolean("benchmarkPackComplete", benchmarkPackComplete, comma = true)
        appendJsonNumber("devicePageSizeBytes", devicePageSizeBytes, comma = true)
        appendJsonNumber("nativeLoadAlignmentBytes", nativeLoadAlignmentBytes, comma = true)
        append("  \"reliabilityChecks\": [")
        reliabilityChecks.forEachIndexed { index, check ->
            if (index > 0) append(',')
            append("{\"id\":\"").append(check.id.jsonEscaped()).append("\",\"passed\":").append(check.passed)
            check.errorCode?.let { append(",\"errorCode\":\"").append(it.jsonEscaped()).append('"') }
            append(",\"completedWorkMs\":").append(check.completedWorkMs).append('}')
        }
        append("],\n")
        append("  \"cases\": [")
        if (cases.isNotEmpty()) append('\n')
        cases.forEachIndexed { index, result ->
            append("    {\n")
            appendJsonString("caseId", result.caseId, comma = true, indent = 6)
            appendJsonString("languageGroup", result.languageGroup, comma = true, indent = 6)
            appendJsonNumber("audioDurationMs", result.audioDurationMs, comma = true, indent = 6)
            appendJsonNumber("elapsedMs", result.elapsedMs, comma = true, indent = 6)
            appendJsonNumber("decodeMs", result.decodeMs, comma = true, indent = 6)
            appendJsonNumber("inferenceMs", result.inferenceMs, comma = true, indent = 6)
            appendJsonNumber(
                "realTimeFactor",
                if (result.audioDurationMs == 0L) 0.0 else result.elapsedMs.toDouble() / result.audioDurationMs,
                comma = true,
                indent = 6
            )
            appendJsonNumber("wer", result.wer, comma = true, indent = 6)
            appendJsonNumber("editDistance", result.editDistance, comma = true, indent = 6)
            appendJsonNumber("referenceWordCount", result.referenceWordCount, comma = true, indent = 6)
            appendJsonString("detectedLanguage", result.detectedLanguage.orEmpty(), comma = true, indent = 6)
            appendJsonNumber("manifestDurationMs", result.manifestDurationMs, comma = true, indent = 6)
            appendJsonNumber("decodedSampleCount", result.decodedSampleCount, comma = true, indent = 6)
            appendJsonNumber("decodedDurationMs", result.decodedDurationMs, comma = true, indent = 6)
            appendJsonNumber("maximumTimestampMs", result.maximumTimestampMs, comma = true, indent = 6)
            appendJsonNumber("timestampToleranceMs", result.timestampToleranceMs, comma = true, indent = 6)
            appendJsonBoolean("catastrophic", result.catastrophic, comma = result.errorCode != null, indent = 6)
            result.errorCode?.let { appendJsonString("errorCode", it, indent = 6) }
            append("    }")
            if (index != cases.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n}")
    }

    private fun StringBuilder.appendJsonString(
        name: String,
        value: String,
        comma: Boolean = false,
        indent: Int = 2
    ) {
        append(" ".repeat(indent))
        append('"').append(name.jsonEscaped()).append("\": \"")
        append(value.jsonEscaped()).append('"')
        if (comma) append(',')
        append('\n')
    }

    private fun StringBuilder.appendJsonNumber(
        name: String,
        value: Number,
        comma: Boolean = false,
        indent: Int = 2
    ) {
        append(" ".repeat(indent))
        append('"').append(name.jsonEscaped()).append("\": ").append(value)
        if (comma) append(',')
        append('\n')
    }

    private fun StringBuilder.appendJsonBoolean(
        name: String,
        value: Boolean,
        comma: Boolean = false,
        indent: Int = 2
    ) {
        append(" ".repeat(indent))
        append('"').append(name.jsonEscaped()).append("\": ").append(value)
        if (comma) append(',')
        append('\n')
    }

    private fun String.jsonEscaped(): String = buildString(length) {
        this@jsonEscaped.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
}

object BenchmarkApproval {
    fun evaluate(report: BenchmarkReport): CandidateApproval {
        val reasons = mutableListOf<String>()
        if (report.schemaVersion < 3) reasons += "Report schema predates decoded-duration and native-alignment evidence."
        if (report.abi != "arm64-v8a") reasons += "Reference evidence must be arm64-v8a."
        if (report.totalRamBytes < 4L * 1024 * 1024 * 1024) reasons += "Reference device has less than 4 GB RAM."
        if (report.peakRssBytes >= 900L * 1024 * 1024) reasons += "Peak RSS is at or above 900 MB."
        val p90 = report.cases.map { if (it.audioDurationMs == 0L) Double.POSITIVE_INFINITY else (it.decodeMs + it.inferenceMs).toDouble() / it.audioDurationMs }.sorted().let { if (it.isEmpty()) Double.POSITIVE_INFINITY else it[kotlin.math.ceil(it.size * 0.90).toInt().coerceIn(1, it.size) - 1] }
        if (p90 > 1.0) reasons += "p90 real-time factor exceeds 1.0."
        val limits = mapOf("en" to 0.15, "id" to 0.20, "en-id" to 0.30)
        limits.forEach { (group, limit) ->
            val values = report.cases.filter { it.languageGroup == group }
            if (values.isEmpty()) reasons += "No $group cases were recorded."
            else {
                val words = values.sumOf { it.referenceWordCount }
                val edits = values.sumOf { it.editDistance }
                if (words <= 0) reasons += "$group reference word counts are missing."
                else if (edits.toDouble() / words > limit) reasons += "$group WER exceeds ${(limit * 100).toInt()}%."
            }
        }
        if (report.cases.isEmpty() || report.cases.count { it.catastrophic }.toDouble() / report.cases.size >= 0.005) reasons += "Catastrophic-error rate is at or above 0.5%."
        if (report.cases.any { it.errorCode != null }) reasons += "One or more benchmark cases reported an engine or decoder error."
        if (!report.cancellationPassed) reasons += "Cancellation validation is missing or failed."
        if (!report.recoveryPassed) reasons += "Process recovery validation is missing or failed."
        if (!report.repeatedLoadUnloadPassed) reasons += "Repeated model load and unload validation is missing or failed."
        if (!report.endurance60MinPassed) reasons += "60-minute endurance validation is missing or failed."
        if (!report.page16KbCompatible) reasons += "16 KB native-page compatibility is missing or failed."
        if (!report.benchmarkPackComplete) reasons += "The benchmark pack is incomplete or has not been reviewed."
        return CandidateApproval(report.modelId, reasons.isEmpty(), reasons)
    }
}

object WordErrorRate {
    data class Measurement(val editDistance: Int, val referenceWordCount: Int) {
        val rate: Double get() = if (referenceWordCount == 0) if (editDistance == 0) 0.0 else 1.0 else editDistance.toDouble() / referenceWordCount
    }

    fun calculate(reference: String, hypothesis: String): Double = measure(reference, hypothesis).rate

    fun measure(reference: String, hypothesis: String): Measurement {
        val expected = tokens(reference)
        val actual = tokens(hypothesis)
        if (expected.isEmpty()) return Measurement(if (actual.isEmpty()) 0 else actual.size, 0)
        var previous = IntArray(actual.size + 1) { it }
        expected.forEachIndexed { row, expectedToken ->
            val current = IntArray(actual.size + 1)
            current[0] = row + 1
            actual.forEachIndexed { column, actualToken ->
                current[column + 1] = minOf(
                    previous[column + 1] + 1,
                    current[column] + 1,
                    previous[column] + if (expectedToken == actualToken) 0 else 1
                )
            }
            previous = current
        }
        return Measurement(previous.last(), expected.size)
    }

    fun tokens(value: String): List<String> = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
}

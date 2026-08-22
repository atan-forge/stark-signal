package com.atan.starkaudio.transcription

import com.atan.starkaudio.core.model.Transcript
import com.atan.starkaudio.core.model.TranscriptSegment

enum class TranscriptFormat { TXT, SRT, VTT }

object TranscriptExporter {
    fun export(transcript: Transcript, format: TranscriptFormat, includeTimestamps: Boolean = true): String = when (format) {
        TranscriptFormat.TXT -> buildString {
            append("Transcript\nLanguage: ${transcript.detectedLanguage ?: transcript.languageMode.name.lowercase()}\nDuration: ${clock(transcript.durationMs)}\n\n")
            transcript.segments.forEach { segment -> if (includeTimestamps) append("[${clock(segment.startMs)}]\n"); append(segment.text.trim()).append("\n\n") }
        }.trimEnd() + "\n"
        TranscriptFormat.SRT -> transcript.segments.joinToString("\n\n", postfix = "\n") { segment -> "${segment.index + 1}\n${subtitleClock(segment.startMs, ',')} --> ${subtitleClock(segment.endMs, ',')}\n${segment.text.trim()}" }
        TranscriptFormat.VTT -> "WEBVTT\n\n" + transcript.segments.joinToString("\n\n", postfix = "\n") { segment -> "${subtitleClock(segment.startMs, '.')} --> ${subtitleClock(segment.endMs, '.')}\n${segment.text.trim()}" }
    }

    private fun clock(ms: Long): String { val seconds = ms.coerceAtLeast(0) / 1000; return if (seconds >= 3600) "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60) }
    private fun subtitleClock(ms: Long, separator: Char): String { val safe = ms.coerceAtLeast(0); val hours = safe / 3_600_000; val minutes = safe % 3_600_000 / 60_000; val seconds = safe % 60_000 / 1000; val millis = safe % 1000; return "%02d:%02d:%02d%c%03d".format(hours, minutes, seconds, separator, millis) }
}

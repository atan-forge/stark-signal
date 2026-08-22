package com.atan.starkaudio.compatibility

import com.atan.starkaudio.core.domain.CompatibilityEngine
import com.atan.starkaudio.core.domain.TransformPlanner
import com.atan.starkaudio.core.model.*
import kotlin.math.ceil

object ProviderProfiles {
    private const val MB = 1_000_000L
    val openAi = ProviderProfile("openai_transcription", "OpenAI transcription", 25 * MB, 24 * MB, setOf("audio/m4a", "audio/mp4", "audio/mpeg", "audio/wav", "audio/webm", "video/mp4"), "2026-08-21")
    val gemini = ProviderProfile("gemini_audio", "Gemini audio", 100 * MB, 96 * MB, setOf("audio/wav", "audio/mpeg", "audio/aac", "audio/ogg", "audio/flac", "audio/mp4"), "2026-08-21")
    val universal = ProviderProfile("universal_ai", "Universal AI", 20 * MB, 19 * MB, setOf("audio/mp4", "audio/m4a", "audio/mpeg", "audio/wav"), "2026-08-21")
    val defaults = listOf(openAi, gemini, universal)
}

object SizeEstimator {
    fun estimateBytes(durationMs: Long, bitRate: Int, overheadRatio: Double = 0.025): Long {
        require(durationMs >= 0 && bitRate > 0 && overheadRatio >= 0)
        return ((durationMs / 1000.0) * bitRate / 8.0 * (1.0 + overheadRatio)).toLong()
    }
}

class DefaultCompatibilityEngine : CompatibilityEngine {
    override fun diagnose(inspection: MediaInspection, profile: ProviderProfile): CompatibilityStatus {
        if (inspection.audioTracks.isEmpty()) return CompatibilityStatus.NO_AUDIO_TRACK
        val size = inspection.sizeBytes ?: return CompatibilityStatus.UNKNOWN
        val mimeAccepted = inspection.containerMime?.let { it in profile.acceptedMimeTypes } ?: false
        return when {
            size > profile.safeBytes -> CompatibilityStatus.SPLIT_REQUIRED
            !mimeAccepted -> CompatibilityStatus.CONVERSION_REQUIRED
            inspection.audioTracks.size > 1 -> CompatibilityStatus.MULTIPLE_AUDIO_TRACKS
            else -> CompatibilityStatus.COMPATIBLE
        }
    }
}

class DefaultTransformPlanner : TransformPlanner {
    override fun plan(inspection: MediaInspection, trackIndex: Int, trim: TrimRange?, profile: ProviderProfile, preset: RecordingPreset): TransformPlan {
        val durationMs = trim?.let { it.endMs - it.startMs } ?: inspection.durationMs ?: 0
        val estimated = SizeEstimator.estimateBytes(durationMs, preset.bitRate)
        val parts = ceil(estimated.toDouble() / profile.safeBytes).toInt().coerceAtLeast(1)
        val track = inspection.audioTracks.firstOrNull { it.index == trackIndex } ?: error("Selected audio track is unavailable")
        val compatibleCodec = track.mimeType == "audio/mp4a-latm" && preset.channels == track.channels && preset.sampleRate == track.sampleRate
        return TransformPlan(
            source = inspection.source,
            selectedAudioTrack = trackIndex,
            selectedAudioMime = track.mimeType,
            isolateSelectedTrack = inspection.audioTracks.size > 1,
            trim = trim,
            durationMs = durationMs,
            safeTargetBytes = profile.safeBytes,
            outputBitRate = preset.bitRate,
            outputSampleRate = preset.sampleRate,
            outputChannels = preset.channels,
            estimatedBytes = estimated,
            parts = parts,
            requiresTranscode = !compatibleCodec || trim != null || inspection.hasVideo,
            reason = when {
                parts > 1 -> "Split into $parts parts to stay below the selected limit."
                compatibleCodec && trim == null && !inspection.hasVideo -> "The audio can be preserved without re-encoding."
                inspection.hasVideo -> "The visual track will be removed."
                else -> "Conversion is required for the selected target."
            }
        )
    }
}

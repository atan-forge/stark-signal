package com.atan.starkaudio.core.model

import android.net.Uri
import java.time.Instant

enum class MediaKind { RECORDING, IMPORTED_AUDIO, IMPORTED_VIDEO, PREPARED_AUDIO }
enum class AssetStatus { READY, RECORDING, PAUSED, PROCESSING, INTERRUPTED, FAILED, DELETING }
enum class LanguageMode { ENGLISH, INDONESIAN, AUTO }
enum class CompatibilityStatus { COMPATIBLE, OPTIMIZATION_RECOMMENDED, CONVERSION_REQUIRED, SPLIT_REQUIRED, UNSUPPORTED, UNKNOWN, NO_AUDIO_TRACK, MULTIPLE_AUDIO_TRACKS }
enum class HeavyWorkType { RECORDING, CONVERSION, TRANSCRIPTION }

enum class RecordingPreset(val bitRate: Int, val sampleRate: Int, val channels: Int) {
    AI_READY(32_000, 24_000, 1),
    SMALLEST_SPEECH(24_000, 16_000, 1),
    CLEAR_SPEECH(48_000, 24_000, 1),
    FULL_AUDIO(96_000, 48_000, 2)
}

data class MediaAsset(
    val id: String,
    val title: String,
    val kind: MediaKind,
    val createdAt: Instant,
    val durationMs: Long,
    val sizeBytes: Long,
    val localPath: String?,
    val sourceUri: Uri?,
    val mimeType: String?,
    val status: AssetStatus,
    val partCount: Int = 1,
    val transcriptId: String? = null
)

data class AudioTrack(
    val index: Int,
    val mimeType: String?,
    val codec: String?,
    val language: String?,
    val channels: Int?,
    val sampleRate: Int?,
    val bitRate: Int?
)

data class MediaInspection(
    val source: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val containerMime: String?,
    val hasVideo: Boolean,
    val audioTracks: List<AudioTrack>,
    val status: CompatibilityStatus,
    val error: AppError? = null
)

data class TrimRange(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs) }
}

data class ProviderProfile(
    val id: String,
    val title: String,
    val maxBytes: Long,
    val safeBytes: Long,
    val acceptedMimeTypes: Set<String>,
    val verifiedOn: String
)

data class TransformPlan(
    val source: Uri,
    val selectedAudioTrack: Int,
    val selectedAudioMime: String?,
    val isolateSelectedTrack: Boolean,
    val trim: TrimRange?,
    val durationMs: Long,
    val safeTargetBytes: Long,
    val outputBitRate: Int,
    val outputSampleRate: Int,
    val outputChannels: Int,
    val estimatedBytes: Long,
    val parts: Int,
    val requiresTranscode: Boolean,
    val reason: String
)

data class TranscriptSegment(val index: Int, val startMs: Long, val endMs: Long, val text: String)

data class Transcript(
    val id: String,
    val audioAssetId: String,
    val engineId: String,
    val engineVersion: String,
    val modelId: String,
    val modelHash: String,
    val languageMode: LanguageMode,
    val detectedLanguage: String?,
    val createdAt: Instant,
    val durationMs: Long,
    val segments: List<TranscriptSegment>
)

enum class ErrorCode {
    REC_PERMISSION_DENIED, REC_MIC_UNAVAILABLE, REC_START_FAILED, REC_STORAGE_LOW,
    REC_STORAGE_FULL, REC_ROUTE_LOST, REC_FINALIZE_FAILED, INPUT_UNREADABLE,
    INPUT_UNSUPPORTED, INPUT_NO_AUDIO_TRACK, PLAN_NO_SAFE_PROFILE, ENCODER_UNAVAILABLE,
    TRANSFORM_FAILED, TRANSFORM_CANCELLED, OUTPUT_VERIFY_FAILED, ASR_MODEL_NOT_INSTALLED,
    ASR_MODEL_CORRUPT, ASR_DEVICE_MEMORY_LOW, ASR_ENGINE_INIT_FAILED, ASR_NATIVE_ERROR,
    ASR_OUT_OF_MEMORY, ASR_CANCELLED, ASR_PROCESS_INTERRUPTED, ASR_OUTPUT_INVALID,
    SHARE_FAILED, SAVE_FAILED
}

data class AppError(val code: ErrorCode, val userMessage: String, val recoverable: Boolean = true)

enum class DeleteTarget { OWNED_MEDIA, IMPORT_REFERENCE }
data class DeletePlan(val assetId: String, val displayName: String, val target: DeleteTarget, val keepsTranscript: Boolean)
sealed interface DeleteResult {
    data class Deleted(val plan: DeletePlan) : DeleteResult
    data object NotFound : DeleteResult
    data class Failed(val plan: DeletePlan?, val message: String) : DeleteResult
}
sealed interface DeletionState {
    data object Idle : DeletionState
    data class Deleting(val assetId: String) : DeletionState
    data class Complete(val result: DeleteResult.Deleted) : DeletionState
    data class Failed(val assetId: String, val message: String) : DeletionState
}

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Starting(val preset: RecordingPreset) : RecordingState
    data class Recording(val sessionId: String, val elapsedMs: Long, val bytesWritten: Long, val currentPart: Int, val amplitude: Int) : RecordingState
    data class Paused(val sessionId: String, val elapsedMs: Long, val currentPart: Int) : RecordingState
    data class Rotating(val sessionId: String, val completedPart: Int) : RecordingState
    data class Stopping(val sessionId: String) : RecordingState
    data class Complete(val assetId: String) : RecordingState
    data class Failed(val error: AppError) : RecordingState
}

sealed interface ProcessingState {
    data object Idle : ProcessingState
    data class Inspecting(val source: Uri) : ProcessingState
    data class Ready(val inspection: MediaInspection) : ProcessingState
    data class Processing(val processedMs: Long, val durationMs: Long?) : ProcessingState
    data class Complete(val assetId: String) : ProcessingState
    data class Error(val error: AppError) : ProcessingState
}

sealed interface TranscriptionState {
    data object Idle : TranscriptionState
    data class LoadingModel(val modelId: String) : TranscriptionState
    data class Running(val jobId: String, val processedMs: Long, val durationMs: Long) : TranscriptionState
    data class Complete(val transcriptId: String) : TranscriptionState
    data class Error(val error: AppError) : TranscriptionState
}

package com.atan.starkaudio.core.domain

import android.net.Uri
import com.atan.starkaudio.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface Recorder {
    val state: StateFlow<RecordingState>
    suspend fun start(title: String, preset: RecordingPreset, safeTargetBytes: Long)
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()
}

interface MediaInspector { suspend fun inspect(uri: Uri): MediaInspection }
interface CompatibilityEngine { fun diagnose(inspection: MediaInspection, profile: ProviderProfile): CompatibilityStatus }
interface TransformPlanner { fun plan(inspection: MediaInspection, trackIndex: Int, trim: TrimRange?, profile: ProviderProfile, preset: RecordingPreset): TransformPlan }
interface MediaProcessor {
    val state: StateFlow<ProcessingState>
    suspend fun process(plan: TransformPlan)
    suspend fun cancel()
}
interface PlaybackController {
    val isPlaying: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    fun setAsset(asset: MediaAsset)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
}
interface VaultRepository {
    fun observeAssets(): Flow<List<MediaAsset>>
    fun observeTranscripts(query: String = ""): Flow<List<Transcript>>
    suspend fun getAsset(id: String): MediaAsset?
    suspend fun upsertAsset(asset: MediaAsset)
    suspend fun planDeletion(id: String): DeletePlan?
    suspend fun deleteAsset(id: String): DeleteResult
    suspend fun recoverPendingDeletions()
    suspend fun upsertTranscript(transcript: Transcript)
    suspend fun deleteTranscript(id: String)
}
interface HeavyWorkCoordinator {
    val active: StateFlow<HeavyWorkType?>
    suspend fun acquire(type: HeavyWorkType): Boolean
    suspend fun release(type: HeavyWorkType)
}

data class TranscriptionRequest(val jobId: String, val asset: MediaAsset, val languageMode: LanguageMode)
data class TranscriptionProgress(val processedMs: Long, val durationMs: Long)
sealed interface EngineAvailability {
    data object Available : EngineAvailability
    data class Unavailable(val reason: String) : EngineAvailability
}
interface TranscriptionEngine {
    val id: String
    suspend fun availability(): EngineAvailability
    suspend fun transcribe(request: TranscriptionRequest, onProgress: (TranscriptionProgress) -> Unit): Result<Transcript>
    suspend fun cancel(jobId: String)
}

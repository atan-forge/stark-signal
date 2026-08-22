package com.atan.starkaudio

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atan.starkaudio.core.model.*
import com.atan.starkaudio.service.recording.RecordingService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import com.atan.starkaudio.compatibility.ProviderProfiles
import com.atan.starkaudio.core.domain.EngineAvailability
import com.atan.starkaudio.core.domain.TranscriptionRequest
import com.atan.starkaudio.transcription.BundledModelInstaller
import com.atan.starkaudio.ui.SettingsLoadState

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as StarkSignalApplication).services
    val assets = graph.vault.observeAssets().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val transcriptQuery = MutableStateFlow("")
    val transcripts = transcriptQuery.flatMapLatest { graph.vault.observeTranscripts(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = graph.settings.settings
        .map<_, SettingsLoadState> { SettingsLoadState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsLoadState.Loading)
    val recordingState = RecordingService.state
    private val mutableInspection = MutableStateFlow<MediaInspection?>(null)
    val inspection: StateFlow<MediaInspection?> = mutableInspection
    private val mutableInspecting = MutableStateFlow(false)
    val inspecting: StateFlow<Boolean> = mutableInspecting
    val processingState = graph.mediaProcessor.state
    private val mutableDeletionState = MutableStateFlow<DeletionState>(DeletionState.Idle)
    val deletionState: StateFlow<DeletionState> = mutableDeletionState
    private val mutableTranscriptionAvailability = MutableStateFlow<EngineAvailability>(EngineAvailability.Unavailable("Checking offline transcription support."))
    val transcriptionAvailability: StateFlow<EngineAvailability> = mutableTranscriptionAvailability
    private val mutableTranscriptionState = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    val transcriptionState: StateFlow<TranscriptionState> = mutableTranscriptionState

    init { viewModelScope.launch { mutableTranscriptionAvailability.value = graph.transcriptionEngine.availability() } }

    fun completeOnboarding() = viewModelScope.launch { graph.settings.completeOnboarding() }
    fun setAppLock(enabled: Boolean) = viewModelScope.launch { graph.settings.setAppLock(enabled) }
    fun setLockGrace(seconds: Int) = viewModelScope.launch { graph.settings.setLockGrace(seconds) }
    fun setTranscriptQuery(query: String) { transcriptQuery.value = query }

    fun inspect(uri: Uri, persistPermission: Boolean = false) {
        if (persistPermission) runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModelScope.launch {
            mutableInspecting.value = true
            val result = graph.mediaInspector.inspect(uri)
            mutableInspection.value = result
            if (result.error == null && result.audioTracks.isNotEmpty()) {
                graph.vault.upsertAsset(MediaAsset(
                    id = UUID.randomUUID().toString(), title = result.displayName,
                    kind = if (result.hasVideo) MediaKind.IMPORTED_VIDEO else MediaKind.IMPORTED_AUDIO,
                    createdAt = Instant.now(), durationMs = result.durationMs ?: 0L,
                    sizeBytes = result.sizeBytes ?: 0L, localPath = null, sourceUri = uri,
                    mimeType = result.containerMime, status = AssetStatus.READY
                ))
            }
            mutableInspecting.value = false
        }
    }

    fun clearInspection() { mutableInspection.value = null }
    fun prepareImportedMedia(trimStartMs: Long = 0, trimEndMs: Long? = null, trackIndex: Int? = null, profile: ProviderProfile = ProviderProfiles.openAi) {
        val inspected = mutableInspection.value ?: return
        val track = inspected.audioTracks.firstOrNull { it.index == trackIndex } ?: inspected.audioTracks.firstOrNull() ?: return
        val trim = trimEndMs?.takeIf { it > trimStartMs && (trimStartMs > 0 || it < (inspected.durationMs ?: Long.MAX_VALUE)) }?.let { TrimRange(trimStartMs, it) }
        val plan = graph.transformPlanner.plan(inspected, track.index, trim, profile, RecordingPreset.AI_READY)
        viewModelScope.launch { graph.mediaProcessor.process(plan) }
    }
    fun cancelPreparation() = viewModelScope.launch { graph.mediaProcessor.cancel() }
    fun deleteAsset(id: String) = viewModelScope.launch {
        mutableDeletionState.value = DeletionState.Deleting(id)
        mutableDeletionState.value = when (val result = graph.vault.deleteAsset(id)) {
            is DeleteResult.Deleted -> DeletionState.Complete(result)
            DeleteResult.NotFound -> DeletionState.Failed(id, "This item is no longer in the library.")
            is DeleteResult.Failed -> DeletionState.Failed(id, result.message)
        }
    }
    fun clearDeletionState() { mutableDeletionState.value = DeletionState.Idle }
    fun renameAsset(id: String, title: String) = viewModelScope.launch { graph.vault.getAsset(id)?.let { graph.vault.upsertAsset(it.copy(title = title.trim().take(80).ifBlank { it.title })) } }
    fun deleteTranscript(id: String) = viewModelScope.launch { graph.vault.deleteTranscript(id) }
    fun saveTranscript(transcript: Transcript) = viewModelScope.launch { graph.vault.upsertTranscript(transcript) }
    suspend fun assetForTranscript(transcript: Transcript): MediaAsset? = graph.vault.getAsset(transcript.audioAssetId)
    fun transcribe(asset: MediaAsset, language: LanguageMode = LanguageMode.AUTO) = viewModelScope.launch {
        val availability = graph.transcriptionEngine.availability()
        mutableTranscriptionAvailability.value = availability
        if (availability !is EngineAvailability.Available) {
            mutableTranscriptionState.value = TranscriptionState.Error(AppError(ErrorCode.ASR_MODEL_NOT_INSTALLED, (availability as EngineAvailability.Unavailable).reason))
            return@launch
        }
        val selectedModel = BundledModelInstaller.manifestOrNull()
        val jobId = selectedModel?.let { graph.transcriptionJobs.recoverable(asset.id, language.name, it.sha256)?.jobId }
            ?: UUID.randomUUID().toString()
        mutableTranscriptionState.value = TranscriptionState.LoadingModel("offline")
        graph.transcriptionEngine.transcribe(TranscriptionRequest(jobId, asset, language)) { progress ->
            mutableTranscriptionState.value = TranscriptionState.Running(jobId, progress.processedMs, progress.durationMs)
        }.onSuccess { transcript ->
            graph.vault.upsertTranscript(transcript)
            graph.vault.upsertAsset(asset.copy(transcriptId = transcript.id))
            mutableTranscriptionState.value = TranscriptionState.Complete(transcript.id)
        }.onFailure { error ->
            val code = if (error is java.util.concurrent.CancellationException) ErrorCode.ASR_CANCELLED else ErrorCode.ASR_NATIVE_ERROR
            mutableTranscriptionState.value = TranscriptionState.Error(AppError(code, if (code == ErrorCode.ASR_CANCELLED) "Transcription was cancelled." else "Transcription could not be completed. Try again after checking the audio and available storage."))
        }
    }
    fun cancelTranscription() = viewModelScope.launch {
        val state = mutableTranscriptionState.value
        if (state is TranscriptionState.Running) graph.transcriptionEngine.cancel(state.jobId)
    }
}

package com.atan.starkaudio.transcription

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.atan.starkaudio.core.domain.*
import com.atan.starkaudio.core.model.*
import com.atan.starkaudio.service.HeavyWorkCoordinatorImpl
import com.atan.starkaudio.storage.TranscriptionJobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

data class BundledModelManifest(
    val id: String,
    val fileName: String,
    val sha256: String,
    val byteCount: Long,
    val sourceUrl: String,
    val license: String,
    val engineVersion: String
)

class WhisperTranscriptionEngine(
    private val context: Context,
    private val manifest: BundledModelManifest?,
    private val heavyWork: HeavyWorkCoordinatorImpl,
    private val jobs: TranscriptionJobStore,
    private val bridge: WhisperNativeBridge = WhisperNativeBridge
) : TranscriptionEngine {
    override val id = "whisper.cpp"
    @Volatile private var cancelledJob: String? = null
    private val activeSessions = ConcurrentHashMap<String, Long>()

    override suspend fun availability(): EngineAvailability = withContext(Dispatchers.IO) {
        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) return@withContext EngineAvailability.Unavailable("Offline transcription requires a 64-bit device.")
        val memory = ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        if (memory.totalMem < 4L * 1024 * 1024 * 1024) return@withContext EngineAvailability.Unavailable("Offline transcription needs a device with at least 4 GB of memory.")
        val selected = manifest ?: return@withContext EngineAvailability.Unavailable("No offline transcription model is bundled in this build.")
        if (!BundledModelInstaller.assetIsPresent(context, selected)) {
            return@withContext EngineAvailability.Unavailable("The offline transcription model is missing from this build.")
        }
        if (!bridge.isLoaded) return@withContext EngineAvailability.Unavailable("The native transcription engine is not installed in this build.")
        EngineAvailability.Available
    }

    override suspend fun transcribe(request: TranscriptionRequest, onProgress: (TranscriptionProgress) -> Unit): Result<Transcript> = withContext(Dispatchers.Default) {
        val available = availability()
        if (available !is EngineAvailability.Available) return@withContext Result.failure(IllegalStateException((available as EngineAvailability.Unavailable).reason))
        if (!heavyWork.acquire(HeavyWorkType.TRANSCRIPTION)) return@withContext Result.failure(IllegalStateException("Finish recording or media preparation before starting transcription."))
        cancelledJob = null
        var session = 0L
        try {
            val selected = checkNotNull(manifest)
            jobs.start(request.jobId, request.asset.id, request.languageMode.name, selected.id, selected.sha256, request.asset.durationMs)
            val model = BundledModelInstaller.installIfNeeded(context, selected)
            session = bridge.createSession(model.absolutePath)
            if (session == 0L) throw IllegalStateException("Offline model could not be loaded.")
            activeSessions[request.jobId] = session
            val sources = mediaSources(request.asset)
            if (sources.isEmpty()) return@withContext Result.failure(IllegalArgumentException("This audio item is no longer available."))
            val decoder = PcmChunkDecoder(context)
            val resume = jobs.recoverable(request.asset.id, request.languageMode.name, selected.sha256)
                ?.takeIf { it.jobId == request.jobId }
            val segments = resume?.segments?.toMutableList() ?: mutableListOf()
            var detectedLanguage: String? = null
            val resumeMs = resume?.processedMs ?: 0L
            var partOffsetMs = 0L
            sources.forEach { part ->
                val partEndMs = partOffsetMs + part.durationMs
                if (resumeMs < partEndMs && cancelledJob != request.jobId) {
                    val offset = partOffsetMs
                    decoder.decode(part.uri, startAtMs = (resumeMs - offset).coerceAtLeast(0L), isCancelled = { cancelledJob == request.jobId }) { localChunkStartMs, pcm ->
                        if (cancelledJob == request.jobId) return@decode
                        val chunkStartMs = offset + localChunkStartMs
                        bridge.resetSession(session)
                        val result = bridge.transcribeChunk(
                            session,
                            pcm,
                            request.languageMode.nativeCode,
                            max(1, Runtime.getRuntime().availableProcessors() / 2)
                        ) { percent ->
                            val processed = (chunkStartMs + pcm.size * percent.toLong() / 16_000L / 100L).coerceAtMost(request.asset.durationMs)
                            onProgress(TranscriptionProgress(processed, request.asset.durationMs))
                        }
                        result.error?.let { code ->
                            if (code == "cancelled") return@decode
                            throw IllegalStateException("Offline transcription failed: $code")
                        }
                        detectedLanguage = result.detectedLanguage ?: detectedLanguage
                        result.segments.forEach { native ->
                            val text = native.text.trim()
                            val last = segments.lastOrNull()
                            val start = max(chunkStartMs + native.startMs, last?.endMs ?: 0L)
                            val end = (chunkStartMs + native.endMs).coerceAtMost(request.asset.durationMs)
                            if (text.isNotBlank() && end > start && !(last?.text == text && start - last.endMs < 500L)) {
                                segments += TranscriptSegment(segments.size, start, end, text)
                            }
                        }
                        jobs.checkpoint(request.jobId, (chunkStartMs + pcm.size * 1_000L / 16_000L).coerceAtMost(request.asset.durationMs), segments)
                    }
                }
                partOffsetMs = partEndMs
            }
            if (cancelledJob == request.jobId) {
                jobs.cancel(request.jobId)
                return@withContext Result.failure(java.util.concurrent.CancellationException("Transcription cancelled."))
            }
            onProgress(TranscriptionProgress(request.asset.durationMs, request.asset.durationMs))
            jobs.complete(request.jobId)
            Result.success(Transcript(UUID.randomUUID().toString(), request.asset.id, id, selected.engineVersion, selected.id, selected.sha256, request.languageMode, detectedLanguage, Instant.now(), request.asset.durationMs, segments))
        } catch (error: Throwable) {
            jobs.fail(request.jobId, if (error is java.util.concurrent.CancellationException) "cancelled" else "native_error")
            Result.failure(error)
        } finally {
            activeSessions.remove(request.jobId)
            if (session != 0L) bridge.destroySession(session)
            heavyWork.release(HeavyWorkType.TRANSCRIPTION)
        }
    }

    override suspend fun cancel(jobId: String) { cancelledJob = jobId; activeSessions[jobId]?.let(bridge::cancel) }

    suspend fun cancelAllForRecording() {
        activeSessions.keys.toList().forEach { jobId -> cancel(jobId) }
        withTimeoutOrNull(2_000L) { while (activeSessions.isNotEmpty()) delay(25L) }
    }

    private data class SourcePart(val uri: Uri, val durationMs: Long)

    private fun mediaSources(asset: MediaAsset): List<SourcePart> {
        asset.sourceUri?.let { return listOf(SourcePart(it, asset.durationMs)) }
        val path = asset.localPath ?: return emptyList()
        val file = File(path)
        val files = if (file.isDirectory) file.listFiles { child -> child.isFile && child.extension.equals("m4a", true) }?.sortedBy { it.name }.orEmpty() else listOf(file)
        return files.filter { it.exists() && it.length() > 0L }.map { SourcePart(Uri.fromFile(it), mediaDuration(it)) }
    }

    private fun mediaDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { retriever.release() }
    }

}

object WhisperNativeBridge {
    val isLoaded: Boolean = runCatching { System.loadLibrary("stark_whisper"); true }.getOrDefault(false)
    external fun createSession(modelPath: String): Long
    external fun resetSession(handle: Long)
    external fun cancel(handle: Long)
    external fun destroySession(handle: Long)
    private external fun transcribeChunk(handle: Long, pcm: FloatArray, language: String?, threads: Int, callback: NativeProgressCallback): String

    internal fun transcribeChunk(handle: Long, pcm: FloatArray, language: String?, threads: Int, progress: (Int) -> Unit): NativeResult {
        val root = JSONObject(transcribeChunk(handle, pcm, language, threads, NativeProgressCallback(progress)))
        root.optString("error").takeIf { it.isNotBlank() }?.let { return NativeResult(error = it) }
        val array = root.getJSONArray("segments")
        return NativeResult(detectedLanguage = root.optString("detectedLanguage").takeIf(String::isNotBlank), segments = List(array.length()) { index ->
            array.getJSONObject(index).let { NativeSegment(it.getLong("startMs"), it.getLong("endMs"), it.getString("text")) }
        })
    }
}

private val LanguageMode.nativeCode: String? get() = when (this) {
    LanguageMode.ENGLISH -> "en"
    LanguageMode.INDONESIAN -> "id"
    LanguageMode.AUTO -> null
}

internal fun interface NativeProgressCallback { fun onProgress(percent: Int) }
internal data class NativeSegment(val startMs: Long, val endMs: Long, val text: String)
internal data class NativeResult(val segments: List<NativeSegment> = emptyList(), val detectedLanguage: String? = null, val error: String? = null)

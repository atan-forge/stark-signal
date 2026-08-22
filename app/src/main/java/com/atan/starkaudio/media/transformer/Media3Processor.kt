package com.atan.starkaudio.media.transformer

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.transformer.*
import com.atan.starkaudio.core.domain.MediaProcessor
import com.atan.starkaudio.core.domain.VaultRepository
import com.atan.starkaudio.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.time.Instant
import java.util.UUID
import java.nio.ByteBuffer
import kotlin.coroutines.resume

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3Processor(private val context: Context, private val vault: VaultRepository) : MediaProcessor {
    private val mutableState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    override val state: StateFlow<ProcessingState> = mutableState
    private var transformer: Transformer? = null

    override suspend fun process(plan: TransformPlan) {
        val id = UUID.randomUUID().toString()
        val outputDirectory = File(context.filesDir, "vault/prepared/$id").apply { mkdirs() }
        val outputs = mutableListOf<File>()
        val isolatedSource = if (plan.isolateSelectedTrack) {
            val extension = if (plan.selectedAudioMime == "audio/opus" || plan.selectedAudioMime == "audio/vorbis") "webm" else "m4a"
            val selected = File(context.cacheDir, "processing/selected-$id.$extension").apply { parentFile?.mkdirs() }
            try { isolateTrack(plan.source, plan.selectedAudioTrack, plan.selectedAudioMime, selected); selected }
            catch (_: Exception) {
                selected.delete(); outputDirectory.deleteRecursively()
                mutableState.value = ProcessingState.Error(AppError(ErrorCode.INPUT_UNSUPPORTED, "The selected audio track cannot be isolated safely on this device. The source was not changed."))
                return
            }
        } else null
        val effectiveSource = isolatedSource?.let(Uri::fromFile) ?: plan.source
        val sourceStart = plan.trim?.startMs ?: 0L
        val totalDuration = plan.durationMs.coerceAtLeast(1L)
        val partDuration = kotlin.math.ceil(totalDuration.toDouble() / plan.parts).toLong()
        mutableState.value = ProcessingState.Processing(0, totalDuration)
        for (part in 0 until plan.parts) {
            val start = sourceStart + part * partDuration
            val end = minOf(sourceStart + totalDuration, start + partDuration)
            val output = File(outputDirectory, if (plan.parts == 1) "audio.m4a" else "part-${(part + 1).toString().padStart(3, '0')}.m4a")
            val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build()
            val mediaItem = MediaItem.Builder().setUri(effectiveSource).setClippingConfiguration(clipping).build()
            val processors = mutableListOf<AudioProcessor>()
            processors += SonicAudioProcessor().apply { setOutputSampleRateHz(plan.outputSampleRate) }
            if (plan.outputChannels == 1) processors += ChannelMixingAudioProcessor().apply {
                for (inputChannels in 1..6) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(inputChannels, 1))
            }
            val edited = EditedMediaItem.Builder(mediaItem).setRemoveVideo(true).setEffects(Effects(processors, emptyList<Effect>())).build()
            val result = runExport(edited, output.absolutePath, plan.outputBitRate)
            if (result.isFailure || !verifyOutput(output, end - start, plan.safeTargetBytes)) {
                outputDirectory.deleteRecursively()
                isolatedSource?.delete()
                mutableState.value = ProcessingState.Error(AppError(ErrorCode.OUTPUT_VERIFY_FAILED, "The prepared audio could not be verified. The source was not changed."))
                return
            }
            outputs += output
            mutableState.value = ProcessingState.Processing((end - sourceStart).coerceAtMost(totalDuration), totalDuration)
        }
        val localPath = if (outputs.size == 1) outputs.single().absolutePath else outputDirectory.absolutePath
        vault.upsertAsset(MediaAsset(id, "Prepared audio", MediaKind.PREPARED_AUDIO, Instant.now(), totalDuration, outputs.sumOf { it.length() }, localPath, null, "audio/mp4", AssetStatus.READY, outputs.size))
        isolatedSource?.delete()
        mutableState.value = ProcessingState.Complete(id)
    }

    private suspend fun runExport(item: EditedMediaItem, outputPath: String, bitRate: Int): Result<Unit> = suspendCancellableCoroutine { continuation ->
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (continuation.isActive) continuation.resume(Result.success(Unit)) }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { if (continuation.isActive) continuation.resume(Result.failure(exportException)) }
        }
        val audioSettings = AudioEncoderSettings.Builder().setBitrate(bitRate).setProfile(android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC).build()
        val encoderFactory = DefaultEncoderFactory.Builder(context).setRequestedAudioEncoderSettings(audioSettings).build()
        transformer = Transformer.Builder(context).setAudioMimeType(MimeTypes.AUDIO_AAC).setEncoderFactory(encoderFactory).addListener(listener).build().also { it.start(item, outputPath) }
        continuation.invokeOnCancellation { transformer?.cancel() }
    }

    private fun verifyOutput(file: File, expectedDurationMs: Long, safeTargetBytes: Long): Boolean {
        if (!file.isFile || file.length() <= 0 || file.length() > safeTargetBytes) return false
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any {
                val format = extractor.getTrackFormat(it)
                val audio = format.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                val durationMs = if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) format.getLong(android.media.MediaFormat.KEY_DURATION) / 1000 else expectedDurationMs
                val tolerance = maxOf(2_000L, expectedDurationMs / 20)
                audio && kotlin.math.abs(durationMs - expectedDurationMs) <= tolerance
            }
        } catch (_: Exception) { false } finally { extractor.release() }
    }

    private fun isolateTrack(source: Uri, trackIndex: Int, mime: String?, output: File) {
        val extractor = android.media.MediaExtractor()
        var muxer: android.media.MediaMuxer? = null
        try {
            extractor.setDataSource(context, source, null)
            require(trackIndex in 0 until extractor.trackCount) { "Selected track is unavailable" }
            val format = extractor.getTrackFormat(trackIndex)
            val actualMime = format.getString(android.media.MediaFormat.KEY_MIME)
            require(actualMime?.startsWith("audio/") == true) { "Selected track is not audio" }
            val outputFormat = if (mime == "audio/opus" || mime == "audio/vorbis") android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            muxer = android.media.MediaMuxer(output.absolutePath, outputFormat)
            val destinationTrack = muxer.addTrack(format)
            muxer.start()
            extractor.selectTrack(trackIndex)
            val requested = if (format.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE) else 1024 * 1024
            val buffer = ByteBuffer.allocateDirect(requested.coerceIn(64 * 1024, 8 * 1024 * 1024))
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleFlags = extractor.sampleFlags
                var bufferFlags = 0
                if (sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    bufferFlags = bufferFlags or android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                }
                if (sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                    bufferFlags = bufferFlags or android.media.MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                }
                info.set(0, size, extractor.sampleTime, bufferFlags)
                muxer.writeSampleData(destinationTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            runCatching { muxer?.stop() }; runCatching { muxer?.release() }; extractor.release()
        }
        require(output.length() > 0) { "Selected track could not be isolated" }
    }
    override suspend fun cancel() { transformer?.cancel(); mutableState.value = ProcessingState.Error(AppError(ErrorCode.TRANSFORM_CANCELLED, "Preparation was cancelled. The source was not changed.")) }
}

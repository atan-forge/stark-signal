package com.atan.starkaudio.transcription

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Decodes directly from a granted URI. PCM exists only as a bounded in-memory chunk. */
internal class PcmChunkDecoder(private val context: Context) {
    suspend fun decode(
        source: Uri,
        startAtMs: Long = 0L,
        isCancelled: () -> Boolean,
        onChunk: suspend (startMs: Long, pcm16KhzMono: FloatArray) -> Unit
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, source, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No readable audio track is available.")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("Audio codec is unavailable.")
            extractor.selectTrack(trackIndex)
            if (startAtMs > 0L) extractor.seekTo(startAtMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime).also { it.configure(inputFormat, null, null, 0); it.start() }

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val targetSamples = 25 * 16_000
            val collector = FloatCollector(targetSamples)
            var emittedSamples = startAtMs.coerceAtLeast(0L) * 16L

            while (!outputEnded && !isCancelled()) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("Decoder input buffer is unavailable.")
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else android.media.AudioFormat.ENCODING_PCM_16BIT
                        require(encoding == android.media.AudioFormat.ENCODING_PCM_16BIT) { "The decoder returned an unsupported PCM format." }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outputIndex) ?: error("Decoder output buffer is unavailable.")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val mono = downmix(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels)
                            val resampled = resampleTo16Khz(mono, sampleRate)
                            var offset = 0
                            while (offset < resampled.size) {
                                val copied = collector.append(resampled, offset)
                                offset += copied
                                if (collector.isFull) {
                                    onChunk(emittedSamples * 1_000L / 16_000L, collector.take())
                                    emittedSamples += targetSamples
                                }
                            }
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            if (!isCancelled() && !collector.isEmpty) onChunk(emittedSamples * 1_000L / 16_000L, collector.take())
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun downmix(buffer: java.nio.ByteBuffer, channels: Int): FloatArray {
        val frames = buffer.remaining() / 2 / channels
        return FloatArray(frames) { frame ->
            var sum = 0f
            repeat(channels) { sum += buffer.short / 32768f }
            sum / channels
        }
    }

    private fun resampleTo16Khz(input: FloatArray, sourceRate: Int): FloatArray {
        if (sourceRate == 16_000) return input
        val count = max(1, (input.size.toLong() * 16_000L / sourceRate).toInt())
        return FloatArray(count) { index ->
            val position = index.toDouble() * sourceRate / 16_000.0
            val left = position.toInt().coerceIn(0, input.lastIndex)
            val right = min(left + 1, input.lastIndex)
            (input[left] + (input[right] - input[left]) * (position - left)).toFloat()
        }
    }

    private class FloatCollector(private val capacity: Int) {
        private val data = FloatArray(capacity)
        private var size = 0
        val isFull get() = size == capacity
        val isEmpty get() = size == 0
        fun append(values: FloatArray, offset: Int): Int {
            val count = min(capacity - size, values.size - offset)
            values.copyInto(data, size, offset, offset + count)
            size += count
            return count
        }
        fun take(): FloatArray = data.copyOf(size).also { size = 0 }
    }
}

package com.atan.starkaudio.benchmark

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object BenchmarkTimestampValidator {
    const val COMPRESSED_FRAME_TOLERANCE_MS = 100L

    data class Result(val valid: Boolean, val maximumTimestampMs: Long, val toleranceMs: Long)

    fun validate(segments: List<LongRange>, decodedSampleCount: Long): Result {
        val decodedDurationMs = decodedSampleCount.coerceAtLeast(0L) * 1_000L / 16_000L
        var previousEnd = 0L
        var maximum = 0L
        for (segment in segments) {
            val start = segment.first
            val end = segment.last
            if (start < 0L || end <= start || start < previousEnd || end > decodedDurationMs + COMPRESSED_FRAME_TOLERANCE_MS) {
                return Result(false, maxOf(maximum, end), COMPRESSED_FRAME_TOLERANCE_MS)
            }
            previousEnd = end
            maximum = maxOf(maximum, end)
        }
        return Result(true, maximum, COMPRESSED_FRAME_TOLERANCE_MS)
    }
}

object NativePageCompatibility {
    const val REQUIRED_ALIGNMENT = 16_384L

    fun maximumLoadAlignment(file: File): Long = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(64).also(raf::readFully)
            require(header[0] == 0x7f.toByte() && header[1].toInt().toChar() == 'E' && header[2].toInt().toChar() == 'L' && header[3].toInt().toChar() == 'F')
            require(header[4].toInt() == 2) { "Only 64-bit ELF is supported." }
            val order = if (header[5].toInt() == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val buffer = ByteBuffer.wrap(header).order(order)
            val programOffset = buffer.getLong(32)
            val entrySize = buffer.getShort(54).toInt() and 0xffff
            val entryCount = buffer.getShort(56).toInt() and 0xffff
            var maximum = 0L
            repeat(entryCount) { index ->
                raf.seek(programOffset + index.toLong() * entrySize)
                val entry = ByteArray(entrySize).also(raf::readFully)
                val item = ByteBuffer.wrap(entry).order(order)
                if (item.getInt(0) == 1) maximum = maxOf(maximum, item.getLong(48))
            }
            maximum
        }
    }.getOrDefault(0L)
}

package com.atan.starkaudio.media.inspector

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.atan.starkaudio.core.domain.MediaInspector
import com.atan.starkaudio.core.model.*

class AndroidMediaInspector(private val context: Context) : MediaInspector {
    override suspend fun inspect(uri: Uri): MediaInspection {
        val metadata = queryMetadata(uri)
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val audio = mutableListOf<AudioTrack>()
            var hasVideo = false
            var duration = 0L
            repeat(extractor.trackCount.coerceAtMost(64)) { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                duration = maxOf(duration, format.longOrNull(MediaFormat.KEY_DURATION) ?: 0L)
                when {
                    mime?.startsWith("audio/") == true -> audio += AudioTrack(
                        index, mime, if (android.os.Build.VERSION.SDK_INT >= 30) format.getString(MediaFormat.KEY_CODECS_STRING) else null,
                        format.getString(MediaFormat.KEY_LANGUAGE), format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                        format.intOrNull(MediaFormat.KEY_SAMPLE_RATE), format.intOrNull(MediaFormat.KEY_BIT_RATE)
                    )
                    mime?.startsWith("video/") == true -> hasVideo = true
                }
            }
            val status = when {
                audio.isEmpty() -> CompatibilityStatus.NO_AUDIO_TRACK
                audio.size > 1 -> CompatibilityStatus.MULTIPLE_AUDIO_TRACKS
                else -> CompatibilityStatus.UNKNOWN
            }
            MediaInspection(uri, metadata.first, metadata.second, duration.takeIf { it > 0 }?.div(1000), context.contentResolver.getType(uri), hasVideo, audio, status)
        } catch (_: SecurityException) {
            MediaInspection(uri, metadata.first, metadata.second, null, null, false, emptyList(), CompatibilityStatus.UNSUPPORTED, AppError(ErrorCode.INPUT_UNREADABLE, "Stark Signal cannot access this file."))
        } catch (_: Exception) {
            MediaInspection(uri, metadata.first, metadata.second, null, null, false, emptyList(), CompatibilityStatus.UNSUPPORTED, AppError(ErrorCode.INPUT_UNSUPPORTED, "This media file cannot be inspected safely."))
        } finally { extractor.release() }
    }

    private fun queryMetadata(uri: Uri): Pair<String, Long?> {
        var name = "Imported media"
        var size: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)?.sanitizeName() ?: name
                if (!c.isNull(1)) size = c.getLong(1).takeIf { it >= 0 }
            }
        }
        return name to size
    }
}

private fun String.sanitizeName(): String = filter { !it.isISOControl() }.trim().take(128).ifBlank { "Imported media" }
private fun MediaFormat.intOrNull(key: String) = if (containsKey(key)) getInteger(key) else null
private fun MediaFormat.longOrNull(key: String) = if (containsKey(key)) getLong(key) else null

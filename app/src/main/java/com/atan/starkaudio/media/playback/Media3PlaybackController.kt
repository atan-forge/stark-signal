package com.atan.starkaudio.media.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.atan.starkaudio.core.domain.PlaybackController
import com.atan.starkaudio.core.model.MediaAsset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class Media3PlaybackController(context: Context) : PlaybackController {
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutablePlaying = MutableStateFlow(false)
    private val mutablePosition = MutableStateFlow(0L)
    override val isPlaying: StateFlow<Boolean> = mutablePlaying
    override val positionMs: StateFlow<Long> = mutablePosition

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { mutablePlaying.value = isPlaying }
        })
        scope.launch { while (isActive) { mutablePosition.value = player.currentPosition.coerceAtLeast(0); delay(250) } }
    }
    override fun setAsset(asset: MediaAsset) {
        val items = when {
            asset.localPath != null -> {
                val file = File(asset.localPath)
                if (file.isDirectory) file.listFiles { f -> f.extension.equals("m4a", true) }?.sortedBy { it.name }?.map { MediaItem.fromUri(Uri.fromFile(it)) }.orEmpty()
                else listOf(MediaItem.fromUri(Uri.fromFile(file)))
            }
            asset.sourceUri != null -> listOf(MediaItem.fromUri(asset.sourceUri))
            else -> emptyList()
        }
        player.setMediaItems(items); player.prepare()
    }
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))
    override fun release() { scope.cancel(); player.release() }
}

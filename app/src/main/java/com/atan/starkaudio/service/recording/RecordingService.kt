package com.atan.starkaudio.service.recording

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.storage.StorageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.atan.starkaudio.MainActivity
import com.atan.starkaudio.R
import com.atan.starkaudio.StarkSignalApplication
import com.atan.starkaudio.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.Instant
import java.util.UUID

class RecordingService : Service(), MediaRecorder.OnInfoListener, MediaRecorder.OnErrorListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: MediaRecorder? = null
    private var sessionId: String? = null
    private var title: String = "Recording"
    private var preset = RecordingPreset.AI_READY
    private var safeTargetBytes = 24_000_000L
    private var currentPart = 1
    private var currentFile: File? = null
    private var nextFile: File? = null
    private var startedAt = 0L
    private var accumulatedMs = 0L
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var phase = Phase.IDLE

    private enum class Phase { IDLE, STARTING, RECORDING, PAUSED, ROTATING, STOPPING, FAILED }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startRecording(intent: Intent) {
        if (phase != Phase.IDLE || recorder != null) return
        val id = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
        sessionId = id
        title = intent.getStringExtra(EXTRA_TITLE)?.sanitizeTitle() ?: "Recording"
        preset = runCatching { RecordingPreset.valueOf(intent.getStringExtra(EXTRA_PRESET) ?: "AI_READY") }.getOrDefault(RecordingPreset.AI_READY)
        safeTargetBytes = intent.getLongExtra(EXTRA_SAFE_TARGET, 24_000_000L).coerceAtLeast(1_000_000L)
        phase = Phase.STARTING
        mutableState.value = RecordingState.Starting(preset)

        try {
            startForegroundNotification(starting = true)
        } catch (_: SecurityException) {
            fail(ErrorCode.REC_PERMISSION_DENIED, getString(R.string.permission_denied))
            return
        } catch (_: Exception) {
            fail(ErrorCode.REC_START_FAILED, getString(R.string.recording_start_failed))
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(ErrorCode.REC_PERMISSION_DENIED, getString(R.string.permission_denied))
            return
        }
        val graph = (application as StarkSignalApplication).services
        if (!runBlocking { graph.prepareForRecording() }) {
            fail(ErrorCode.REC_START_FAILED, getString(R.string.recording_busy))
            return
        }
        if (allocatableBytes() < safeTargetBytes + RECORDING_HEADROOM_BYTES) {
            fail(ErrorCode.REC_STORAGE_LOW, getString(R.string.recording_storage_low))
            return
        }
        currentPart = 1
        val directory = File(filesDir, "vault/recordings/$id").apply { mkdirs() }
        currentFile = File(directory, "part-001.m4a")
        try {
            acquireWakeLock()
            val instance = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            recorder = instance.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (Build.VERSION.SDK_INT >= 30) setPrivacySensitive(true)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(preset.channels)
                setAudioSamplingRate(preset.sampleRate)
                setAudioEncodingBitRate(preset.bitRate)
                setOutputFile(currentFile!!.absolutePath)
                setMaxFileSize(safeTargetBytes)
                setOnInfoListener(this@RecordingService)
                setOnErrorListener(this@RecordingService)
                prepare()
                start()
            }
            phase = Phase.RECORDING
            startedAt = SystemClock.elapsedRealtime()
            accumulatedMs = 0L
            updateNotification(false)
            persistAsset(AssetStatus.RECORDING)
            startTicker()
        } catch (_: SecurityException) {
            fail(ErrorCode.REC_PERMISSION_DENIED, getString(R.string.permission_denied))
        } catch (_: Exception) {
            fail(ErrorCode.REC_START_FAILED, getString(R.string.recording_start_failed))
        }
    }

    private fun allocatableBytes(): Long = runCatching {
        getSystemService(StorageManager::class.java).getAllocatableBytes(StorageManager.UUID_DEFAULT)
    }.getOrElse {
        filesDir.usableSpace
    }

    private fun pauseRecording() {
        if (phase != Phase.RECORDING) return
        val id = sessionId ?: return
        try {
            recorder?.pause()
            phase = Phase.PAUSED
            accumulatedMs += SystemClock.elapsedRealtime() - startedAt
            ticker?.cancel()
            mutableState.value = RecordingState.Paused(id, accumulatedMs, currentPart)
            persistAsset(AssetStatus.PAUSED)
            updateNotification(true)
        } catch (_: Exception) { fail(ErrorCode.REC_FINALIZE_FAILED, getString(R.string.recording_pause_failed)) }
    }

    private fun resumeRecording() {
        if (phase != Phase.PAUSED || recorder == null) return
        try {
            recorder?.resume()
            phase = Phase.RECORDING
            startedAt = SystemClock.elapsedRealtime()
            persistAsset(AssetStatus.RECORDING)
            startTicker()
            updateNotification(false)
        } catch (_: Exception) { fail(ErrorCode.REC_START_FAILED, getString(R.string.recording_resume_failed)) }
    }

    private fun stopRecording() {
        if (phase !in setOf(Phase.RECORDING, Phase.PAUSED, Phase.ROTATING)) return
        val id = sessionId ?: return
        if (phase == Phase.RECORDING || phase == Phase.ROTATING) {
            accumulatedMs += (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        }
        phase = Phase.STOPPING
        mutableState.value = RecordingState.Stopping(id)
        ticker?.cancel()
        try {
            recorder?.stop()
            releaseRecorder()
            val files = completedParts()
            if (files.isEmpty()) throw IllegalStateException("No readable output")
            mutableState.value = RecordingState.Complete(id)
            persistAsset(AssetStatus.READY) { finishService() }
        } catch (_: Exception) {
            releaseRecorder()
            phase = Phase.FAILED
            mutableState.value = RecordingState.Failed(AppError(ErrorCode.REC_FINALIZE_FAILED, getString(R.string.recording_finalize_failed)))
            persistAsset(AssetStatus.INTERRUPTED) { finishService() }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val elapsed = accumulatedMs + (SystemClock.elapsedRealtime() - startedAt)
                val size = currentFile?.parentFile?.listFiles()?.sumOf { it.length() } ?: 0L
                val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                sessionId?.let { mutableState.value = RecordingState.Recording(it, elapsed, size, currentPart, amplitude) }
                if ((elapsed / 1000) % 10L == 0L) updateNotification(false)
                delay(250)
            }
        }
    }

    override fun onInfo(mr: MediaRecorder?, what: Int, extra: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {
                if (phase != Phase.RECORDING) return
                if (allocatableBytes() < safeTargetBytes + RECORDING_HEADROOM_BYTES) {
                    fail(ErrorCode.REC_STORAGE_LOW, getString(R.string.recording_storage_low_rotation))
                    return
                }
                val directory = currentFile?.parentFile ?: return
                val candidate = File(directory, "part-${(currentPart + 1).toString().padStart(3, '0')}.m4a")
                phase = Phase.ROTATING
                sessionId?.let { mutableState.value = RecordingState.Rotating(it, currentPart) }
                runCatching { recorder?.setNextOutputFile(candidate); nextFile = candidate; phase = Phase.RECORDING }
                    .onFailure { fail(ErrorCode.REC_STORAGE_FULL, getString(R.string.recording_next_part_failed)) }
            }
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                currentPart += 1
                currentFile = nextFile
                nextFile = null
                phase = Phase.RECORDING
                persistAsset(AssetStatus.RECORDING)
            }
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> fail(ErrorCode.REC_STORAGE_FULL, getString(R.string.recording_part_limit_reached))
        }
    }

    override fun onError(mr: MediaRecorder?, what: Int, extra: Int) = fail(ErrorCode.REC_FINALIZE_FAILED, getString(R.string.recording_system_error))

    private fun persistAsset(status: AssetStatus, after: (() -> Unit)? = null) {
        val id = sessionId ?: return
        val directory = currentFile?.parentFile ?: return
        val elapsed = when (val s = mutableState.value) {
            is RecordingState.Recording -> s.elapsedMs
            is RecordingState.Paused -> s.elapsedMs
            else -> accumulatedMs
        }
        scope.launch {
            persistAssetNow(id, directory, elapsed, status)
            after?.invoke()
        }
    }

    private suspend fun persistAssetNow(id: String, directory: File, elapsed: Long, status: AssetStatus) {
        val vault = (application as StarkSignalApplication).services.vault
        val createdAt = vault.getAsset(id)?.createdAt ?: Instant.now()
        vault.upsertAsset(MediaAsset(id, title, MediaKind.RECORDING, createdAt, elapsed, directory.listFiles()?.sumOf { it.length() } ?: 0L, directory.absolutePath, null, "audio/mp4", status, currentPart))
    }

    private fun fail(code: ErrorCode, message: String) {
        ticker?.cancel()
        releaseRecorder()
        phase = Phase.FAILED
        mutableState.value = RecordingState.Failed(AppError(code, message))
        val directory = currentFile?.parentFile
        if (directory != null && directory.exists()) persistAsset(AssetStatus.INTERRUPTED) { finishService() }
        else finishService()
    }

    private fun startForegroundNotification(starting: Boolean) {
        val notification = buildNotification(paused = false, starting = starting)
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else @Suppress("DEPRECATION") startForeground(NOTIFICATION_ID, notification)
        foregroundStarted = true
    }
    private fun updateNotification(paused: Boolean) = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(paused, starting = false))
    private fun buildNotification(paused: Boolean, starting: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pauseResume = servicePendingIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, 1)
        val stop = servicePendingIntent(ACTION_STOP, 2)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setContentTitle(when { starting -> getString(R.string.recording_starting); paused -> getString(R.string.recording_paused); else -> getString(R.string.recording) })
            .setContentText(getString(R.string.recording_notification_private))
            .setContentIntent(openIntent).setOngoing(true).setOnlyAlertOnce(true)
            .apply {
                if (!starting) addAction(0, getString(if (paused) R.string.resume else R.string.pause), pauseResume)
                addAction(0, getString(R.string.stop), stop)
            }.build()
    }
    private fun servicePendingIntent(action: String, requestCode: Int) = PendingIntent.getService(this, requestCode, Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.recording_channel_name), NotificationManager.IMPORTANCE_LOW).apply { description = getString(R.string.recording_channel_description); setShowBadge(false) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
    override fun onTaskRemoved(rootIntent: Intent?) { if (recorder != null) persistAsset(AssetStatus.RECORDING) }
    override fun onDestroy() {
        ticker?.cancel()
        if (recorder != null) {
            releaseRecorder()
            val id = sessionId
            val directory = currentFile?.parentFile
            if (id != null && directory != null && directory.exists()) runBlocking(Dispatchers.IO) {
                runCatching { persistAssetNow(id, directory, accumulatedMs, AssetStatus.INTERRUPTED) }
            }
        }
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.atan.starkaudio.record.START"
        const val ACTION_PAUSE = "com.atan.starkaudio.record.PAUSE"
        const val ACTION_RESUME = "com.atan.starkaudio.record.RESUME"
        const val ACTION_STOP = "com.atan.starkaudio.record.STOP"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_SAFE_TARGET = "safe_target"
        private const val CHANNEL_ID = "active_recording"
        private const val NOTIFICATION_ID = 4101
        private const val RECORDING_HEADROOM_BYTES = 10_000_000L
        private const val MAX_WAKE_LOCK_MS = 12L * 60L * 60L * 1_000L
        private val mutableState = MutableStateFlow<RecordingState>(RecordingState.Idle)
        val state: StateFlow<RecordingState> = mutableState

        fun startIntent(context: Context, title: String, preset: RecordingPreset, safeTargetBytes: Long) = Intent(context, RecordingService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_SESSION_ID, UUID.randomUUID().toString())
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_PRESET, preset.name)
            putExtra(EXTRA_SAFE_TARGET, safeTargetBytes)
        }
        fun actionIntent(context: Context, action: String) = Intent(context, RecordingService::class.java).setAction(action)
    }

    private fun completedParts(): List<File> = currentFile?.parentFile?.listFiles()
        ?.filter { it.isFile && it.extension.equals("m4a", true) && it.length() > 0L }
        ?.sortedBy { it.name }
        .orEmpty()

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        nextFile = null
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:recording")
            .apply { setReferenceCounted(false); acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun finishService() {
        releaseWakeLock()
        if (foregroundStarted) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        if (phase != Phase.FAILED) phase = Phase.IDLE
        runBlocking { (application as StarkSignalApplication).services.heavyWork.release(HeavyWorkType.RECORDING) }
        stopSelf()
    }

}

private fun String.sanitizeTitle() = filter { !it.isISOControl() && it !in "\\/:*?\"<>|" }.trim().take(80).ifBlank { "Recording" }

package com.atan.starkaudio

import android.app.Application
import com.atan.starkaudio.storage.RoomVaultRepository
import com.atan.starkaudio.storage.SettingsRepository
import com.atan.starkaudio.storage.VaultDatabase
import com.atan.starkaudio.storage.TranscriptionJobStore
import com.atan.starkaudio.storage.BenchmarkRunStore
import com.atan.starkaudio.compatibility.DefaultCompatibilityEngine
import com.atan.starkaudio.compatibility.DefaultTransformPlanner
import com.atan.starkaudio.media.inspector.AndroidMediaInspector
import com.atan.starkaudio.service.HeavyWorkCoordinatorImpl
import com.atan.starkaudio.media.transformer.Media3Processor
import com.atan.starkaudio.transcription.WhisperTranscriptionEngine
import com.atan.starkaudio.transcription.BundledModelInstaller
import com.atan.starkaudio.core.model.AssetStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class StarkSignalApplication : Application() {
    val services by lazy { ServiceGraph(this) }
}

class ServiceGraph(application: Application) {
    private val database = VaultDatabase.get(application)
    val vault = RoomVaultRepository(application, database.dao())
    val settings = SettingsRepository(application)
    val mediaInspector = AndroidMediaInspector(application)
    val compatibility = DefaultCompatibilityEngine()
    val transformPlanner = DefaultTransformPlanner()
    val heavyWork = HeavyWorkCoordinatorImpl()
    val mediaProcessor = Media3Processor(application, vault)
    val transcriptionJobs = TranscriptionJobStore(database.dao())
    val benchmarkRuns = BenchmarkRunStore(database.dao())
    val transcriptionEngine = WhisperTranscriptionEngine(application, BundledModelInstaller.manifestOrNull(), heavyWork, transcriptionJobs)
    suspend fun prepareForRecording(): Boolean {
        mediaProcessor.cancel()
        transcriptionEngine.cancelAllForRecording()
        return heavyWork.acquire(com.atan.starkaudio.core.model.HeavyWorkType.RECORDING)
    }
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        maintenanceScope.launch {
            vault.recoverPendingDeletions()
            vault.observeAssets().first()
                .filter { it.status == AssetStatus.RECORDING || it.status == AssetStatus.PAUSED }
                .forEach { vault.upsertAsset(it.copy(status = AssetStatus.INTERRUPTED)) }
            java.io.File(application.cacheDir, "processing").takeIf { it.exists() }?.listFiles()?.forEach { it.deleteRecursively() }
        }
    }
}

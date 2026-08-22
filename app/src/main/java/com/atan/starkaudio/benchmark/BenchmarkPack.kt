package com.atan.starkaudio.benchmark

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.atan.starkaudio.storage.BenchmarkRunStore
import com.atan.starkaudio.transcription.BundledModelInstaller
import com.atan.starkaudio.transcription.BundledModelManifest
import com.atan.starkaudio.transcription.PcmChunkDecoder
import com.atan.starkaudio.transcription.WhisperNativeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** Benchmark-only runner. References and recognized text remain in memory and never enter reports or logs. */
class BenchmarkRunner(
    private val context: Context,
    private val model: BundledModelManifest,
    private val store: BenchmarkRunStore,
    private val bridge: WhisperNativeBridge = WhisperNativeBridge
) {
    suspend fun runReliabilityChecks(
        report: BenchmarkReport,
        onProgress: (processedMs: Long, targetMs: Long) -> Unit = { _, _ -> }
    ): BenchmarkReport = withContext(Dispatchers.Default) {
        val modelFile = BundledModelInstaller.installIfNeeded(context, model)
        val checks = mutableListOf<ReliabilityCheckResult>()
        var repeatedPassed = true
        runCatching {
            repeat(5) {
                val handle = bridge.createSession(modelFile.absolutePath)
                if (handle == 0L) error("session_create_failed")
                try { bridge.resetSession(handle) } finally { bridge.destroySession(handle) }
            }
        }.onFailure { repeatedPassed = false }
        checks += ReliabilityCheckResult("repeated_load_reset_unload", repeatedPassed, if (repeatedPassed) null else "session_cycle_failed")

        val manifest = JSONObject(context.assets.open("benchmark-pack/manifest.json").bufferedReader().use { it.readText() })
        val cases = manifest.getJSONArray("cases")
        val first = cases.getJSONObject(0)
        val firstAudio = copyAsset(first.getString("audioAsset"))
        var firstPcm: FloatArray? = null
        PcmChunkDecoder(context).decode(Uri.fromFile(firstAudio), isCancelled = { firstPcm != null }) { _, pcm -> firstPcm = pcm }
        firstAudio.delete()

        var cancellationPassed = false
        val cancelHandle = bridge.createSession(modelFile.absolutePath)
        if (cancelHandle != 0L && firstPcm != null) {
            try {
                coroutineScope {
                    val pending = async(Dispatchers.Default) { bridge.transcribeChunk(cancelHandle, firstPcm!!, "en", threadCount()) {} }
                    delay(100L)
                    bridge.cancel(cancelHandle)
                    cancellationPassed = pending.await().error == "cancelled"
                }
            } finally { bridge.destroySession(cancelHandle) }
        }
        checks += ReliabilityCheckResult("cancellation", cancellationPassed, if (cancellationPassed) null else "cancellation_not_observed")

        val recoveryPassed = report.recoveryPassed
        checks += ReliabilityCheckResult("process_recovery", recoveryPassed, if (recoveryPassed) null else "new_process_resume_required")

        val targetMs = 60L * 60L * 1_000L
        var processedMs = 0L
        var endurancePassed = true
        val enduranceHandle = bridge.createSession(modelFile.absolutePath)
        if (enduranceHandle == 0L) endurancePassed = false
        val enduranceContext = currentCoroutineContext()
        try {
            var index = 0
            while (endurancePassed && processedMs < targetMs && currentCoroutineContext().isActive) {
                val item = cases.getJSONObject(index % cases.length())
                val audio = copyAsset(item.getString("audioAsset"))
                try {
                    PcmChunkDecoder(context).decode(Uri.fromFile(audio), isCancelled = { !enduranceContext.isActive }) { _, pcm ->
                        bridge.resetSession(enduranceHandle)
                        val native = bridge.transcribeChunk(enduranceHandle, pcm, languageFor(item.getString("languageGroup")), threadCount()) {}
                        if (native.error != null || native.segments.any { it.endMs <= it.startMs }) endurancePassed = false
                    }
                    processedMs += readDurationMs(audio)
                    onProgress(processedMs.coerceAtMost(targetMs), targetMs)
                } finally { audio.delete() }
                index++
            }
        } catch (cancelled: CancellationException) {
            if (enduranceHandle != 0L) bridge.cancel(enduranceHandle)
            throw cancelled
        } catch (_: Throwable) {
            endurancePassed = false
        } finally {
            if (enduranceHandle != 0L) bridge.destroySession(enduranceHandle)
        }
        checks += ReliabilityCheckResult("endurance_60_min", endurancePassed && processedMs >= targetMs, if (endurancePassed && processedMs >= targetMs) null else "endurance_incomplete", processedMs)
        val pageSize = runCatching { android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrDefault(0L)
        val nativeLibrary = File(context.applicationInfo.nativeLibraryDir, "libstark_whisper.so")
        val nativeAlignment = NativePageCompatibility.maximumLoadAlignment(nativeLibrary)
        val pageCompatible = nativeAlignment >= NativePageCompatibility.REQUIRED_ALIGNMENT
        checks += ReliabilityCheckResult("native_16kb_alignment", pageCompatible, if (pageCompatible) null else "native_alignment_below_16kb")
        report.copy(
            cancellationPassed = cancellationPassed,
            recoveryPassed = recoveryPassed,
            repeatedLoadUnloadPassed = repeatedPassed,
            endurance60MinPassed = endurancePassed && processedMs >= targetMs,
            page16KbCompatible = pageCompatible,
            devicePageSizeBytes = pageSize,
            nativeLoadAlignmentBytes = nativeAlignment,
            reliabilityChecks = checks
        )
    }

    suspend fun run(onCaseComplete: (Int, Int) -> Unit = { _, _ -> }): BenchmarkReport = withContext(Dispatchers.Default) {
        val manifest = JSONObject(context.assets.open("benchmark-pack/manifest.json").bufferedReader().use { it.readText() })
        require(manifest.optInt("schemaVersion") >= 2) { "The benchmark pack schema is unsupported." }
        val cases = manifest.getJSONArray("cases")
        val groups = manifest.optJSONObject("groups")
        val packComplete = listOf("en", "id", "en-id").all { groups?.optJSONObject(it)?.optBoolean("complete", false) == true }
        val memoryInfo = android.app.ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it)
        }
        require(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") { "Benchmark approval requires an arm64-v8a phone." }
        require(memoryInfo.totalMem >= 4L * 1024 * 1024 * 1024) { "Benchmark approval requires at least 4 GB RAM." }

        val run = store.startOrResume(model.id, model.sha256, model.engineVersion, Build.SUPPORTED_ABIS.first(), memoryInfo.totalMem, batteryPercent())
        val completed = store.completedCaseIds(run.id)
        val results = ArrayList<BenchmarkCaseResult>(cases.length()).apply { addAll(store.results(run.id)) }
        val peakRss = AtomicLong(max(run.peakRssBytes, currentRssBytes()))
        val batteryStart = batteryPercent()
        val modelFile = BundledModelInstaller.installIfNeeded(context, model)
        val loadStarted = SystemClock.elapsedRealtime()
        val session = bridge.createSession(modelFile.absolutePath)
        val modelLoadMs = SystemClock.elapsedRealtime() - loadStarted
        require(session != 0L) { "The verified model could not be loaded." }

        try {
            repeat(cases.length()) { index ->
                val item = cases.getJSONObject(index)
                if (item.getString("caseId") in completed) {
                    onCaseComplete(index + 1, cases.length())
                    return@repeat
                }
                val audio = copyAsset(item.getString("audioAsset"))
                val manifestDuration = item.optLong("durationMs", readDurationMs(audio))
                require(manifestDuration in 1_000L..30_500L) { "A benchmark clip has an invalid duration." }
                val hypothesis = StringBuilder()
                var inferenceMs = 0L
                var detectedLanguage: String? = null
                var decodedSampleCount = 0L
                val timestampRanges = mutableListOf<LongRange>()
                var errorCode: String? = null
                val totalStarted = SystemClock.elapsedRealtime()

                coroutineScope {
                    val sampler: Job = launch(Dispatchers.Default) {
                        while (isActive) {
                            peakRss.accumulateAndGet(currentRssBytes(), ::maxOf)
                            delay(100)
                        }
                    }
                    try {
                        val runContext = currentCoroutineContext()
                        PcmChunkDecoder(context).decode(Uri.fromFile(audio), isCancelled = { !runContext.isActive }) { chunkStartMs, pcm ->
                            decodedSampleCount += pcm.size
                            bridge.resetSession(session)
                            val inferenceStarted = SystemClock.elapsedRealtime()
                            val native = bridge.transcribeChunk(session, pcm, languageFor(item.getString("languageGroup")), threadCount()) {}
                            inferenceMs += SystemClock.elapsedRealtime() - inferenceStarted
                            if (native.error != null) {
                                errorCode = native.error
                                return@decode
                            }
                            detectedLanguage = native.detectedLanguage ?: detectedLanguage
                            native.segments.forEach { segment ->
                                timestampRanges += (chunkStartMs + segment.startMs)..(chunkStartMs + segment.endMs)
                                val text = segment.text.trim()
                                if (text.isNotBlank()) {
                                    if (hypothesis.isNotEmpty()) hypothesis.append(' ')
                                    hypothesis.append(text)
                                }
                            }
                        }
                    } finally {
                        sampler.cancel()
                    }
                }

                val elapsed = SystemClock.elapsedRealtime() - totalStarted
                val decodeMs = (elapsed - inferenceMs).coerceAtLeast(0L)
                val decodedDuration = decodedSampleCount * 1_000L / 16_000L
                val timestampValidation = BenchmarkTimestampValidator.validate(timestampRanges, decodedSampleCount)
                val measurement = WordErrorRate.measure(item.getString("referenceText"), hypothesis.toString())
                val timestampError = if (!timestampValidation.valid) "invalid_timestamps" else null
                val catastrophic = errorCode != null || hypothesis.isBlank() || timestampError != null || measurement.rate >= 1.0
                val value = BenchmarkCaseResult(
                    caseId = item.getString("caseId"),
                    languageGroup = item.getString("languageGroup"),
                    audioDurationMs = decodedDuration,
                    elapsedMs = elapsed,
                    wer = measurement.rate,
                    catastrophic = catastrophic,
                    errorCode = errorCode ?: timestampError,
                    decodeMs = decodeMs,
                    inferenceMs = inferenceMs,
                    editDistance = measurement.editDistance,
                    referenceWordCount = measurement.referenceWordCount,
                    detectedLanguage = detectedLanguage,
                    manifestDurationMs = manifestDuration,
                    decodedSampleCount = decodedSampleCount,
                    decodedDurationMs = decodedDuration,
                    maximumTimestampMs = timestampValidation.maximumTimestampMs,
                    timestampToleranceMs = timestampValidation.toleranceMs
                )
                results += value
                store.saveCase(run.id, value, peakRss.get(), thermalStatus(), batteryPercent())
                audio.delete()
                onCaseComplete(index + 1, cases.length())
            }
            store.finish(run.id, BenchmarkRunStatus.COMPLETE)
            BenchmarkReport(
                modelId = model.id,
                modelSha256 = model.sha256,
                engineVersion = model.engineVersion,
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".take(120),
                androidSdk = Build.VERSION.SDK_INT,
                totalRamBytes = memoryInfo.totalMem,
                peakRssBytes = peakRss.get(),
                maxThermalStatus = thermalStatus(),
                batteryStartPercent = batteryStart,
                batteryEndPercent = batteryPercent(),
                cases = results.sortedBy { it.caseId },
                modelLoadMs = modelLoadMs,
                recoveryPassed = run.recoveredFromProcessDeath,
                benchmarkPackComplete = packComplete
            )
        } catch (cancelled: CancellationException) {
            bridge.cancel(session)
            store.finish(run.id, BenchmarkRunStatus.INCOMPLETE, "cancelled")
            throw cancelled
        } catch (error: Throwable) {
            store.finish(run.id, BenchmarkRunStatus.FAILED, "benchmark_failed")
            throw error
        } finally {
            bridge.destroySession(session)
        }
    }

    private fun languageFor(group: String): String? = when (group) {
        "en" -> "en"
        "id" -> "id"
        else -> null
    }

    private fun threadCount(): Int = max(1, Runtime.getRuntime().availableProcessors() / 2)

    private fun copyAsset(relativePath: String): File {
        require(!relativePath.contains("..") && relativePath.startsWith("audio/")) { "Invalid benchmark asset path." }
        val output = File(context.cacheDir, "benchmark/${relativePath.substringAfterLast('/')}").also { it.parentFile?.mkdirs() }
        context.assets.open("benchmark-pack/$relativePath").use { input -> output.outputStream().use { input.copyTo(it) } }
        return output
    }

    private fun readDurationMs(audio: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audio.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { retriever.release() }
    }

    private fun currentRssBytes(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.first { it.startsWith("VmRSS:") }.split(Regex("\\s+")).getOrNull(1)?.toLong()?.times(1024L) ?: 0L
        }
    }.getOrDefault(0L)

    private fun batteryPercent(): Int {
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) -1 else level * 100 / scale
    }

    private fun thermalStatus(): Int = if (Build.VERSION.SDK_INT >= 29) {
        (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus
    } else -1

}

package com.atan.starkaudio.storage

import com.atan.starkaudio.benchmark.BenchmarkCaseResult
import com.atan.starkaudio.benchmark.BenchmarkRunStatus
import java.util.UUID

class BenchmarkRunStore(private val dao: VaultDao) {
    suspend fun startOrResume(modelId: String, modelHash: String, engineVersion: String, abi: String, totalRamBytes: Long, battery: Int): BenchmarkRunEntity {
        val existing = dao.recoverableBenchmarkRun()?.takeIf { it.modelHash == modelHash }
        val now = System.currentTimeMillis()
        val recoveredFromDeath = existing != null && existing.processInstanceId.isNotBlank() && existing.processInstanceId != PROCESS_INSTANCE_ID
        val run = existing?.copy(
            status = BenchmarkRunStatus.RUNNING.name,
            updatedAtEpochMs = now,
            errorCode = null,
            resumeCount = existing.resumeCount + 1,
            processInstanceId = PROCESS_INSTANCE_ID,
            recoveredFromProcessDeath = existing.recoveredFromProcessDeath || recoveredFromDeath
        ) ?: BenchmarkRunEntity(
            id = UUID.randomUUID().toString(), status = BenchmarkRunStatus.RUNNING.name, modelId = modelId,
            modelHash = modelHash, engineVersion = engineVersion, abi = abi, totalRamBytes = totalRamBytes,
            createdAtEpochMs = now, updatedAtEpochMs = now, batteryStartPercent = battery,
            batteryEndPercent = battery, maxThermalStatus = -1, peakRssBytes = 0, errorCode = null,
            resumeCount = 0, processInstanceId = PROCESS_INSTANCE_ID, recoveredFromProcessDeath = false
        )
        dao.upsertBenchmarkRun(run)
        return run
    }

    suspend fun completedCaseIds(runId: String): Set<String> = dao.benchmarkCaseResults(runId).mapTo(mutableSetOf()) { it.caseId }
    suspend fun saveCase(runId: String, value: BenchmarkCaseResult, peakRss: Long, thermal: Int, battery: Int) {
        dao.upsertBenchmarkCaseResult(BenchmarkCaseResultEntity(runId, value.caseId, value.languageGroup, value.audioDurationMs, value.decodeMs, value.inferenceMs, value.elapsedMs, value.wer, value.catastrophic, value.errorCode, value.editDistance, value.referenceWordCount, value.detectedLanguage, value.manifestDurationMs, value.decodedSampleCount, value.decodedDurationMs, value.maximumTimestampMs, value.timestampToleranceMs))
        dao.getBenchmarkRun(runId)?.let { dao.upsertBenchmarkRun(it.copy(updatedAtEpochMs = System.currentTimeMillis(), peakRssBytes = maxOf(it.peakRssBytes, peakRss), maxThermalStatus = maxOf(it.maxThermalStatus, thermal), batteryEndPercent = battery)) }
    }
    suspend fun finish(runId: String, status: BenchmarkRunStatus, errorCode: String? = null) { dao.getBenchmarkRun(runId)?.let { dao.upsertBenchmarkRun(it.copy(status = status.name, updatedAtEpochMs = System.currentTimeMillis(), errorCode = errorCode)) } }
    suspend fun results(runId: String): List<BenchmarkCaseResult> = dao.benchmarkCaseResults(runId).map { BenchmarkCaseResult(it.caseId, it.languageGroup, it.audioDurationMs, it.elapsedMs, it.wer, it.catastrophic, it.errorCode, it.decodeMs, it.inferenceMs, it.editDistance, it.referenceWordCount, it.detectedLanguage, it.manifestDurationMs, it.decodedSampleCount, it.decodedDurationMs, it.maximumTimestampMs, it.timestampToleranceMs) }

    private companion object { val PROCESS_INSTANCE_ID: String = UUID.randomUUID().toString() }
}

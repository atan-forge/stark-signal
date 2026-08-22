package com.atan.starkaudio.storage

import com.atan.starkaudio.core.model.TranscriptSegment
import java.time.Instant

class TranscriptionJobStore(private val dao: VaultDao) {
    data class ResumePoint(val jobId: String, val processedMs: Long, val segments: List<TranscriptSegment>)

    suspend fun recoverable(assetId: String, languageMode: String, modelHash: String): ResumePoint? {
        val job = dao.recoverableJobForAsset(assetId, languageMode)?.takeIf { it.modelHash == modelHash } ?: return null
        return ResumePoint(job.id, job.processedMs, checkpoints(job.id))
    }
    suspend fun start(id: String, assetId: String, languageMode: String, modelId: String, modelHash: String, durationMs: Long) {
        val existing = dao.getJob(id)
        val now = Instant.now().toEpochMilli()
        dao.upsertJob(existing?.copy(status = "RUNNING", updatedAtEpochMs = now, errorCode = null) ?: TranscriptionJobEntity(id, assetId, languageMode, "RUNNING", modelId, modelHash, durationMs, 0, now, now, null))
    }

    suspend fun checkpoint(id: String, processedMs: Long, segments: List<TranscriptSegment>) {
        if (segments.isNotEmpty()) dao.upsertCheckpoints(segments.map { TranscriptionCheckpointEntity(id, it.index, it.startMs, it.endMs, it.text) })
        dao.getJob(id)?.let { dao.upsertJob(it.copy(processedMs = processedMs.coerceIn(0, it.durationMs), updatedAtEpochMs = Instant.now().toEpochMilli())) }
    }

    suspend fun complete(id: String) { dao.getJob(id)?.let { dao.upsertJob(it.copy(status = "COMPLETE", processedMs = it.durationMs, updatedAtEpochMs = Instant.now().toEpochMilli())) } }
    suspend fun fail(id: String, code: String) { dao.getJob(id)?.let { dao.upsertJob(it.copy(status = "FAILED", updatedAtEpochMs = Instant.now().toEpochMilli(), errorCode = code)) } }
    suspend fun cancel(id: String) { dao.getJob(id)?.let { dao.upsertJob(it.copy(status = "PAUSED", updatedAtEpochMs = Instant.now().toEpochMilli(), errorCode = "cancelled")) } }
    suspend fun checkpoints(id: String): List<TranscriptSegment> = dao.checkpoints(id).map { TranscriptSegment(it.segmentIndex, it.startMs, it.endMs, it.text) }
}

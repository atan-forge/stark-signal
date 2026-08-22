package com.atan.starkaudio.storage

import android.content.Context
import android.net.Uri
import com.atan.starkaudio.core.domain.VaultRepository
import com.atan.starkaudio.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.Instant

class RoomVaultRepository(context: Context, private val dao: VaultDao) : VaultRepository {
    private val vaultRoot = File(context.filesDir, "vault").canonicalFile
    override fun observeAssets(): Flow<List<MediaAsset>> = dao.observeAssets().map { rows -> rows.map { it.toDomain() } }
    override fun observeTranscripts(query: String): Flow<List<Transcript>> = dao.observeTranscripts().map { rows ->
        rows.map { it.toDomain() }.filter { transcript -> query.isBlank() || transcript.segments.any { it.text.contains(query, ignoreCase = true) } }
    }
    override suspend fun getAsset(id: String): MediaAsset? = dao.getAsset(id)?.toDomain()
    override suspend fun upsertAsset(asset: MediaAsset) = dao.upsertAsset(asset.toEntity())
    override suspend fun planDeletion(id: String): DeletePlan? = dao.getAsset(id)?.let { asset ->
        val target = if (asset.localPath != null && asset.kind in setOf(MediaKind.RECORDING.name, MediaKind.PREPARED_AUDIO.name)) {
            DeleteTarget.OWNED_MEDIA
        } else DeleteTarget.IMPORT_REFERENCE
        DeletePlan(asset.id, asset.title, target, keepsTranscript = asset.transcriptId != null)
    }

    override suspend fun deleteAsset(id: String): DeleteResult {
        val asset = dao.getAsset(id) ?: return DeleteResult.NotFound
        val plan = planDeletion(id) ?: return DeleteResult.NotFound
        val original = asset.copy(status = if (asset.status == AssetStatus.DELETING.name) AssetStatus.READY.name else asset.status)
        val entry = DeletionJournalEntity(asset.id, plan.target.name, asset.localPath, original.status, System.currentTimeMillis())
        dao.beginDeletion(asset, entry)
        return finishDeletion(entry, original, plan)
    }

    override suspend fun recoverPendingDeletions() {
        dao.pendingDeletions().forEach { entry ->
            val asset = dao.getAsset(entry.assetId)
            if (asset == null) {
                dao.deleteDeletionJournal(entry.assetId)
                return@forEach
            }
            val original = asset.copy(status = entry.originalStatus)
            val plan = DeletePlan(asset.id, asset.title, DeleteTarget.valueOf(entry.target), keepsTranscript = asset.transcriptId != null)
            finishDeletion(entry, original, plan)
        }
    }

    private suspend fun finishDeletion(entry: DeletionJournalEntity, original: MediaAssetEntity, plan: DeletePlan): DeleteResult {
        val removed = when (plan.target) {
            DeleteTarget.IMPORT_REFERENCE -> true
            DeleteTarget.OWNED_MEDIA -> entry.localPath?.let { PrivateVaultFiles.deleteTree(vaultRoot, it) } ?: false
        }
        return if (removed) {
            dao.completeDeletion(entry.assetId)
            DeleteResult.Deleted(plan)
        } else {
            dao.rollbackDeletion(original)
            DeleteResult.Failed(plan, "The private file could not be removed. Nothing was deleted from the library.")
        }
    }

    override suspend fun upsertTranscript(transcript: Transcript) {
        dao.upsertTranscript(transcript.toEntity())
        dao.upsertSegments(transcript.segments.map { TranscriptSegmentEntity(transcript.id, it.index, it.startMs, it.endMs, it.text) })
    }
    override suspend fun deleteTranscript(id: String) = dao.deleteTranscript(id)
}

private fun MediaAssetEntity.toDomain() = MediaAsset(id, title, MediaKind.valueOf(kind), Instant.ofEpochMilli(createdAtEpochMs), durationMs, sizeBytes, localPath, sourceUri?.let(Uri::parse), mimeType, AssetStatus.valueOf(status), partCount, transcriptId)
private fun MediaAsset.toEntity() = MediaAssetEntity(id, title, kind.name, createdAt.toEpochMilli(), durationMs, sizeBytes, localPath, sourceUri?.toString(), mimeType, status.name, partCount, transcriptId)
private fun TranscriptWithSegments.toDomain() = Transcript(transcript.id, transcript.audioAssetId, transcript.engineId, transcript.engineVersion, transcript.modelId, transcript.modelHash, LanguageMode.valueOf(transcript.languageMode), transcript.detectedLanguage, Instant.ofEpochMilli(transcript.createdAtEpochMs), transcript.durationMs, segments.sortedBy { it.segmentIndex }.map { TranscriptSegment(it.segmentIndex, it.startMs, it.endMs, it.text) })
private fun Transcript.toEntity() = TranscriptEntity(id, audioAssetId, engineId, engineVersion, modelId, modelHash, languageMode.name, detectedLanguage, createdAt.toEpochMilli(), durationMs)

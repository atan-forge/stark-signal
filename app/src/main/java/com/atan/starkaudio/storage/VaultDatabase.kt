package com.atan.starkaudio.storage

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.atan.starkaudio.core.model.AssetStatus
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "media_assets")
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val localPath: String?,
    val sourceUri: String?,
    val mimeType: String?,
    val status: String,
    val partCount: Int,
    val transcriptId: String?
)

@Entity(tableName = "transcripts", indices = [Index("audioAssetId")])
data class TranscriptEntity(
    @PrimaryKey val id: String,
    val audioAssetId: String,
    val engineId: String,
    val engineVersion: String,
    val modelId: String,
    val modelHash: String,
    val languageMode: String,
    val detectedLanguage: String?,
    val createdAtEpochMs: Long,
    val durationMs: Long
)

@Entity(
    tableName = "transcript_segments",
    primaryKeys = ["transcriptId", "segmentIndex"],
    foreignKeys = [ForeignKey(entity = TranscriptEntity::class, parentColumns = ["id"], childColumns = ["transcriptId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("transcriptId")]
)
data class TranscriptSegmentEntity(val transcriptId: String, val segmentIndex: Int, val startMs: Long, val endMs: Long, val text: String)

/** Private, resumable work state. Transcript text never leaves this database automatically. */
@Entity(tableName = "transcription_jobs", indices = [Index("assetId"), Index("status")])
data class TranscriptionJobEntity(
    @PrimaryKey val id: String,
    val assetId: String,
    val languageMode: String,
    val status: String,
    val modelId: String,
    val modelHash: String,
    val durationMs: Long,
    val processedMs: Long,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val errorCode: String?
)

@Entity(tableName = "transcription_checkpoints", primaryKeys = ["jobId", "segmentIndex"], foreignKeys = [ForeignKey(entity = TranscriptionJobEntity::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)])
data class TranscriptionCheckpointEntity(val jobId: String, val segmentIndex: Int, val startMs: Long, val endMs: Long, val text: String)

/** Benchmark records contain metrics only. They never store source, reference, or recognized text. */
@Entity(tableName = "benchmark_runs", indices = [Index("status"), Index("modelHash")])
data class BenchmarkRunEntity(
    @PrimaryKey val id: String,
    val status: String,
    val modelId: String,
    val modelHash: String,
    val engineVersion: String,
    val abi: String,
    val totalRamBytes: Long,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val batteryStartPercent: Int,
    val batteryEndPercent: Int,
    val maxThermalStatus: Int,
    val peakRssBytes: Long,
    val errorCode: String?,
    val resumeCount: Int,
    val processInstanceId: String,
    val recoveredFromProcessDeath: Boolean
)

@Entity(tableName = "benchmark_case_results", primaryKeys = ["runId", "caseId"], foreignKeys = [ForeignKey(entity = BenchmarkRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)], indices = [Index("runId"), Index("languageGroup")])
data class BenchmarkCaseResultEntity(
    val runId: String,
    val caseId: String,
    val languageGroup: String,
    val audioDurationMs: Long,
    val decodeMs: Long,
    val inferenceMs: Long,
    val elapsedMs: Long,
    val wer: Double,
    val catastrophic: Boolean,
    val errorCode: String?,
    val editDistance: Int,
    val referenceWordCount: Int,
    val detectedLanguage: String?,
    val manifestDurationMs: Long,
    val decodedSampleCount: Long,
    val decodedDurationMs: Long,
    val maximumTimestampMs: Long,
    val timestampToleranceMs: Long
)

@Entity(tableName = "deletion_journal")
data class DeletionJournalEntity(
    @PrimaryKey val assetId: String,
    val target: String,
    val localPath: String?,
    val originalStatus: String,
    val createdAtEpochMs: Long
)

data class TranscriptWithSegments(
    @Embedded val transcript: TranscriptEntity,
    @Relation(parentColumn = "id", entityColumn = "transcriptId") val segments: List<TranscriptSegmentEntity>
)

@Dao
interface VaultDao {
    @Query("SELECT * FROM media_assets ORDER BY createdAtEpochMs DESC") fun observeAssets(): Flow<List<MediaAssetEntity>>
    @Query("SELECT * FROM media_assets WHERE id = :id LIMIT 1") suspend fun getAsset(id: String): MediaAssetEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAsset(asset: MediaAssetEntity)
    @Query("DELETE FROM media_assets WHERE id = :id") suspend fun deleteAsset(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertDeletionJournal(entry: DeletionJournalEntity)
    @Query("SELECT * FROM deletion_journal ORDER BY createdAtEpochMs ASC") suspend fun pendingDeletions(): List<DeletionJournalEntity>
    @Query("DELETE FROM deletion_journal WHERE assetId = :assetId") suspend fun deleteDeletionJournal(assetId: String)

    @Transaction
    suspend fun beginDeletion(asset: MediaAssetEntity, entry: DeletionJournalEntity) {
        upsertAsset(asset.copy(status = AssetStatus.DELETING.name))
        upsertDeletionJournal(entry)
    }

    @Transaction
    suspend fun completeDeletion(assetId: String) {
        deleteAsset(assetId)
        deleteDeletionJournal(assetId)
    }

    @Transaction
    suspend fun rollbackDeletion(asset: MediaAssetEntity) {
        upsertAsset(asset)
        deleteDeletionJournal(asset.id)
    }

    @Transaction
    @Query("SELECT * FROM transcripts ORDER BY createdAtEpochMs DESC") fun observeTranscripts(): Flow<List<TranscriptWithSegments>>
    @Transaction
    @Query("SELECT * FROM transcripts WHERE id = :id LIMIT 1") suspend fun getTranscript(id: String): TranscriptWithSegments?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTranscript(transcript: TranscriptEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSegments(segments: List<TranscriptSegmentEntity>)
    @Query("DELETE FROM transcripts WHERE id = :id") suspend fun deleteTranscript(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertJob(job: TranscriptionJobEntity)
    @Query("SELECT * FROM transcription_jobs WHERE id = :id LIMIT 1") suspend fun getJob(id: String): TranscriptionJobEntity?
    @Query("SELECT * FROM transcription_jobs WHERE status IN ('RUNNING', 'PAUSED') ORDER BY updatedAtEpochMs ASC") suspend fun recoverableJobs(): List<TranscriptionJobEntity>
    @Query("SELECT * FROM transcription_jobs WHERE assetId = :assetId AND languageMode = :languageMode AND status IN ('RUNNING', 'PAUSED') ORDER BY updatedAtEpochMs DESC LIMIT 1") suspend fun recoverableJobForAsset(assetId: String, languageMode: String): TranscriptionJobEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCheckpoints(checkpoints: List<TranscriptionCheckpointEntity>)
    @Query("SELECT * FROM transcription_checkpoints WHERE jobId = :jobId ORDER BY segmentIndex ASC") suspend fun checkpoints(jobId: String): List<TranscriptionCheckpointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBenchmarkRun(run: BenchmarkRunEntity)
    @Query("SELECT * FROM benchmark_runs WHERE id = :id LIMIT 1") suspend fun getBenchmarkRun(id: String): BenchmarkRunEntity?
    @Query("SELECT * FROM benchmark_runs WHERE status IN ('RUNNING', 'INCOMPLETE') ORDER BY updatedAtEpochMs DESC LIMIT 1") suspend fun recoverableBenchmarkRun(): BenchmarkRunEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertBenchmarkCaseResult(result: BenchmarkCaseResultEntity)
    @Query("SELECT * FROM benchmark_case_results WHERE runId = :runId ORDER BY caseId ASC") suspend fun benchmarkCaseResults(runId: String): List<BenchmarkCaseResultEntity>
}

@Database(entities = [MediaAssetEntity::class, TranscriptEntity::class, TranscriptSegmentEntity::class, TranscriptionJobEntity::class, TranscriptionCheckpointEntity::class, BenchmarkRunEntity::class, BenchmarkCaseResultEntity::class, DeletionJournalEntity::class], version = 8, exportSchema = true)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun dao(): VaultDao
    companion object {
        @Volatile private var instance: VaultDatabase? = null
        fun get(context: Context): VaultDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, "stark_signal_vault.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS transcription_jobs (id TEXT NOT NULL, assetId TEXT NOT NULL, languageMode TEXT NOT NULL, status TEXT NOT NULL, modelId TEXT NOT NULL, modelHash TEXT NOT NULL, durationMs INTEGER NOT NULL, processedMs INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, errorCode TEXT, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcription_jobs_assetId ON transcription_jobs(assetId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcription_jobs_status ON transcription_jobs(status)")
                db.execSQL("CREATE TABLE IF NOT EXISTS transcription_checkpoints (jobId TEXT NOT NULL, segmentIndex INTEGER NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(jobId, segmentIndex), FOREIGN KEY(jobId) REFERENCES transcription_jobs(id) ON DELETE CASCADE)")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS benchmark_runs (id TEXT NOT NULL, status TEXT NOT NULL, modelId TEXT NOT NULL, modelHash TEXT NOT NULL, engineVersion TEXT NOT NULL, abi TEXT NOT NULL, totalRamBytes INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, batteryStartPercent INTEGER NOT NULL, batteryEndPercent INTEGER NOT NULL, maxThermalStatus INTEGER NOT NULL, peakRssBytes INTEGER NOT NULL, errorCode TEXT, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_benchmark_runs_status ON benchmark_runs(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_benchmark_runs_modelHash ON benchmark_runs(modelHash)")
                db.execSQL("CREATE TABLE IF NOT EXISTS benchmark_case_results (runId TEXT NOT NULL, caseId TEXT NOT NULL, languageGroup TEXT NOT NULL, audioDurationMs INTEGER NOT NULL, decodeMs INTEGER NOT NULL, inferenceMs INTEGER NOT NULL, elapsedMs INTEGER NOT NULL, wer REAL NOT NULL, catastrophic INTEGER NOT NULL, errorCode TEXT, PRIMARY KEY(runId, caseId), FOREIGN KEY(runId) REFERENCES benchmark_runs(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_benchmark_case_results_runId ON benchmark_case_results(runId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_benchmark_case_results_languageGroup ON benchmark_case_results(languageGroup)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS deletion_journal (assetId TEXT NOT NULL, target TEXT NOT NULL, localPath TEXT, originalStatus TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, PRIMARY KEY(assetId))")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE benchmark_case_results ADD COLUMN editDistance INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE benchmark_case_results ADD COLUMN referenceWordCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE benchmark_case_results ADD COLUMN detectedLanguage TEXT")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE benchmark_runs ADD COLUMN resumeCount INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE benchmark_case_results ADD COLUMN manifestDurationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE benchmark_case_results ADD COLUMN decodedSampleCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE benchmark_case_results ADD COLUMN decodedDurationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE benchmark_case_results ADD COLUMN maximumTimestampMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE benchmark_case_results ADD COLUMN timestampToleranceMs INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE benchmark_runs ADD COLUMN processInstanceId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE benchmark_runs ADD COLUMN recoveredFromProcessDeath INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

package com.momonong.pixelreceipt.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "drafts")
data class DraftRow(@PrimaryKey val id: String, val revision: Long, val createdAt: Long, val payload: String)

@Entity(tableName = "blobs")
data class BlobRow(@PrimaryKey val hash: String, val byteSize: Long)

@Entity(tableName = "evidence", foreignKeys = [ForeignKey(
    entity = BlobRow::class, parentColumns = ["hash"], childColumns = ["hash"],
)], indices = [Index("hash")])
data class EvidenceRow(@PrimaryKey val id: String, val hash: String, val payload: String)

@Entity(tableName = "draft_evidence", primaryKeys = ["draftId", "assetId"], foreignKeys = [
    ForeignKey(entity = DraftRow::class, parentColumns = ["id"], childColumns = ["draftId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = EvidenceRow::class, parentColumns = ["id"], childColumns = ["assetId"]),
], indices = [Index("assetId"), Index(value = ["draftId", "position"], unique = true)])
data class DraftEvidenceRow(val draftId: String, val assetId: String, val position: Int)

/** A batch is either running, completed (possibly partial), or interrupted. No durable external URI. */
@Entity(tableName = "imports")
data class ImportRow(
    @PrimaryKey val id: String,
    val draftId: String?,
    val state: String,
    val report: String,
    val createdAt: Long,
)

@Dao
interface ReceiptDao {
    @Query("SELECT * FROM drafts ORDER BY createdAt DESC, id") fun observeDrafts(): Flow<List<DraftRow>>
    @Query("SELECT * FROM drafts WHERE id = :id") fun observeDraft(id: String): Flow<DraftRow?>
    @Query("SELECT * FROM drafts WHERE id = :id") suspend fun draft(id: String): DraftRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDraft(row: DraftRow): Long
    @Query("UPDATE drafts SET payload = :payload, revision = :next WHERE id = :id AND revision = :expected")
    suspend fun cas(id: String, expected: Long, next: Long, payload: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertBlob(row: BlobRow): Long
    @Insert suspend fun insertEvidence(row: EvidenceRow)
    @Insert suspend fun insertLinks(rows: List<DraftEvidenceRow>)
    @Query("DELETE FROM draft_evidence WHERE draftId = :id") suspend fun clearLinks(id: String)
    @Query("SELECT * FROM evidence WHERE id = :id") suspend fun evidence(id: String): EvidenceRow?
    @Query("SELECT evidence.* FROM evidence INNER JOIN draft_evidence ON evidence.id = draft_evidence.assetId WHERE draftId = :id ORDER BY position")
    fun observeEvidence(id: String): Flow<List<EvidenceRow>>
    @Query("SELECT hash FROM blobs") suspend fun hashes(): List<String>
    @Query("SELECT * FROM imports WHERE id = :id") suspend fun operation(id: String): ImportRow?
    @Insert suspend fun insertOperation(row: ImportRow)
    @Update suspend fun updateOperation(row: ImportRow)
    @Query("UPDATE imports SET state = 'interrupted', report = '匯入已中斷，未提交的圖片未加入草稿；請重新選取圖片。' WHERE state = 'running'")
    suspend fun interruptOperations()
    @Query("SELECT * FROM imports ORDER BY createdAt DESC, id DESC LIMIT 1")
    fun observeLatestImport(): Flow<ImportRow?>
}

@Database(entities = [DraftRow::class, BlobRow::class, EvidenceRow::class, DraftEvidenceRow::class, ImportRow::class], version = 1, exportSchema = true)
abstract class ReceiptDatabase : RoomDatabase() {
    abstract fun receipts(): ReceiptDao

    companion object {
        fun open(context: Context): ReceiptDatabase = Room.databaseBuilder(
            context.applicationContext, ReceiptDatabase::class.java, "receipts.db",
        ).build() // First persisted schema. Future upgrades require explicit migrations; never destructive fallback.
    }
}

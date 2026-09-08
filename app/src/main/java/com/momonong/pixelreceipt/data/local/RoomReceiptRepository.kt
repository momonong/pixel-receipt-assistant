package com.momonong.pixelreceipt.data.local

import androidx.room.withTransaction
import com.momonong.pixelreceipt.domain.model.ReceiptDraft
import com.momonong.pixelreceipt.domain.port.DraftWriteResult
import com.momonong.pixelreceipt.domain.port.ReceiptRepository
import kotlinx.coroutines.flow.map

class RoomReceiptRepository(private val db: ReceiptDatabase, val codec: DraftCodec = DraftCodec()) : ReceiptRepository {
    private val dao = db.receipts()
    override fun observeDraft(id: String) = dao.observeDraft(id).map { it?.let { codec.decode(it.payload) } }
    val drafts = dao.observeDrafts().map { rows -> rows.map { codec.decode(it.payload) } }
    fun evidence(id: String) = dao.observeEvidence(id).map { rows -> rows.map { codec.decodeAsset(it.payload) } }

    override suspend fun createDraft(draft: ReceiptDraft): DraftWriteResult = db.withTransaction {
        require(draft.revision == 0L)
        if (dao.draft(draft.id) != null) return@withTransaction DraftWriteResult.Conflict
        validateReferences(draft)
        dao.insertDraft(DraftRow(draft.id, 0, System.currentTimeMillis(), codec.encode(draft)))
        writeLinks(draft)
        DraftWriteResult.Written(0)
    }

    override suspend fun compareAndSetDraft(draft: ReceiptDraft, expectedRevision: Long): DraftWriteResult = db.withTransaction {
        require(expectedRevision >= 0 && expectedRevision < Long.MAX_VALUE && draft.revision == expectedRevision + 1)
        val current = dao.draft(draft.id) ?: return@withTransaction DraftWriteResult.NotFound
        if (current.revision != expectedRevision) return@withTransaction DraftWriteResult.Conflict
        validateReferences(draft)
        check(dao.cas(draft.id, expectedRevision, draft.revision, codec.encode(draft)) == 1)
        writeLinks(draft)
        DraftWriteResult.Written(draft.revision)
    }

    private suspend fun validateReferences(draft: ReceiptDraft) {
        draft.evidenceAssetIds.forEach { require(dao.evidence(it) != null) { "Missing evidence" } }
    }

    private suspend fun writeLinks(draft: ReceiptDraft) {
        dao.clearLinks(draft.id)
        dao.insertLinks(draft.evidenceAssetIds.mapIndexed { index, id -> DraftEvidenceRow(draft.id, id, index) })
    }
}

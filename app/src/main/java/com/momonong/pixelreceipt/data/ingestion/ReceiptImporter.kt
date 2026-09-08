package com.momonong.pixelreceipt.data.ingestion

import androidx.room.withTransaction
import com.momonong.pixelreceipt.data.local.*
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.DraftWriteResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.util.UUID

data class ImportInput(val open: () -> InputStream?)

/** One application-scoped coordinator. No worker/process may independently publish or GC blobs. */
class ReceiptImporter(
    private val db: ReceiptDatabase,
    private val repository: RoomReceiptRepository,
    private val images: ImageStore,
) {
    private val mutex = Mutex()
    private var recovered = false
    private val dao = db.receipts()

    private suspend fun recoverLocked() {
        if (recovered) return
        dao.interruptOperations()
        images.cleanup(dao.hashes().toSet())
        recovered = true
    }

    suspend fun recover() = withContext(Dispatchers.IO) { mutex.withLock { recoverLocked() } }

    suspend fun createEmptyDraft(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverLocked()
            val id = UUID.randomUUID().toString()
            check(repository.createDraft(ReceiptDraft(id)) is DraftWriteResult.Written)
            id
        }
    }

    suspend fun import(
        operationId: String,
        targetId: String?,
        inputs: List<ImportInput>,
        source: EvidenceImportSource,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportRow = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverLocked()
            dao.operation(operationId)?.let { return@withLock it }
            val started = ImportRow(operationId, targetId, "running", "匯入中", System.currentTimeMillis())
            dao.insertOperation(started)
            try {
                if (inputs.size > ImageStore.MAX_IMAGES) throw ImportFailure("一次最多選取 20 張圖片，請分批匯入。")
                if (inputs.isEmpty()) {
                    val cancelled = started.copy(state = "completed", report = "已取消，未加入圖片。")
                    dao.updateOperation(cancelled)
                    return@withLock cancelled
                }
                val initial = targetId?.let { dao.draft(it)?.let { row -> repository.codec.decode(row.payload) }
                    ?: throw ImportFailure("草稿已不存在，請重新開啟。") }
                if (initial != null && initial.stage != ReceiptStage.Captured) {
                    throw ImportFailure("此草稿已進入後續處理，不能在此追加圖片。")
                }
                val known = initial?.evidenceAssetIds.orEmpty().mapNotNull { dao.evidence(it)?.hash }.toMutableSet()
                val assets = mutableListOf<EvidenceAsset>()
                val results = mutableListOf<String>()
                var duplicates = 0
                var failures = 0
                var batchBytes = 0L
                inputs.forEachIndexed { index, input ->
                    currentCoroutineContext().ensureActive()
                    progress(index, inputs.size)
                    try {
                        // Count all bytes read, including duplicates. Failed inputs are each bounded to 20 MiB.
                        var readBytes = 0L
                        val stored = try {
                            images.copy({ input.open()?.let { stream -> object : java.io.FilterInputStream(stream) {
                                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                                    super.read(buffer, offset, length).also { if (it > 0) readBytes += it }
                            } } }, ImageStore.MAX_BATCH_BYTES - batchBytes)
                        } finally { batchBytes += readBytes }
                        if (!known.add(stored.hash)) {
                            duplicates++
                            results += "第 ${index + 1} 張：草稿已有相同圖片，已跳過。"
                        } else {
                            assets += EvidenceAsset(
                                id = UUID.randomUUID().toString(), contentSha256 = stored.hash,
                                mimeType = stored.mime, byteSize = stored.bytes, widthPx = stored.width,
                                heightPx = stored.height, importSource = source,
                                importedAtEpochMillis = System.currentTimeMillis(),
                            )
                            results += "第 ${index + 1} 張：已加入。"
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failures++
                        results += "第 ${index + 1} 張：${readError(error)}"
                    }
                    progress(index + 1, inputs.size)
                }
                currentCoroutineContext().ensureActive()
                val draftId = initial?.id ?: if (assets.isNotEmpty()) UUID.randomUUID().toString() else null
                val finished = started.copy(
                    draftId = draftId, state = "completed",
                    report = "新增 ${assets.size} 張，重複 $duplicates 張，失敗 $failures 張。" +
                        (if (draftId == null) "未建立草稿。" else "") + "\n" + results.joinToString("\n"),
                )
                db.withTransaction {
                    if (assets.isNotEmpty()) {
                        // Reject races; never overwrite later facts, stage or evidence membership.
                        if (initial != null && dao.draft(initial.id)?.revision != initial.revision) {
                            throw ImportFailure("草稿已被更新，本批未加入；請重新選取圖片。")
                        }
                        assets.forEach { asset ->
                            dao.insertBlob(BlobRow(asset.contentSha256, asset.byteSize))
                            dao.insertEvidence(EvidenceRow(asset.id, asset.contentSha256, repository.codec.encodeAsset(asset)))
                        }
                        val ids = initial?.evidenceAssetIds.orEmpty() + assets.map { it.id }
                        val write = if (initial == null) repository.createDraft(ReceiptDraft(draftId!!, evidenceAssetIds = ids))
                        else repository.compareAndSetDraft(initial.copy(evidenceAssetIds = ids, revision = initial.revision + 1), initial.revision)
                        check(write is DraftWriteResult.Written)
                    }
                    dao.updateOperation(finished)
                }
                finished
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (dao.operation(operationId)?.state == "running") {
                        dao.updateOperation(started.copy(state = "interrupted", report = "匯入已取消，未提交圖片；請重新選取。"))
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                val failed = started.copy(state = "interrupted", report = readError(error) + " 本批未提交，請重新選取。")
                dao.updateOperation(failed)
                failed
            } finally {
                withContext(NonCancellable) { images.cleanup(dao.hashes().toSet()) }
            }
        }
    }
}

fun readError(error: Exception): String = when (error) {
    is ImportFailure -> error.message ?: "圖片匯入失敗。"
    is SecurityException -> "圖片讀取權限已失效，請重新選取。"
    is java.io.FileNotFoundException -> "找不到圖片，請重新選取。"
    is IllegalArgumentException -> "圖片數量或資料不符合限制。"
    else -> "無法讀取或保存圖片，請確認圖片與可用儲存空間後重試。"
}

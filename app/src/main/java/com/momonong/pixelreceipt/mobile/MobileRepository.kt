package com.momonong.pixelreceipt.mobile

import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.data.ingestion.ImportInput
import com.momonong.pixelreceipt.data.ingestion.readError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class MobileRepository(
    val store: MobileStore,
    val images: ImageStore,
    private val vault: TokenVault,
    private val api: MobileApi,
) {
    private val operation = Mutex()
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) { store.load(); vault.read() != null }
    suspend fun reviewerSession(): Boolean = withContext(Dispatchers.IO) { vault.read()?.startsWith("prm_") == true }
    suspend fun login(password: String) = withContext(Dispatchers.IO) {
        operation.withLock {
            val token = api.login(store.load().deviceId, password)
            vault.save(token)
        }
    }
    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        operation.withLock {
            store.load()
            val token = vault.read()
            try { if (token != null) api.logout(token); true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            finally { vault.clear() }
        }
    }
    suspend fun availability(): Map<String, EngineAvailability> = withContext(Dispatchers.IO) {
        operation.withLock { authenticated { api.recognition(it) } }
    }
    private suspend fun <T> authenticated(action: suspend (String) -> T): T {
        store.load() // A fresh device identity must be durable before any authenticated request.
        val token = vault.read() ?: throw MobileApiFailure(401)
        try { return action(token) } catch (error: MobileApiFailure) { if (error.statusCode == 401) vault.clear(); throw error }
    }
    suspend fun import(id: String, notes: String, inputs: List<ImportInput>): MobileBatch = withContext(Dispatchers.IO) {
        operation.withLock {
            require(inputs.size in 1..20) { "一次請分享 1 至 20 張圖片。" }
            require(notes.length <= 64_000) { "分享說明超過 64,000 字，請縮短後重試。" }
            val (existing, firstAttempt) = store.createImport(id, notes, inputs.size)
            if (existing.importComplete || !existing.editable) return@withLock existing
            // Persist the expected membership before opening any source. A resumed attempt
            // never proves the original selection complete, even when every new URI is readable.
            val review = existing.importReview ?: MobileImportReview(
                expectedCount = inputs.size.takeIf { firstAttempt },
                originalSlots = if (firstAttempt) List(inputs.size) { null } else emptyList(),
            )
            store.update(id) { it.copy(importComplete = false, importReview = review.copy(selectedHashes = emptyList(), previousHashes = existing.images.map { image -> image.hash }, requiresConfirmation = true)) }
            var bytes = 0L
            try {
                inputs.forEachIndexed { index, input ->
                    currentCoroutineContext().ensureActive()
                    val stored = images.copy(input.open, ImageStore.MAX_BATCH_BYTES - bytes)
                    bytes += stored.bytes
                    store.update(id) { old ->
                        val current = old.importReview!!
                        old.copy(
                            images = if (old.images.any { it.hash == stored.hash }) old.images else old.images + MobileImage(stored.hash, stored.mime, stored.bytes),
                            importReview = current.copy(
                                originalSlots = if (firstAttempt) current.originalSlots.mapIndexed { slot, hash -> if (slot == index) stored.hash else hash } else current.originalSlots,
                                selectedHashes = current.selectedHashes + stored.hash,
                            ),
                        )
                    }
                }
                store.update(id) { old -> old.copy(
                    importComplete = firstAttempt,
                    importReview = old.importReview!!.copy(requiresConfirmation = !firstAttempt),
                    message = if (!firstAttempt) "這次選圖已保存；請核對這批的最終照片與順序，再確認完成。"
                        else if (old.images.size < inputs.size) "相同原圖已合併，未重複送出。" else null,
                ) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { runCatching { store.update(id) { it.copy(message = "照片匯入中斷；已保存的原圖仍在，請補齊並核對照片與順序。") } } }
                throw cancelled
            } catch (error: Exception) {
                runCatching { store.update(id) { it.copy(message = readError(error) + " 已保存原圖仍在；請補齊並核對照片與順序。") } }
                throw error
            }
        }
    }
    suspend fun edit(id: String, expectedRevision: Long? = null, change: (MobileBatch) -> MobileBatch) = withContext(Dispatchers.IO) {
        store.update(id, expectedRevision, acceptIdentical = true) { old ->
            if (!old.editable) {
                if (expectedRevision != null) throw MobileFailure("這筆收件已鎖定，未保存的編輯已保留，請聯絡維護者核對。")
                old
            } else requireAssociationReview(old, change(old))
        }
    }
    suspend fun confirmImport(id: String, expectedRevision: Long): MobileBatch = withContext(Dispatchers.IO) {
        store.update(id, expectedRevision) { old ->
            check(old.editable && old.images.isNotEmpty())
            old.copy(importComplete = true, importReview = (old.importReview ?: MobileImportReview()).copy(requiresConfirmation = false), message = null)
        }
    }
    suspend fun retryStorage() = withContext(Dispatchers.IO) { store.retryStorage() }
    suspend fun sync(id: String, expectedRevision: Long? = null): MobileBatch = syncWithDuplicateApproval(id, expectedRevision, null)
    internal suspend fun syncWithDuplicateApproval(id: String, expectedRevision: Long?, approval: DuplicateShareApproval?): MobileBatch = withContext(Dispatchers.IO) {
        operation.withLock {
            try {
                // Disk commit precedes every network write. An uncertain response never changes this key/body.
                val current = store.batch(id)
                if (current.deleted && approval == null) return@withLock current
                val batch = if (current.intakeId != null && approval == null) current else store.freezeForSubmission(id, expectedRevision, approval)
                val result = authenticated { token ->
                    if (batch.intakeId != null) api.status(token, batch.intakeId)
                    else {
                        batch.images.filter { it.hash !in batch.uploadedHashes }.forEach { image ->
                            currentCoroutineContext().ensureActive()
                            val file = images.file(image.hash)
                            verifyOriginal(file, image)
                            check(api.upload(token, image, file) == image.hash)
                            store.update(id) { old -> old.copy(uploadedHashes = (old.uploadedHashes + image.hash).distinct()) }
                        }
                        api.submit(token, batch.frozen!!)
                    }
                }
                applyIntake(id, result)
            } catch (required: DuplicateShareRequired) {
                throw required // Expected local decision: no message write or revision change.
            } catch (expired: DuplicateShareDecisionExpired) {
                throw expired // A stale choice must not change the newer draft or its editor CAS.
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { runCatching { store.update(id) { it.copy(message = "同步已停止；中央可能已收件。原圖及原請求已保存，下次沿原請求同步。") } } }
                throw cancelled
            } catch (error: Exception) {
                runCatching { store.update(id) { it.copy(message = mobileError(error)) } }
                throw error
            }
        }
    }
    suspend fun openReview(id: String): MobileBatch = withContext(Dispatchers.IO) {
        operation.withLock {
            val batch = store.batch(id)
            val draftId = batch.draftId ?: throw MobileFailure("尚未取得核對內容，請更新進度。")
            val remote = authenticated { api.draft(it, draftId) }
            applyRemote(id, remote)
        }
    }
    private suspend fun applyRemote(id: String, remote: String): MobileBatch {
        val draft = draftObject(remote)
        if (store.batch(id).deleted) return store.batch(id)
        if (draft.text("status") == "deleted") return tombstone(id)
        val ledgerId = draft.text("ledger_id").takeIf { validUuid(it) }
        val entry = ledgerId?.let { authenticated { token -> api.ledgerDetail(token, it) } }
        if (entry != null) {
            val local = store.batch(id)
            store.cacheEntry(entry, confirmed = local.ledgerId == null && local.review?.confirmation != null)
        }
        return store.update(id) { old ->
            val previous = old.review
            // A GET never replaces a dirty editor, pending PUT or frozen final intent.
            val review = when {
                previous == null -> createReview(remote)
                draft.text("status") != "needs_review" -> previous.copy(serverJson = remote)
                previous.dirty || previous.saveAttempt != null || previous.confirmation != null || previous.conflict -> previous
                draft["revision"].asLong != previous.draft["revision"].asLong -> createReview(remote)
                else -> previous.copy(serverJson = remote)
            }
            val ledgerStatus = if (old.ledgerStatus == "voided") "voided" else entry?.let { ledgerObject(it).text("status") } ?: old.ledgerStatus
            old.copy(review = review, draftStatus = if (ledgerStatus == "voided") "voided" else draft.text("status"), ledgerId = old.ledgerId ?: ledgerId,
                ledgerStatus = ledgerStatus, message = null)
        }
    }
    suspend fun persistReview(id: String, edited: MobileReview, expectedEditRevision: Long): MobileBatch = withContext(Dispatchers.IO) {
        store.update(id) { old ->
            val current = old.review ?: throw MobileFailure("核對內容尚未載入。")
            if (old.deleted || store.state.value?.pendingMutation(id) != null || current.locked || old.ledgerId != null || old.draftStatus in setOf("confirmed", "voided", "deleted")) throw MobileFailure("正在確認或已入帳，編輯內容已保留。")
            if (current.editRevision != expectedEditRevision && !(current.editRevision == edited.editRevision && current.editorJson == edited.editorJson))
                throw MobileFailure("本機核對內容已更新；目前編輯已保留。")
            old.copy(review = current.copy(editorJson = edited.editorJson, editRevision = edited.editRevision, error = null))
        }
    }
    private suspend fun reconcileSave(id: String, review: MobileReview): MobileReview {
        val pending = review.saveAttempt ?: return review
        val remote = authenticated { api.draft(it, review.draft.text("id")) }
        val draft = draftObject(remote)
        if (draft.text("status") != "needs_review") { applyRemote(id, remote); throw MobileFailure("這筆帳目已在其他位置完成或作廢；請返回查看正式狀態。") }
        return when {
            draft["revision"].asLong == pending.revision -> review // Safe to retry the same revision/body after this GET.
            draft["revision"].asLong == pending.revision + 1 && matchesReview(draft, pending.body) -> {
                store.update(id) { old -> old.copy(review = old.review!!.copy(serverJson = remote, savedEditRevision = pending.editRevision, saveAttempt = null, error = null)) }.review!!
            }
            else -> {
                store.update(id) { old -> old.copy(review = old.review!!.copy(conflict = true, error = "中央內容已變更；你的編輯已保留，請重新比對版本。")) }
                throw MobileFailure("中央內容已變更；你的編輯已保留，請重新比對版本。")
            }
        }
    }
    suspend fun saveReview(id: String): MobileBatch = withContext(Dispatchers.IO) {
        operation.withLock {
            val batch = store.batch(id)
            ensureNoReceiptAction(id)
            var review = batch.review ?: throw MobileFailure("核對內容尚未載入。")
            if (review.locked || review.conflict || batch.ledgerId != null || batch.draftStatus in setOf("confirmed", "voided")) return@withLock store.batch(id)
            try {
                review = reconcileSave(id, review)
                if (!review.dirty) return@withLock store.batch(id)
                val prepared = prepareReview(review)
                if (prepared.body == null) return@withLock store.batch(id) // Partial/invalid strings remain durable locally.
                val pending = review.saveAttempt ?: FrozenReviewSave(prepared.body, review.draft["revision"].asLong, review.editRevision)
                store.update(id) { old -> old.copy(review = old.review!!.copy(saveAttempt = pending, error = null)) }
                val remote = authenticated { api.review(it, review.draft.text("id"), pending.body) }
                check(draftObject(remote)["revision"].asLong == pending.revision + 1 && matchesReview(draftObject(remote), pending.body))
                store.update(id) { old -> old.copy(review = old.review!!.copy(serverJson = remote, savedEditRevision = pending.editRevision, saveAttempt = null, error = null)) }
            } catch (error: Exception) {
                withContext(NonCancellable) { runCatching { store.update(id) { old -> old.copy(review = old.review!!.copy(error = "金額尚未更新：${mobileError(error)}")) } } }
                throw error
            }
        }
    }
    suspend fun confirmReview(id: String): MobileBatch = withContext(Dispatchers.IO) {
        operation.withLock {
            ensureNoReceiptAction(id)
            var review = store.batch(id).review ?: throw MobileFailure("核對內容尚未載入。")
            if (review.conflict) throw MobileFailure("中央版本有變更，請先重新比對。")
            if (store.batch(id).ledgerId != null || review.draft.text("status") != "needs_review") return@withLock store.batch(id)
            try {
                review = reconcileSave(id, review)
                val existing = review.confirmation
                if (existing == null) {
                    if (review.dirty) throw MobileFailure("正在更新金額，請稍候再確認。")
                    if (review.editor.text("assembly_status") == "incomplete") throw MobileFailure("請先補齊缺少的照片或品項。")
                    if (reviewBlockers(review).any { it.text("code") != "receipt_completeness_unconfirmed" }) throw MobileFailure("請先修正標示的欄位與差額。")
                }
                val revision = review.draft["revision"].asLong
                val completeBody = existing?.reviewBody ?: prepareReview(review, complete = true).body ?: throw MobileFailure("請先補齊標示的欄位。")
                val attempt = existing ?: FrozenConfirmation(java.util.UUID.randomUUID().toString(),
                    com.google.gson.JsonObject().apply { addProperty("expected_revision", revision + 1) }.toString(), completeBody, revision)
                // Freeze BOTH requests and the key before the first final mutation, including a lost PUT response.
                store.update(id) { old -> old.copy(review = old.review!!.copy(confirmation = attempt, error = null)) }
                val remote = authenticated { api.draft(it, review.draft.text("id")) }
                val draft = draftObject(remote)
                if (draft.text("status") != "needs_review") return@withLock applyRemote(id, remote)
                val accepted = draft["revision"].asLong == attempt.baseRevision + 1 && matchesReview(draft, attempt.reviewBody)
                val finalDraft = if (accepted) remote else {
                    if (draft["revision"].asLong != attempt.baseRevision) {
                        val current = store.batch(id).review!!
                        if (!current.confirmationDispatched) store.update(id) { old -> old.copy(review = old.review!!.copy(
                            confirmation = null, conflict = true, comparisonJson = remote, error = "中央版本有變更；尚未送出入帳，請選擇要保留的版本。")) }
                        throw MobileFailure("中央版本有變更；請重新比對，尚未入帳。")
                    }
                    store.update(id) { old -> old.copy(review = old.review!!.copy(finalReviewDispatched = true)) }
                    authenticated { api.review(it, draft.text("id"), attempt.reviewBody) }
                }
                val finalValue = draftObject(finalDraft)
                check(finalValue["revision"].asLong == attempt.baseRevision + 1 && matchesReview(finalValue, attempt.reviewBody))
                if (!finalValue.getAsJsonObject("calculation")["ready"].asBoolean) throw MobileFailure("中央檢查仍有待修正欄位，尚未入帳。")
                store.update(id) { old -> old.copy(review = old.review!!.copy(serverJson = finalDraft)) }
                store.update(id) { old -> old.copy(review = old.review!!.copy(confirmationDispatched = true)) }
                val entry = authenticated { api.confirm(it, draft.text("id"), attempt) }
                val ledger = ledgerObject(entry)
                store.cacheEntry(entry, confirmed = true)
                store.update(id) { old -> old.copy(draftStatus = if (ledger.text("status") == "voided") "voided" else "confirmed",
                    ledgerId = ledger.text("id"), ledgerStatus = ledger.text("status"), message = null,
                    review = old.review!!.copy(serverJson = ledger["snapshot"].toString(), error = null)) }
            } catch (error: Exception) {
                withContext(NonCancellable) { runCatching { store.update(id) { old -> old.copy(review = old.review!!.copy(error = if (old.review.conflict) "中央版本有變更；手機編輯已保留，請比對版本。" else "尚未確認入帳結果；原確認內容已保存。請按查詢結果。")) } } }
                throw error
            }
        }
    }
    suspend fun compareReview(id: String): MobileBatch = withContext(Dispatchers.IO) {
        operation.withLock {
            val current = store.batch(id).review ?: throw MobileFailure("核對內容尚未載入。")
            val remote = authenticated { api.draft(it, current.draft.text("id")) }
            if (draftObject(remote).text("status") != "needs_review") return@withLock applyRemote(id, remote)
            store.update(id) { old -> old.copy(review = old.review!!.copy(comparisonJson = remote)) }
        }
    }
    /** Called only after the user explicitly chooses between the two displayed versions. */
    suspend fun resolveReview(id: String, useLocal: Boolean): MobileBatch = withContext(Dispatchers.IO) {
        operation.withLock {
            val current = store.batch(id).review ?: throw MobileFailure("核對內容尚未載入。")
            if (current.confirmation != null) throw MobileFailure("確認結果尚待查詢，不能更改本次內容。")
            val remote = current.comparisonJson ?: throw MobileFailure("請先讀取中央版本來比對。")
            val next = if (useLocal) current.copy(serverJson = remote, savedEditRevision = -1, saveAttempt = null, conflict = false, error = null, comparisonJson = null)
                else createReview(remote).copy(retainedEditorJson = current.editorJson)
            store.update(id) { old -> old.copy(review = next) }
        }
    }
    suspend fun ledger(offset: Int = 0, detailId: String? = null): Unit = withContext(Dispatchers.IO) {
        operation.withLock {
            if (offset == 0) refreshAccountingLocked(detailId)
            else try { val response = authenticated { api.ledger(it, offset) }; store.cacheLedger(response, append = true) }
            catch (error: Exception) { store.accountingRefresh(true, "帳本尚未同步；顯示上次紀錄。${mobileError(error)}"); throw error }
        }
    }
    suspend fun ledgerDetail(id: String): String = withContext(Dispatchers.IO) {
        operation.withLock {
            try { authenticated { api.ledgerDetail(it, id) }.also { store.cacheEntry(it) } }
            catch (error: Exception) { store.accountingRefresh(true, "帳目明細尚未同步；顯示上次紀錄。${mobileError(error)}"); throw error }
        }
    }
    private fun ensureNoReceiptAction(id: String) {
        if (store.batch(id).deleted) throw MobileFailure("這筆收據已刪除。")
        if (store.load().pendingMutation(id) != null) throw MobileFailure("刪除結果尚待確認；請先查詢原刪除結果，不能編輯或入帳。")
    }
    private fun tombstone(id: String): MobileBatch = store.update(id) { old ->
        if (old.deleted) old else old.copy(deletedAt = System.currentTimeMillis(), status = "deleted", draftStatus = old.draftId?.let { "deleted" }, message = "收據已刪除；原圖與歷史資料保留。")
    }
    private fun applyIntake(id: String, result: ServerIntake): MobileBatch = store.update(id) { old ->
        if (old.deleted) old else old.copy(intakeId = result.id, status = result.status, draftId = result.draftId, serverJson = result.rawJson,
            message = null, draftStatus = if (old.draftStatus == "voided" || old.ledgerStatus == "voided") "voided" else if (old.ledgerId != null && result.ledgerId == null) old.draftStatus else result.draftStatus,
            ledgerId = old.ledgerId ?: result.ledgerId, ledgerStatus = if (old.ledgerStatus == "voided") "voided" else result.ledgerStatus ?: old.ledgerStatus,
            deletedAt = if (result.status == "deleted" || result.draftStatus == "deleted") System.currentTimeMillis() else old.deletedAt)
    }
    /** Called only by an explicit, confirmed user action. A pending intent always wins over new text. */
    suspend fun deleteBatch(id: String, reason: String = "未註明"): Unit = withContext(Dispatchers.IO) {
        operation.withLock {
            val batch = store.batch(id)
            val pending = store.load().pendingMutation(id)
            if (batch.deleted) {
                if (pending != null) store.finishMutation(pending.key, "completed")
                refreshAfterReceiptAction()
                return@withLock
            }
            if (pending == null) {
                if (!batch.importComplete || batch.importReview?.requiresConfirmation == true) throw MobileFailure("照片匯入或核對尚未完成；請先完成照片核對，再刪除收據。")
                if (batch.review?.saveAttempt != null || batch.review?.confirmation != null) throw MobileFailure("保存或入帳結果尚待確認；請先查詢原結果，再刪除收據。")
                if (batch.ledgerId != null || batch.draftStatus in setOf("confirmed", "voided")) throw MobileFailure("已入帳收據請使用作廢帳目，保留帳本歷史。")
                if (batch.intakeId == null && batch.draftId == null) {
                    if (batch.frozen != null) throw MobileFailure("原收件結果尚未確認；請先沿原收件同步取得中央 ID，再刪除。")
                    tombstone(id)
                    return@withLock
                }
            }
            val attempt = pending ?: run {
                val intake = batch.intakeId?.let { authenticated { token -> api.status(token, it) } }
                if (intake != null) applyIntake(id, intake)
                if (intake?.status == "deleted") { refreshAfterReceiptAction(); return@withLock }
                if (intake?.ledgerId != null) throw MobileFailure("這筆已在中央入帳；請更新後使用作廢帳目。")
                val draftId = intake?.draftId ?: batch.draftId
                val remote = draftId?.let { authenticated { token -> api.draft(token, it) } }
                val draft = remote?.let(::draftObject)
                if (draft?.text("status") == "deleted") { tombstone(id); refreshAfterReceiptAction(); return@withLock }
                if (draft != null && draft.text("status") != "needs_review") { applyRemote(id, remote); throw MobileFailure("這筆已在中央完成；請更新後使用作廢帳目。") }
                val knownRevision = batch.review?.draft?.get("revision")?.asLong
                if (draft != null && knownRevision != null && draft["revision"].asLong != knownRevision) {
                    applyRemote(id, remote)
                    throw MobileFailure("中央版本已變更；請先檢查最新內容，再確認刪除。")
                }
                val body = com.google.gson.JsonObject().apply {
                    val field = if (batch.intakeId != null) "expected_draft_revision" else "expected_revision"
                    add(field, draft?.get("revision") ?: com.google.gson.JsonNull.INSTANCE)
                    addProperty("reason", reason.trim().ifBlank { "未註明" })
                }.toString()
                store.recordMutation(ReceiptMutation(if (batch.intakeId != null) "intake_delete" else "draft_delete", batch.intakeId ?: draftId!!, id, java.util.UUID.randomUUID().toString(), body))
            }
            executeReceiptAction(attempt)
        }
    }
    suspend fun voidEntry(id: String, reason: String = "未註明"): Unit = withContext(Dispatchers.IO) {
        operation.withLock {
            val state = store.load()
            val pending = state.pendingLedgerMutation(id)
            val linked = state.batches.firstOrNull { it.ledgerId == id }
            if (linked != null && state.pendingMutation(linked.id)?.kind in setOf("intake_delete", "draft_delete")) throw MobileFailure("原刪除結果尚待確認；請先查詢原刪除結果，再作廢帳目。")
            val attempt = pending ?: run {
                val remote = authenticated { api.ledgerDetail(it, id) }; store.cacheEntry(remote)
                val entry = ledgerObject(remote)
                if (entry.text("status") == "voided") { refreshAfterReceiptAction(id); return@withLock }
                val known = state.ledgerDetailsJson?.let(::jsonObject)?.get(id)?.asJsonObject
                    ?: state.ledgerJson?.let(::jsonObject)?.array("entries")?.firstOrNull { it.asJsonObject.text("id") == id }?.asJsonObject
                if (known != null && known["revision"].asLong != entry["revision"].asLong) throw MobileFailure("中央帳目版本已變更；請檢查最新內容，再確認作廢。")
                val body = com.google.gson.JsonObject().apply { add("expected_revision", entry["revision"]); addProperty("reason", reason.trim().ifBlank { "未註明" }) }.toString()
                store.recordMutation(ReceiptMutation("ledger_void", id, state.batches.firstOrNull { it.ledgerId == id || it.draftId == entry.text("draft_id") }?.id,
                    java.util.UUID.randomUUID().toString(), body))
            }
            executeReceiptAction(attempt)
        }
    }
    private suspend fun executeReceiptAction(attempt: ReceiptMutation) {
        try {
            // Query only the original target before a retry; never discover it by new ingestion.
            when (attempt.kind) {
                "intake_delete" -> {
                    val current = authenticated { api.status(it, attempt.targetId) }
                    val result = if (current.status == "deleted") current else authenticated { api.deleteIntake(it, attempt.targetId, attempt) }
                    check(result.status == "deleted"); applyIntake(attempt.batchId!!, result); tombstone(attempt.batchId)
                }
                "draft_delete" -> {
                    val current = authenticated { api.draft(it, attempt.targetId) }
                    val result = if (draftObject(current).text("status") == "deleted") current else authenticated { api.deleteDraft(it, attempt.targetId, attempt) }
                    check(draftObject(result).text("status") == "deleted"); tombstone(attempt.batchId!!)
                }
                "ledger_void" -> {
                    val current = authenticated { api.ledgerDetail(it, attempt.targetId) }
                    val result = if (ledgerObject(current).text("status") == "voided") current else authenticated { api.voidLedger(it, attempt.targetId, attempt) }
                    check(ledgerObject(result).text("status") == "voided"); store.cacheEntry(result)
                }
            }
            store.finishMutation(attempt.key, "completed")
            refreshAfterReceiptAction(attempt.targetId.takeIf { attempt.kind == "ledger_void" })
        } catch (error: MobileApiFailure) {
            if (error.statusCode in setOf(403, 409, 422)) {
                store.finishMutation(attempt.key, "rejected", if (error.statusCode == 409) "中央版本或狀態已改變，本次未完成；已保留原紀錄，請檢查最新狀態。" else mobileError(error))
                refreshAfterReceiptAction(attempt.targetId.takeIf { attempt.kind == "ledger_void" })
            }
            throw error
        }
    }
    private suspend fun refreshAfterReceiptAction(detailId: String? = null) {
        try { refreshAccountingLocked(detailId) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            store.accountingRefresh(true, "操作已確認，帳本或統計尚未同步；顯示上次紀錄，請更新帳本。")
        }
    }
    /** Rebuild loaded pages, refresh off-page detail and use the authoritative overview. */
    private suspend fun refreshAccountingLocked(detailId: String? = null) {
        store.accountingRefresh(true)
        try {
            val initial = store.load()
            val targetCount = initial.ledgerNextOffset ?: 20
            var offset = 0
            do {
                val response = authenticated { api.ledger(it, offset) }
                val page = jsonObject(response)
                store.cacheLedger(response, append = offset > 0)
                val count = page.array("entries").size()
                offset += count
                if (count == 0 || offset >= page["total_count"].asInt) break
            } while (offset < targetCount)
            val loadedIds = jsonObject(store.load().ledgerJson!!).array("entries").map { it.asJsonObject.text("id") }.toSet()
            if (detailId != null && detailId !in loadedIds) store.cacheEntry(authenticated { api.ledgerDetail(it, detailId) })
            store.retainLedgerDetails(loadedIds + listOfNotNull(detailId))
            store.cacheOverview(authenticated { api.overview(it) })
            store.accountingRefresh(false)
        } catch (error: Exception) {
            withContext(NonCancellable) { runCatching { store.accountingRefresh(true, "帳本與統計尚未同步；顯示上次紀錄，請稍後更新。") } }
            throw error
        }
    }
    private fun verifyOriginal(file: File, image: MobileImage) {
        if (!file.isFile || file.length() != image.bytes) throw MobileFailure("原圖無法讀取；請保留 App 資料並聯絡維護者。")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        if (digest.digest().joinToString("") { "%02x".format(it) } != image.hash) throw MobileFailure("原圖完整性檢查失敗；本次未送出。")
    }
}

internal fun mobileError(error: Exception): String = when (error) {
    is MobileApiFailure -> when (error.statusCode) {
        401 -> "登入已失效，請重新登入；照片、說明與原請求均已保留。"
        403 -> "此裝置需要重新登入，才能在 App 內核對與入帳。"
        409 -> "中央版本或狀態已改變；資料已保留，請更新並檢查目前內容後再操作。"
        413 -> "圖片或說明超過中央限制；資料仍在本機。"
        415 -> "中央不接受此圖片格式。"
        422 -> "中央無法接受這批資料；原請求已保留，請聯絡維護者核對。"
        429 -> "登入嘗試過多，請稍後再試。"
        in 300..399 -> "中央網址發生轉址，已停止連線；資料仍在本機。"
        else -> "中央服務暫時無法完成；資料已保留，可稍後手動同步。"
    }
    is MobileFailure -> error.userMessage
    is IllegalArgumentException -> "資料不符合限制；請檢查照片數量、說明長度及收據角色。"
    else -> "連線或保存未完成；請確認網路與儲存空間。原資料仍在，請手動同步。"
}

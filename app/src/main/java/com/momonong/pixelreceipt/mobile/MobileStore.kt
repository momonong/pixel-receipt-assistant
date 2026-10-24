package com.momonong.pixelreceipt.mobile

import android.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Independent of the legacy ledger. Every mutation reloads under a process + OS lock. */
class MobileStore(private val root: File, private val beforeWrite: () -> Unit = {}) {
    private val gson = Gson()
    private val stateFile = AtomicFile(File(root, "state.json"))
    private val lock = locks.computeIfAbsent(root.canonicalPath) { Any() }
    private val mutable = MutableStateFlow<MobileState?>(null)
    val state = mutable.asStateFlow()
    private val writableMutable = MutableStateFlow(true)
    val writable = writableMutable.asStateFlow()
    init { check(root.isDirectory || root.mkdirs()) }

    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        RandomAccessFile(File(root, "state.lock"), "rw").use { file -> file.channel.lock().use { action() } }
    }
    private fun read(): MobileState {
        if (!stateFile.baseFile.exists() && !File(root, "state.json.bak").exists()) return MobileState(UUID.randomUUID().toString())
        try {
            val bytes = stateFile.openRead().use { stream ->
                readBounded(stream, MAX_STATE_BYTES)
            }
            val state = gson.fromJson(bytes.toString(Charsets.UTF_8), MobileState::class.java)
            check(validUuid(state.deviceId))
            check(state.batches.map { it.id }.distinct().size == state.batches.size)
            state.batches.forEach { batch ->
                check(validUuid(batch.id) && batch.revision >= 0 && batch.images.size <= 20)
                batch.images.forEach { check(it.hash.matches(Regex("[0-9a-f]{64}"))) }
                check(batch.images.map { it.hash }.distinct().size == batch.images.size)
                batch.importReview?.let { review ->
                    check(review.expectedCount == null || review.expectedCount in 1..20)
                    check(review.originalSlots.size <= 20 && review.selectedHashes.size <= 20 && review.previousHashes.size <= 20)
                    check((review.originalSlots.filterNotNull() + review.selectedHashes + review.previousHashes).all { it.matches(Regex("[0-9a-f]{64}")) })
                }
                batch.frozen?.let { check(sha256(it.body.toByteArray(Charsets.UTF_8)) == it.bodySha256) }
                batch.review?.let(::validateReview)
            }
            state.mutations.orEmpty().forEach(::validateMutation)
            state.overviewJson?.let(::overviewObject)
            return state
        } catch (_: Exception) { throw MobileFailure("本機收件資料無法讀取；原檔已保留，請勿清除 App 資料。") }
    }
    private fun write(state: MobileState) {
        val bytes = gson.toJson(state).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STATE_BYTES) throw MobileFailure("本機收件索引已達容量上限；原資料保留，請聯絡維護者。")
        try {
            beforeWrite()
            val output = stateFile.startWrite()
            try {
                output.write(bytes)
                stateFile.finishWrite(output)
            } catch (error: Exception) { stateFile.failWrite(output); throw error }
        } catch (error: Exception) { writableMutable.value = false; throw error }
        writableMutable.value = true
        mutable.value = state
    }
    fun load(): MobileState = locked {
        val exists = stateFile.baseFile.exists() || File(root, "state.json.bak").exists()
        read().also { if (exists) mutable.value = it else write(it) }
    }
    /** Explicit recovery only; normal reads never probe storage with a write. */
    fun retryStorage(): MobileState = locked { read().also(::write) }
    fun batch(id: String): MobileBatch = locked { read().batches.firstOrNull { it.id == id } ?: throw MobileFailure("找不到這筆本機收件。") }
    fun create(id: String, notes: String): MobileBatch = locked { createLocked(id, notes, null).first }
    fun createImport(id: String, notes: String, expectedCount: Int): Pair<MobileBatch, Boolean> = locked {
        createLocked(id, notes, MobileImportReview(expectedCount, List(expectedCount) { null }))
    }
    private fun createLocked(id: String, notes: String, review: MobileImportReview?): Pair<MobileBatch, Boolean> {
        require(validUuid(id))
        val state = read()
        state.batches.firstOrNull { it.id == id }?.let { return it to false }
        if (state.batches.size >= 1000) throw MobileFailure("本機已保存 1,000 筆收件，請先備存資料後聯絡維護者。")
        val batch = MobileBatch(id = id, userNotes = notes, importReview = review)
        write(state.copy(batches = listOf(batch) + state.batches))
        return batch to true
    }
    /** A stale editor cannot overwrite a frozen submission or a newer server response. */
    fun update(id: String, expectedRevision: Long? = null, acceptIdentical: Boolean = false, transform: (MobileBatch) -> MobileBatch): MobileBatch = locked {
        val state = read()
        val old = state.batches.firstOrNull { it.id == id } ?: throw MobileFailure("找不到這筆本機收件。")
        if (expectedRevision != null && old.revision != expectedRevision && acceptIdentical && transform(old).copy(revision = old.revision) == old) {
            mutable.value = state
            return@locked old // Process death after disk commit but before SavedState acknowledgement.
        }
        if (expectedRevision != null && old.revision != expectedRevision) throw MobileFailure("資料已更新，請重新開啟這筆收件。")
        val next = transform(old).copy(revision = old.revision + 1)
        check(next.id == old.id && next.images.map { it.hash }.distinct().size == next.images.size)
        if (next.images.size > 20 || next.images.sumOf { it.bytes } > 100L * 1024 * 1024) throw MobileFailure("每筆最多 20 張圖片、合計 100 MiB；已保存原圖保留，請分批分享。")
        check(next.uploadedHashes.distinct().size == next.uploadedHashes.size && next.uploadedHashes.all { hash -> next.images.any { it.hash == hash } })
        check(next.uploadedHashes.containsAll(old.uploadedHashes))
        if (old.frozen != null) check(next.frozen == old.frozen && next.images == old.images && next.userNotes == old.userNotes && next.purpose == old.purpose && next.engine == old.engine)
        if (old.intakeId != null) check(next.intakeId == old.intakeId)
        old.review?.confirmation?.let { attempt ->
            val releasedBeforeConfirm = next.review?.confirmation == null && !old.review.confirmationDispatched && next.review?.conflict == true &&
                next.review.comparisonJson?.let { draftObject(it).text("status") == "needs_review" && draftObject(it).text("ledger_id").isBlank() } == true
            check(next.review?.confirmation == attempt || releasedBeforeConfirm)
        }
        if (state.pendingMutation(id) != null) check(next.images == old.images && next.userNotes == old.userNotes && next.review?.editorJson == old.review?.editorJson) { "Pending receipt action cannot edit original content" }
        if (old.deleted) check(next.deleted && next.deletedAt == old.deletedAt && next.status == old.status && next.draftStatus == old.draftStatus)
        next.review?.let(::validateReview)
        val mutations = if (next.deleted) state.mutations?.map { attempt ->
            val matches = attempt.batchId == id && ((attempt.kind == "intake_delete" && attempt.targetId == next.intakeId) || (attempt.kind == "draft_delete" && attempt.targetId == next.draftId))
            if (matches && attempt.phase == "pending") attempt.copy(phase = "completed", error = null) else attempt
        } else state.mutations
        val ledgerChanged = next.ledgerStatus != old.ledgerStatus
        write(state.copy(batches = state.batches.map { if (it.id == id) next else it }, mutations = mutations,
            accountingStale = if (ledgerChanged) true else state.accountingStale,
            accountingError = if (ledgerChanged) "中央帳目狀態已更新，帳本與統計尚未同步。" else state.accountingError))
        next
    }
    fun cacheLedger(json: String, append: Boolean = false): MobileState = locked {
        val state = read(); val response = jsonObject(json)
        val offset = response["offset"]?.let { strictLong(it, 0, 100_000).toInt() } ?: if (append) state.ledgerNextOffset ?: 0 else 0
        val details = state.ledgerDetailsJson?.let(::jsonObject) ?: com.google.gson.JsonObject()
        val page = response.array("entries").map { incoming ->
            val entry = ledgerObject(incoming.toString()); val value = latestLedger(details, entry)
            details.add(value.text("id"), value); value
        }
        response.add("entries", com.google.gson.JsonArray().apply { page.forEach(::add) })
        if (append && state.ledgerJson != null) {
            val entries = linkedMapOf<String, com.google.gson.JsonElement>()
            jsonObject(state.ledgerJson).array("entries").forEach { entries[it.asJsonObject.text("id")] = it }
            page.forEach { entries[it.asJsonObject.text("id")] = it } // New status wins for overlapping IDs.
            response.add("entries", com.google.gson.JsonArray().apply { entries.values.forEach(::add) })
        }
        state.copy(ledgerJson = response.toString(), ledgerUpdatedAt = System.currentTimeMillis(), ledgerDetailsJson = details.toString(), ledgerNextOffset = offset + page.size, batches = projectLedger(state.batches, page.map { it.asJsonObject }), mutations = completeLedgerMutations(state.mutations, page.map { it.asJsonObject })).also(::write)
    }
    /** Detail lookup has no effect on the server paging cursor or page membership. */
    fun cacheEntry(json: String, confirmed: Boolean = false): MobileState = locked {
        val state = read()
        val details = state.ledgerDetailsJson?.let(::jsonObject) ?: com.google.gson.JsonObject()
        val entry = latestLedger(details, ledgerObject(json))
        details.add(entry.text("id"), entry)
        val response = state.ledgerJson?.let(::jsonObject) ?: com.google.gson.JsonObject().apply { add("entries", com.google.gson.JsonArray()); addProperty("total_count", 0) }
        val previous = response.array("entries").toList()
        response.add("entries", com.google.gson.JsonArray().apply {
            if (confirmed) add(entry)
            previous.forEach { value -> if (value.asJsonObject.text("id") != entry.text("id")) add(value) else if (!confirmed) add(entry) }
        })
        response.addProperty("total_count", maxOf(response["total_count"].asInt, response.array("entries").size()))
        state.copy(ledgerJson = response.toString(), ledgerUpdatedAt = System.currentTimeMillis(), ledgerDetailsJson = details.toString(), batches = projectLedger(state.batches, listOf(entry)), mutations = completeLedgerMutations(state.mutations, listOf(entry)), accountingStale = if (confirmed || state.ledgerDetailsJson?.let(::jsonObject)?.get(entry.text("id"))?.asJsonObject?.text("status") != entry.text("status")) true else state.accountingStale).also(::write)
    }
    /** Off-page entries are invalidated; opening one fetches its current formal state. */
    fun retainLedgerDetails(ids: Set<String>) = locked {
        val state = read(); val details = state.ledgerDetailsJson?.let(::jsonObject) ?: com.google.gson.JsonObject()
        details.keySet().toList().filter { it !in ids }.forEach { details.remove(it) }
        write(state.copy(ledgerDetailsJson = details.toString()))
    }
    fun recordMutation(attempt: ReceiptMutation): ReceiptMutation = locked {
        validateMutation(attempt)
        val state = read()
        val existing = state.mutations.orEmpty().firstOrNull { it.key == attempt.key }
        if (existing != null) { check(existing.kind == attempt.kind && existing.targetId == attempt.targetId && existing.body == attempt.body); return@locked existing }
        check(state.mutations.orEmpty().none { it.kind == attempt.kind && it.targetId == attempt.targetId && it.phase == "pending" })
        write(state.copy(mutations = state.mutations.orEmpty() + attempt, accountingStale = true, accountingError = null))
        attempt
    }
    fun finishMutation(key: String, phase: String, error: String? = null) = locked {
        val state = read(); check(phase in setOf("completed", "rejected"))
        check(state.mutations.orEmpty().any { it.key == key })
        write(state.copy(mutations = state.mutations.orEmpty().map { if (it.key == key) it.copy(phase = phase, error = error) else it }))
    }
    fun accountingRefresh(stale: Boolean, error: String? = null) = locked {
        val state = read(); write(state.copy(accountingStale = stale, accountingError = error))
    }
    fun cacheOverview(json: String) = locked {
        overviewObject(json); val state = read(); write(state.copy(overviewJson = json))
    }
    private fun latestLedger(details: com.google.gson.JsonObject, incoming: com.google.gson.JsonObject): com.google.gson.JsonObject {
        val previous = details[incoming.text("id")]?.takeIf { it.isJsonObject }?.asJsonObject ?: return incoming
        return if (previous.text("status") == "voided" || previous["revision"].asLong > incoming["revision"].asLong) previous else incoming
    }
    private fun completeLedgerMutations(attempts: List<ReceiptMutation>?, entries: List<com.google.gson.JsonObject>): List<ReceiptMutation>? = attempts?.map { attempt ->
        if (attempt.kind == "ledger_void" && attempt.phase == "pending" && entries.any { it.text("id") == attempt.targetId && it.text("status") == "voided" }) attempt.copy(phase = "completed", error = null) else attempt
    }
    private fun projectLedger(batches: List<MobileBatch>, entries: List<com.google.gson.JsonObject>): List<MobileBatch> = batches.map { batch ->
        val entry = entries.firstOrNull { it.text("id") == batch.ledgerId || it.text("draft_id") == batch.draftId }
        if (entry == null || batch.deleted || (batch.ledgerStatus == "voided" && entry.text("status") != "voided")) batch else batch.copy(ledgerId = entry.text("id"), ledgerStatus = entry.text("status"), draftStatus = if (entry.text("status") == "voided") "voided" else "confirmed")
    }
    fun freeze(id: String, expectedRevision: Long? = null): MobileBatch = update(id, expectedRevision) { old -> old.copy(frozen = freezeIntake(old), message = null) }
    companion object { private const val MAX_STATE_BYTES = 16 * 1024 * 1024; private val locks = ConcurrentHashMap<String, Any>() }
}

internal fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        check(output.size() + count <= limit) { "資料超過限制。" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

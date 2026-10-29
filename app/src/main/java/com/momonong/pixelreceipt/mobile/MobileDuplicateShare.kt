package com.momonong.pixelreceipt.mobile

/** Local byte identity is an advisory hint; the same image may represent a separate purchase. */
// Ledger projections can update status without changing batch revision: bind the actual values too.
internal data class DuplicateShareApproval(val incoming: MobileBatch, val candidates: List<MobileBatch>)
// Reference identity also identifies one displayed decision, including an identical later reopen.
internal class DuplicateShareReminder(val incoming: MobileBatch, val candidates: List<MobileBatch>) {
    val approval: DuplicateShareApproval get() = DuplicateShareApproval(incoming, candidates)
}
internal class DuplicateShareRequired(val reminder: DuplicateShareReminder) : Exception()
internal class DuplicateShareDecisionExpired : Exception()

internal fun duplicateShareReminder(incoming: MobileBatch, batches: List<MobileBatch>): DuplicateShareReminder? {
    if (!incoming.editable || incoming.purpose != "purchase" || !incoming.importComplete || incoming.importReview?.requiresConfirmation == true) return null
    val receipt = incoming.images.filter { it.role == "receipt" }.map { it.hash }.toSet()
    if (receipt.isEmpty()) return null
    val matches = batches.filter { old ->
        old.id != incoming.id && old.purpose == "purchase" && old.importComplete && old.importReview?.requiresConfirmation != true &&
            old.images.filter { it.role == "receipt" }.map { it.hash }.toSet() == receipt
    }.sortedWith(compareBy<MobileBatch> { old ->
        when {
            old.deleted || old.ledgerId != null || old.draftStatus in setOf("confirmed", "voided") -> 3
            old.status == "unknown" || (old.frozen != null && old.intakeId == null) -> 0
            old.frozen != null -> 1
            else -> 2
        }
    }.thenByDescending { it.createdAt }.thenBy { it.id })
    return matches.takeIf { it.isNotEmpty() }?.let { DuplicateShareReminder(incoming, it) }
}

internal fun duplicateShareDifferences(incoming: MobileBatch, old: MobileBatch): List<String> = buildList {
    if (incoming.images.map { it.hash } != old.images.map { it.hash }) add("照片或頁序不同")
    if (incoming.userNotes != old.userNotes) add("整批說明不同")
    if (incoming.images.associate { it.hash to it.notes } != old.images.associate { it.hash to it.notes }) add("逐圖說明不同")
    if (incoming.images.filter { it.role != "receipt" } != old.images.filter { it.role != "receipt" }) add("參考圖片不同")
    if (incoming.images.associate { it.hash to it.role } != old.images.associate { it.hash to it.role }) add("照片角色不同")
    if (incoming.engine != old.engine) add("辨識來源不同")
}

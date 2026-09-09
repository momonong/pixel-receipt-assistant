package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.*
import com.momonong.pixelreceipt.domain.rules.ReceiptReconciler
import java.util.UUID

/** Trust boundary: bounded observations become facts, never ledger results or workflow instructions. */
class MapReceiptRecognition {
    fun map(base: ReceiptDraft, sources: List<EvidenceAsset>, output: ReceiptRecognition,
        provenance: ExtractionProvenance): ReceiptDraft {
        require(sources.size in 1..20 && output.pages.size == sources.size)
        require(sources.map { it.id }.distinct().size == sources.size && sources.all { it.id in base.evidenceAssetIds })
        require(output.items.size in 1..100 && output.adjustments.size <= 50) { "沒有可帶入的品項，文字辨識不等於品項擷取成功。" }
        require(output.merchants.size <= 40 && output.dates.size <= 100 && output.totals.size <= 100)
        require(output.warnings.size <= 200 && output.warnings.all { it.length <= 2000 })
        require(output.pages.sumOf { it.lines.size } <= 500 && output.pages.sumOf { p -> p.lines.sumOf { it.text.length } } <= 50_000)
        val regions = mutableListOf<EvidenceRegion>()
        val refs = mutableMapOf<Pair<Int, Int>, EvidenceReference>()
        output.pages.forEachIndexed { p, page ->
            require(page.width in 1..20_000 && page.height in 1..20_000 && page.lines.size <= 1000)
            page.lines.forEachIndexed { l, row ->
                require(row.text.isNotBlank() && row.text.length <= 2000)
                require(row.rawText == null || row.rawText.length <= 500)
                val id = UUID.randomUUID().toString()
                regions += EvidenceRegion(id, sources[p].id, page.width, page.height,
                    row.left, row.top, row.right, row.bottom,
                    if (row.rawText == null || row.rawText == row.text) row.text else "${row.rawText}\n分欄文字：${row.text}")
                refs[p to l] = EvidenceReference(sources[p].id, id)
            }
        }
        val warnings = output.warnings.toMutableList()
        if (sources.size > 1) warnings += "多頁收據的順序、缺頁與跨頁品項關聯仍須人工核對；OCR 不保證已找到所有重複。"
        val links = mutableListOf<EvidenceLink>()
        fun evidence(observation: TextObservation): EvidenceReference {
            require(observation.text.isNotBlank() && observation.text.length <= 500)
            return requireNotNull(refs[observation.page to observation.line]) { "辨識結果引用不存在的文字區域。" }
        }
        fun <T : Any> fact(observations: List<TextObservation>, label: String, parse: (String) -> T): Fact<T> {
            val references = observations.map(::evidence)
            val candidates = observations.mapNotNull { observation ->
                try { Fact.Known(parse(observation.text), FactProvenance.Extracted(provenance, listOf(evidence(observation)))) }
                catch (_: IllegalArgumentException) { warnings += "$label：無效值「${observation.text}」，保留未知。"; null }
            }
            if (candidates.size != observations.size || candidates.isEmpty()) return Fact.Unknown(UnknownFactReason.IncompleteExtraction, references)
            return if (candidates.map { it.value }.distinct().size > 1) Fact.Conflicting(candidates)
                else Fact.Known(candidates.first().value, FactProvenance.Extracted(provenance, references))
        }
        fun amount(text: String): Money {
            val cleaned = text.trim().replace(Regex("^(?:NT\\$|TWD|\\$)\\s*"), "")
            require(cleaned.matches(Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.0+)?")))
            return Money(ReviewParsing.amount(cleaned.substringBefore('.').replace(",", "")), "TWD")
        }
        fun link(observations: List<TextObservation>, target: EvidenceLinkTarget) {
            observations.map(::evidence).distinct().forEach { reference ->
                links += EvidenceLink(UUID.randomUUID().toString(), reference, target, EvidenceLinkStatus.Candidate,
                    FactProvenance.Extracted(provenance, listOf(reference)), rationale = "本機 OCR 候選，須人工核對")
            }
        }
        // Match occurrences by name across pages. Never collapse repeated rows within one page.
        val groups = mutableListOf<MutableList<RecognizedItem>>()
        output.items.forEach { item ->
            evidence(item.name)
            val key = item.name.text.filterNot(Char::isWhitespace).lowercase(java.util.Locale.ROOT)
            val matching = groups.firstOrNull { group ->
                similarName(group.first().name.text.filterNot(Char::isWhitespace).lowercase(java.util.Locale.ROOT), key) &&
                    group.none { it.name.page == item.name.page }
            }
            if (matching != null) matching += item else groups += mutableListOf(item)
        }
        var duplicates = false
        val items = groups.map { group ->
            val id = UUID.randomUUID().toString()
            val names = group.map { it.name }
            val quantities = group.mapNotNull { it.quantity }
            val totals = group.mapNotNull { it.lineTotal }
            val unitPrices = group.mapNotNull { it.unitPrice }
            // Validate unit-price observations but do not multiply or substitute them for totals.
            unitPrices.forEach { evidence(it) }
            var qty = fact(quantities, "數量", ReviewParsing::quantity)
            var printed = fact(totals, "行金額", ::amount)
            if (group.size > 1) {
                duplicates = true
                warnings += "跨頁可能重複：${names.first().text}（${group.map { it.name.page + 1 }}）。暫列一項，數量與行金額請明確核對；若實為多項請新增。原文完整保留。"
                qty = Fact.Unknown(UnknownFactReason.Ambiguous, names.map(::evidence))
                printed = Fact.Unknown(UnknownFactReason.Ambiguous, names.map(::evidence))
            }
            link(names + quantities + totals + unitPrices, EvidenceLinkTarget.ReceiptLine(id))
            ReceiptLineDraft(id, rawName = fact(names, "品名") { it.trim().also { name -> require(name.isNotEmpty()) } },
                quantity = qty, printedTotal = printed)
        }
        val adjustmentGroups = output.adjustments.groupBy { it.label.text.lowercase(java.util.Locale.ROOT) to it.subtract }
        val adjustments = adjustmentGroups.values.flatMap { group ->
            val acrossPages = group.map { it.label.page }.distinct().size > 1
            val entries = if (acrossPages) listOf(group) else group.map { listOf(it) }
            entries.map { observations ->
                val first = observations.first()
                val id = UUID.randomUUID().toString()
                val labels = observations.map { it.label }
                val amounts = observations.mapNotNull { it.amount }
                link(labels + amounts, EvidenceLinkTarget.Adjustment(id))
                if (acrossPages) { duplicates = true; warnings += "跨頁調整可能重複：${first.label.text}，暫列一筆，請核對金額與範圍。" }
                ReceiptAdjustment(id, ReceiptAdjustmentKind.Other,
                    if (first.subtract) AdjustmentDirection.Subtract else AdjustmentDirection.Add,
                    if (acrossPages) Fact.Unknown(UnknownFactReason.Ambiguous, labels.map(::evidence)) else fact(amounts, "另列調整", ::amount),
                    Fact.Unknown(UnknownFactReason.Ambiguous, labels.map(::evidence)))
            }
        }
        link(output.merchants + output.dates + output.totals, EvidenceLinkTarget.Receipt(base.id))
        val mapped = base.copy(merchant = fact(output.merchants, "商家") { it.trim() },
            transactionDate = fact(output.dates, "日期", ReviewParsing::date), total = fact(output.totals, "交易總額", ::amount),
            items = items, adjustments = adjustments, evidenceLinks = links,
            assemblyStatus = if (duplicates) ReceiptAssemblyStatus.PossibleDuplicates else if (sources.size > 1) ReceiptAssemblyStatus.NeedsOrdering else ReceiptAssemblyStatus.Collecting,
            stage = ReceiptStage.NeedsReview)
        return mapped.copy(extraction = ReceiptExtractionRecord(provenance, sources.associate { it.id to it.contentSha256 },
            regions, warnings.distinct().take(300), items.size, "${receiptAmountPreview(mapped)}；${ReceiptReconciler().reconcile(mapped)}"))
    }

    private fun similarName(a: String, b: String): Boolean {
        if (a == b) return true
        if (minOf(a.length, b.length) < 2 || kotlin.math.abs(a.length - b.length) > 1) return false
        var previous = IntArray(b.length + 1) { it }
        a.forEachIndexed { i, char ->
            val next = IntArray(b.length + 1)
            next[0] = i + 1
            b.forEachIndexed { j, other -> next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (char == other) 0 else 1) }
            previous = next
        }
        return previous.last() <= 1
    }
}

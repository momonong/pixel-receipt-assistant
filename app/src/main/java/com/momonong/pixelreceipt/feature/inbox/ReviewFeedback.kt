package com.momonong.pixelreceipt.feature.inbox

import com.momonong.pixelreceipt.domain.rules.ReceiptReconciliationResult
import com.momonong.pixelreceipt.domain.usecase.ReviewInput
import com.momonong.pixelreceipt.domain.usecase.ReviewParsing

/** Display identifiers as locations in this editor; never change reconciliation decisions. */
internal fun reviewLocations(text: String, input: ReviewInput): String {
    var result = text
    input.lines.forEachIndexed { i, line -> result = result.replace(line.id, "第 ${i + 1} 項「${line.name.ifBlank { "未填品名" }}」") }
    input.adjustments.forEachIndexed { i, adjustment -> result = result.replace(adjustment.id, "第 ${i + 1} 筆加減項") }
    return result
}

internal fun reviewResultText(result: ReceiptReconciliationResult, input: ReviewInput): String =
    reviewLocations(reconciliationText(result), input)

internal fun inputProblem(value: String, kind: String, required: Boolean = false): String? {
    val text = value.trim()
    if (text.isEmpty()) return if (required) "尚未填寫；可先保存草稿，確認記帳前再補齊。" else null
    return try {
        when (kind) {
            "amount" -> ReviewParsing.amount(text)
            "quantity" -> ReviewParsing.quantity(text)
            "date" -> ReviewParsing.date(text)
        }
        null
    } catch (error: IllegalArgumentException) { error.message }
}

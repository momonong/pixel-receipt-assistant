package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.model.*
import java.math.BigInteger

/** Amount-only diagnostic. Never authorizes confirmation or infers missing amounts/scope. */
fun receiptAmountPreview(draft: ReceiptDraft): String {
    val total = (draft.total as? Fact.Known)?.value ?: return "金額差額未知：交易總額未知或衝突。"
    if (draft.items.isEmpty()) return "金額差額未知：尚無品項。"
    var sum = BigInteger.ZERO
    draft.items.forEach {
        val amount = (it.printedTotal as? Fact.Known)?.value ?: return "金額差額未知：品項行金額未知或衝突。"
        if (amount.currencyCode != total.currencyCode) return "金額差額未知：幣別不一致。"
        sum += amount.minorUnits.toBigInteger()
    }
    draft.adjustments.forEach {
        val amount = (it.amount as? Fact.Known)?.value ?: return "金額差額未知：調整金額未知或衝突。"
        if (amount.currencyCode != total.currencyCode) return "金額差額未知：幣別不一致。"
        sum += if (it.direction == AdjustmentDirection.Add) amount.minorUnits.toBigInteger() else -amount.minorUnits.toBigInteger()
    }
    return "已知金額試算：$sum，收據總額 ${total.minorUnits}，差額 ${sum - total.minorUnits.toBigInteger()} ${total.currencyCode}；仍須通過完整核對規則。"
}

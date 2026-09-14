package com.momonong.pixelreceipt.domain.model

import java.time.LocalDate

/** External observations only. No local IDs, ownership, computed amounts or workflow fields. */
data class AssistantReceipt(
    val schema: String,
    val currency: String,
    val merchant: String?,
    val date: String?,
    val totalMinor: Long?,
    val items: List<AssistantReceiptItem>,
    val adjustments: List<AssistantReceiptAdjustment> = emptyList(),
) {
    fun validate() {
        require(schema == "pixelreceipt-1") { "收據格式版本不支援，請使用 App 提供的 Gemini 指令重新整理。" }
        require(currency == "TWD") { "目前只接受明確標示 TWD 的收據；請先確認幣別。" }
        fun text(value: String?) {
            require(value == null || (value.isNotBlank() && value.length <= 500)) { "文字欄位須為 null 或 1 至 500 字。" }
        }
        text(merchant)
        date?.let {
            require(it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && runCatching { LocalDate.parse(it) }.isSuccess) { "交易日期須為存在的 YYYY-MM-DD。" }
        }
        require(totalMinor == null || totalMinor >= 0) { "總額不可為負數。" }
        require(items.size in 1..100 && adjustments.size <= 50) { "需包含 1 至 100 個品項，另列加減項最多 50 筆。" }
        items.forEach {
            text(it.name)
            require(it.quantity == null || it.quantity > 0) { "數量須為正整數，未知請用 null。" }
            require(it.lineTotalMinor == null || it.lineTotalMinor >= 0) { "品項金額不可為負數。" }
        }
        adjustments.forEach {
            text(it.label)
            require(it.direction == "add" || it.direction == "subtract") { "加減項須標示 add 或 subtract。" }
            require(it.amountMinor == null || it.amountMinor >= 0) { "加減項金額須為非負整數，方向另外指定。" }
        }
    }
}

data class AssistantReceiptItem(val name: String?, val quantity: Int?, val lineTotalMinor: Long?)
data class AssistantReceiptAdjustment(val label: String?, val direction: String, val amountMinor: Long?)

enum class AssistantImportChannel { PastedText, SharedText, AppFunction }

/** Retained after corrections. Channel is locally selected; a Gemini identity is never asserted. */
data class AssistantImportRecord(
    val channel: AssistantImportChannel,
    val inputSha256: String,
    val importedAtEpochMillis: Long,
    val rawInput: String,
)

package com.momonong.pixelreceipt.data.extraction

import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide
import java.time.LocalDate

/** Typed numeric/date fields prevent currency suffixes and timestamps from entering numeric slots. */
@Generable
data class NanoReceiptFields(
    @Guide(description = "Currency code TWD for Taiwanese dollars, other code for foreign currency; null if unclear")
    val currency: String?,
    @Guide(description = "Printed merchant name; copy faithfully from the image, OCR may help")
    val merchant: String?,
    @Guide(description = "Printed four-digit calendar year, not an order number. Null if not printed", minimum = 1000.0, maximum = 9999.0)
    val year: Int?,
    @Guide(description = "Printed month number, null if unclear", minimum = 1.0, maximum = 12.0)
    val month: Int?,
    @Guide(description = "Printed day of month, null if unclear", minimum = 1.0, maximum = 31.0)
    val day: Int?,
    @Guide(description = "Printed final transaction amount in whole TWD after discounts, not tendered cash or subtotal. Null if unclear", minimum = 0.0)
    val total: Long?,
    @Guide(description = "Only actual printed product and monetary summary rows. Stop at the end of the receipt; the maximum is NOT a target count. Never pad this list.", maxItems = 100)
    val rows: List<NanoReceiptFieldsRow>,
) {
    fun observations(): NanoReceiptOutput {
        val date = if (year == null || month == null || day == null) null else {
            require(year in 1000..9999)
            requireNotNull(runCatching { LocalDate.of(requireNotNull(year), requireNotNull(month), requireNotNull(day)).toString() }.getOrNull()) { "Nano 回傳無效日期。" }
        }
        return NanoReceiptOutput(currency, merchant, date, total?.toString(), rows.map { row ->
            NanoReceiptRow(row.kind, row.name, row.quantity?.toString(), row.unitPrice?.toString(), row.amount?.toString(), row.effect)
        })
    }
}

@Generable
data class NanoReceiptFieldsRow(
    @Guide(enumValues = ["product", "discount", "fee", "payment", "change", "member", "reward", "subtotal", "other"])
    val kind: String,
    @Guide(description = "Exact printed row name in Traditional Chinese. A preparation note belongs to its parent product, not a separate product")
    val name: String?,
    @Guide(description = "Explicit printed quantity only; null if absent, never default to 1", minimum = 1.0)
    val quantity: Int?,
    @Guide(description = "Printed unit price only. Not a discount amount. Null if no separate unit price is printed", minimum = 0.0)
    val unitPrice: Long?,
    @Guide(description = "Printed row total in whole TWD, without currency or tax suffix. Null if absent; do not calculate", minimum = 0.0)
    val amount: Long?,
    @Guide(description = "For discount or fee: already included in product amounts, applied separately afterwards, or unknown",
        enumValues = ["included", "separate", "unknown"])
    val effect: String,
)

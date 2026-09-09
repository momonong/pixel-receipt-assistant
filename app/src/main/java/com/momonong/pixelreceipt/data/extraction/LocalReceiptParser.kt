package com.momonong.pixelreceipt.data.extraction

import com.momonong.pixelreceipt.domain.port.*
import java.text.Normalizer

/** Conservative receipt grammar, not a language model. Never calculates an amount. */
class LocalReceiptParser {
    companion object { const val VERSION = "receipt-layout-1" }
    private val number = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?"
    private val date = Regex("(?<![0-9])([0-9]{3,4})[-/.年]([0-9]{1,2})[-/.月]([0-9]{1,2})日?")
    private val total = Regex("^(?:總計|總額|合計|應付(?:金額)?|實付(?:金額)?|交易總額|TOTAL)\\s*[:：]?\\s*(?:NT\\$|TWD|\\$)?\\s*($number)元?$", RegexOption.IGNORE_CASE)
    private val adjustment = Regex("^(.*?(?:折扣|折讓|折抵|優惠券|服務費|運費|手續費|COUPON|DISCOUNT|FEE))\\s*[:：]?\\s*([-−]?)\\s*(?:\\$)?($number)元?$", RegexOption.IGNORE_CASE)
    private val ignored = Regex("^(?:小計|現金|找零|找回|信用卡|刷卡|付款|支付|統編|統一編號|電話|TEL|地址|發票|交易序號|收銀|機台|會員|稅額|課稅|免稅|銷售額|營業稅|節省|已折|您已|含稅|頁碼|第\\s*[0-9]+\\s*頁|感謝|歡迎|謝謝|備註|CASH|CHANGE|SUBTOTAL).*", RegexOption.IGNORE_CASE)

    fun parse(pages: List<OcrPage>): ReceiptRecognition {
        require(pages.size in 1..20)
        require(pages.sumOf { it.lines.size } <= 500 && pages.sumOf { page -> page.lines.sumOf { it.text.length } } <= 50_000)
        require(pages.all { page -> page.lines.all { it.text.length <= 500 } })
        require(pages.none { page -> page.lines.any { Regex("\\b(?:USD|EUR|JPY|CNY|RMB|HKD)\\b|美元|日圓|日元|人民幣|人民币|港幣|港币|€|¥", RegexOption.IGNORE_CASE).containsMatchIn(it.text) } }) {
            "本次本機解析僅支援 TWD 收據。"
        }
        val merchants = mutableListOf<TextObservation>()
        val dates = mutableListOf<TextObservation>()
        val totals = mutableListOf<TextObservation>()
        val items = mutableListOf<RecognizedItem>()
        val adjustments = mutableListOf<RecognizedAdjustment>()
        val warnings = mutableListOf<String>()
        pages.forEachIndexed { p, page ->
            require(page.lines.size <= 1000)
            var columns = emptyList<String>()
            var ended = false
            var firstContent = true
            page.lines.forEachIndexed { l, row ->
                val text = normalize(row.text)
                fun obs(value: String) = TextObservation(value, p, l)
                val dateMatch = date.find(text)
                val totalMatch = total.matchEntire(text)
                val adjustmentMatch = adjustment.matchEntire(text)
                val headerPattern = Regex("品名|商品(?:名稱)?|名稱|數量|数量|單價|单价|金額|金额|行合計|ITEM|QTY|PRICE|AMOUNT", RegexOption.IGNORE_CASE)
                val header = headerPattern.findAll(text).toList()
                when {
                    text.isBlank() -> Unit
                    dateMatch != null -> {
                        val (year, month, day) = dateMatch.destructured
                        val y = year.toInt() + if (year.length == 3) 1911 else 0
                        dates += obs("%04d-%02d-%02d".format(java.util.Locale.ROOT, y, month.toInt(), day.toInt()))
                        firstContent = false
                    }
                    totalMatch != null -> { totals += obs(totalMatch.groupValues[1]); ended = true }
                    adjustmentMatch != null -> {
                        val label = adjustmentMatch.groupValues[1]
                        adjustments += RecognizedAdjustment(obs(label), obs(adjustmentMatch.groupValues[3]),
                            Regex("折|券|COUPON|DISCOUNT", RegexOption.IGNORE_CASE).containsMatchIn(label))
                        firstContent = false
                    }
                    header.size >= 2 -> {
                        val tokens = text.split(' ')
                        // Preserve an unreadable header's position instead of shifting later columns.
                        val labels = if (tokens.size >= header.size && tokens.size > 1) tokens else header.map { it.value }
                        columns = labels.map { when (it.uppercase()) {
                            "數量", "数量", "QTY" -> "qty"
                            "單價", "单价", "PRICE" -> "unit"
                            "金額", "金额", "行合計", "AMOUNT" -> "total"
                            "品名", "商品", "商品名稱", "名稱", "ITEM" -> "name"
                            else -> "unknown"
                        } }
                        ended = false
                        firstContent = false
                    }
                    ignored.matches(text) -> firstContent = false
                    ended -> Unit
                    else -> {
                        // Explicit multiplication layout includes BOTH unit price and printed line total.
                        val explicit = Regex("^(.+?)\\s+($number)\\s*[xX×*]\\s*($number)\\s+(?:=\\s*)?($number)$").matchEntire(text)
                        val tail = Regex("^(.*?)\\s+((?:[-−]?(?:NT\\$|TWD|\\$)?$number\\s*)+)$").matchEntire(text)
                        if (explicit != null) {
                            items += RecognizedItem(obs(explicit.groupValues[1]), obs(explicit.groupValues[2]), obs(explicit.groupValues[4]), obs(explicit.groupValues[3]))
                            firstContent = false
                        } else if (tail != null && tail.groupValues[1].any(Char::isLetter)) {
                            val name = tail.groupValues[1]
                            val values = Regex("[-−]?(?:NT\\$|TWD|\\$)?$number").findAll(tail.groupValues[2]).map { it.value }.toList()
                            val numericColumns = columns.filter { it != "name" }
                            val mapped = if (numericColumns.size == values.size && numericColumns.distinct().size == numericColumns.size)
                                numericColumns.zip(values).toMap() else emptyMap()
                            items += RecognizedItem(obs(name), mapped["qty"]?.let(::obs), mapped["total"]?.let(::obs), mapped["unit"]?.let(::obs))
                            if (mapped["total"] == null) warnings += "圖片 ${p + 1} 第 ${l + 1} 行：無法區分單價與行合計，金額留待核對，請查看原文。"
                            firstContent = false
                        } else if (firstContent && text.any(Char::isLetter) && !text.any(Char::isDigit)) {
                            merchants += obs(text)
                            firstContent = false
                        } else {
                            warnings += "圖片 ${p + 1} 第 ${l + 1} 行未能結構化，請查看原文。"
                            firstContent = false
                        }
                    }
                }
            }
        }
        require(items.size <= 100 && adjustments.size <= 50) { "辨識結果超過 100 品項或 50 調整。" }
        return ReceiptRecognition(pages, merchants, dates, totals, items, adjustments, warnings.take(200))
    }

    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
        .replace(Regex("\\s+"), " ")
}

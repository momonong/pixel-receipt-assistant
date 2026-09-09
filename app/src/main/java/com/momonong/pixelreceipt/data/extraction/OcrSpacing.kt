package com.momonong.pixelreceipt.data.extraction

/** Reconstruct visible inter-column gaps even when Chinese OCR omits whitespace. Geometry only. */
data class OcrSymbol(val text: String, val left: Int, val right: Int)

fun spacedOcrText(raw: String, symbols: List<OcrSymbol>, visibleGaps: List<IntRange>? = null): String {
    if (symbols.isEmpty()) return raw
    val ordered = symbols.sortedBy { it.left }
    val widths = ordered.map { it.right - it.left }.filter { it > 0 }.sorted()
    if (widths.isEmpty()) return raw
    val threshold = maxOf(4, widths[widths.size / 2] * 3 / 5)
    val rebuilt = buildString {
        ordered.forEachIndexed { i, symbol ->
            if (i > 0) {
                val previous = ordered[i - 1]
                val gap = visibleGaps?.any {
                    val center = (it.first + it.last) / 2
                    center > (previous.left + previous.right) / 2 && center < (symbol.left + symbol.right) / 2
                }
                    ?: (symbol.left - previous.right > threshold)
                if (gap) append(' ')
            }
            append(symbol.text)
        }
    }
    // Preserve SDK-provided word separation when geometry is less informative.
    return if (rebuilt.count(Char::isWhitespace) >= raw.count(Char::isWhitespace)) rebuilt else raw
}

package com.momonong.pixelreceipt.domain.port

/** Untrusted OCR observations. References are positions, never application IDs. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val rawText: String? = null)
data class OcrPage(val width: Int, val height: Int, val lines: List<OcrLine>)
data class TextObservation(val text: String, val page: Int, val line: Int)
data class RecognizedItem(
    val name: TextObservation,
    val quantity: TextObservation?,
    val lineTotal: TextObservation?,
    val unitPrice: TextObservation? = null,
)
data class RecognizedAdjustment(val label: TextObservation, val amount: TextObservation?, val subtract: Boolean)
data class ReceiptRecognition(
    val pages: List<OcrPage>,
    val merchants: List<TextObservation>,
    val dates: List<TextObservation>,
    val totals: List<TextObservation>,
    val items: List<RecognizedItem>,
    val adjustments: List<RecognizedAdjustment>,
    val warnings: List<String>,
)

package com.momonong.pixelreceipt.domain.model

/** Local audit snapshot survives edits. Region coordinates refer to EXIF-oriented OCR images. */
data class ReceiptExtractionRecord(
    val provenance: ExtractionProvenance,
    val sourceHashes: Map<String, String>,
    val regions: List<EvidenceRegion>,
    val warnings: List<String>,
    val originalItemCount: Int,
    val originalReconciliation: String,
)

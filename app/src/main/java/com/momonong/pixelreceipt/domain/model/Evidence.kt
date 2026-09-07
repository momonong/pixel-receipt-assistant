package com.momonong.pixelreceipt.domain.model

/**
 * Immutable metadata for an image that can support a receipt or promotion claim.
 *
 * The source URI is deliberately not part of the model: imported bytes are identified by their
 * digest so a revoked picker/share grant cannot change the evidence behind an accepted claim.
 */
data class EvidenceAsset(
    val id: String,
    /** Classification is a fact: importing an image does not prove what it depicts. */
    val kind: Fact<EvidenceAssetKind> = Fact.Unknown(UnknownFactReason.PendingAnalysis),
    val contentSha256: String,
    val mimeType: String,
    val byteSize: Long,
    val widthPx: Int,
    val heightPx: Int,
    val importSource: EvidenceImportSource,
    val importedAtEpochMillis: Long,
    val capturedAtEpochMillis: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Evidence asset id cannot be blank." }
        require(Sha256Pattern.matches(contentSha256)) {
            "Evidence digest must be a lowercase SHA-256 value."
        }
        require(mimeType.startsWith("image/")) { "Evidence asset must be an image." }
        require(byteSize > 0) { "Evidence asset cannot be empty." }
        require(widthPx > 0 && heightPx > 0) { "Evidence dimensions must be positive." }
        require(importedAtEpochMillis >= 0) { "Import timestamp cannot be negative." }
        require(capturedAtEpochMillis == null || capturedAtEpochMillis >= 0) {
            "Capture timestamp cannot be negative."
        }
    }

    private companion object {
        val Sha256Pattern = Regex("[0-9a-f]{64}")
    }
}

enum class EvidenceAssetKind {
    PriceTag,
    PromotionSign,
    ReceiptPage,
    Other,
}

enum class EvidenceImportSource {
    PhotoPicker,
    ShareSheet,
    InAppCamera,
    Other,
}

/** Pixel coordinates inside [assetId]. The original crop remains independently auditable. */
data class EvidenceRegion(
    val id: String,
    val assetId: String,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
    val rawText: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Evidence region id cannot be blank." }
        require(assetId.isNotBlank()) { "Evidence asset id cannot be blank." }
        require(imageWidthPx > 0 && imageHeightPx > 0) {
            "Evidence image dimensions must be positive."
        }
        require(leftPx >= 0 && topPx >= 0) { "Evidence region origin cannot be negative." }
        require(rightPx > leftPx && bottomPx > topPx) {
            "Evidence region must have a positive width and height."
        }
        require(rightPx <= imageWidthPx && bottomPx <= imageHeightPx) {
            "Evidence region must fit inside the image."
        }
        require(rawText == null || rawText.isNotBlank()) {
            "Evidence region text cannot be blank when present."
        }
    }
}

/** Reference to either an entire evidence asset or one extracted region within it. */
data class EvidenceReference(
    val assetId: String,
    val regionId: String? = null,
) {
    init {
        require(assetId.isNotBlank()) { "Evidence asset id cannot be blank." }
        require(regionId == null || regionId.isNotBlank()) {
            "Evidence region id cannot be blank when present."
        }
    }
}

/** Identifies the exact extraction configuration that produced machine-observed facts. */
data class ExtractionProvenance(
    val runId: String,
    val extractorName: String,
    val extractorVersion: String,
    val schemaVersion: String,
    val runtime: ExtractionRuntime,
    val extractedAtEpochMillis: Long,
    val promptVersion: String? = null,
) {
    init {
        require(runId.isNotBlank()) { "Extraction run id cannot be blank." }
        require(extractorName.isNotBlank()) { "Extractor name cannot be blank." }
        require(extractorVersion.isNotBlank()) { "Extractor version cannot be blank." }
        require(schemaVersion.isNotBlank()) { "Extraction schema version cannot be blank." }
        require(extractedAtEpochMillis >= 0) { "Extraction timestamp cannot be negative." }
        require(promptVersion == null || promptVersion.isNotBlank()) {
            "Prompt version cannot be blank when present."
        }
    }
}

enum class ExtractionRuntime {
    Deterministic,
    OnDevice,
    Cloud,
}

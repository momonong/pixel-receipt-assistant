package com.momonong.pixelreceipt.domain.model

/** A reviewable many-to-many link from image evidence to a local transaction entity. */
data class EvidenceLink(
    val id: String,
    val evidence: EvidenceReference,
    val target: EvidenceLinkTarget,
    val status: EvidenceLinkStatus,
    val provenance: FactProvenance,
    val confidenceBasisPoints: Int? = null,
    val rationale: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Evidence link id cannot be blank." }
        require(confidenceBasisPoints == null || confidenceBasisPoints in 0..10_000) {
            "Evidence link confidence must be between 0 and 10000 basis points."
        }
        require(rationale == null || rationale.isNotBlank()) {
            "Evidence link rationale cannot be blank when present."
        }
        if (provenance is FactProvenance.Extracted) {
            require(evidence in provenance.evidence) {
                "An extracted evidence link must cite the linked evidence."
            }
        }
        if (status != EvidenceLinkStatus.Candidate) {
            require(
                provenance is FactProvenance.Derived ||
                    provenance is FactProvenance.UserConfirmed,
            ) {
                "Only a deterministic rule or user can confirm or reject an evidence link."
            }
        }
    }
}

sealed interface EvidenceLinkTarget {
    val localId: String

    data class Receipt(override val localId: String) : EvidenceLinkTarget {
        init {
            require(localId.isNotBlank()) { "Receipt target id cannot be blank." }
        }
    }

    data class ReceiptLine(override val localId: String) : EvidenceLinkTarget {
        init {
            require(localId.isNotBlank()) { "Receipt line target id cannot be blank." }
        }
    }

    data class Adjustment(override val localId: String) : EvidenceLinkTarget {
        init {
            require(localId.isNotBlank()) { "Adjustment target id cannot be blank." }
        }
    }

    data class PromotionOffer(override val localId: String) : EvidenceLinkTarget {
        init {
            require(localId.isNotBlank()) { "Promotion offer target id cannot be blank." }
        }
    }
}

enum class EvidenceLinkStatus {
    Candidate,
    Confirmed,
    Rejected,
}

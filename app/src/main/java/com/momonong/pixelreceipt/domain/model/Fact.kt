package com.momonong.pixelreceipt.domain.model

/** An evidence-aware value. Unknown and not-applicable are states, never numeric defaults. */
sealed interface Fact<out T : Any> {
    data class Known<out T : Any>(
        val value: T,
        val provenance: FactProvenance,
    ) : Fact<T>

    class Unknown(
        val reason: UnknownFactReason,
        evidence: Collection<EvidenceReference> = emptySet(),
    ) : Fact<Nothing> {
        val evidence: Set<EvidenceReference> = evidence.toSet()

        override fun equals(other: Any?): Boolean = other is Unknown &&
            reason == other.reason && evidence == other.evidence

        override fun hashCode(): Int = 31 * reason.hashCode() + evidence.hashCode()

        override fun toString(): String = "Unknown(reason=$reason, evidence=$evidence)"
    }

    class Conflicting<out T : Any>(
        candidates: Collection<Known<T>>,
    ) : Fact<T> {
        val candidates: List<Known<T>> = candidates.toList()

        init {
            require(this.candidates.size >= 2) { "A conflict requires at least two candidates." }
            require(this.candidates.map { it.value }.distinct().size >= 2) {
                "Conflicting candidates must contain different values."
            }
        }

        override fun equals(other: Any?): Boolean = other is Conflicting<*> &&
            candidates == other.candidates

        override fun hashCode(): Int = candidates.hashCode()

        override fun toString(): String = "Conflicting(candidates=$candidates)"
    }

    data class NotApplicable(
        val reason: String,
    ) : Fact<Nothing> {
        init {
            require(reason.isNotBlank()) { "Not-applicable reason cannot be blank." }
        }
    }
}

enum class UnknownFactReason {
    MissingEvidence,
    NotObserved,
    Unreadable,
    IncompleteExtraction,
    Ambiguous,
    PendingAnalysis,
    Unsupported,
}

sealed interface FactProvenance {
    val evidence: Set<EvidenceReference>

    class Extracted(
        val extraction: ExtractionProvenance,
        evidence: Collection<EvidenceReference>,
        /** Optional confidence for this individual observation, in basis points. */
        val confidenceBasisPoints: Int? = null,
    ) : FactProvenance {
        override val evidence: Set<EvidenceReference> = evidence.toSet()

        init {
            require(this.evidence.isNotEmpty()) { "An extracted fact requires source evidence." }
            require(confidenceBasisPoints == null || confidenceBasisPoints in 0..10_000) {
                "Extraction confidence must be between 0 and 10000 basis points."
            }
        }

        override fun equals(other: Any?): Boolean = other is Extracted &&
            extraction == other.extraction &&
            evidence == other.evidence &&
            confidenceBasisPoints == other.confidenceBasisPoints

        override fun hashCode(): Int {
            var result = extraction.hashCode()
            result = 31 * result + evidence.hashCode()
            result = 31 * result + (confidenceBasisPoints ?: 0)
            return result
        }

        override fun toString(): String =
            "Extracted(extraction=$extraction, evidence=$evidence, " +
                "confidenceBasisPoints=$confidenceBasisPoints)"
    }

    class UserConfirmed(
        val confirmedAtEpochMillis: Long,
        evidence: Collection<EvidenceReference> = emptySet(),
    ) : FactProvenance {
        override val evidence: Set<EvidenceReference> = evidence.toSet()

        init {
            require(confirmedAtEpochMillis >= 0) { "Confirmation timestamp cannot be negative." }
        }

        override fun equals(other: Any?): Boolean = other is UserConfirmed &&
            confirmedAtEpochMillis == other.confirmedAtEpochMillis &&
            evidence == other.evidence

        override fun hashCode(): Int =
            31 * confirmedAtEpochMillis.hashCode() + evidence.hashCode()

        override fun toString(): String =
            "UserConfirmed(confirmedAtEpochMillis=$confirmedAtEpochMillis, evidence=$evidence)"
    }

    class Derived(
        val ruleName: String,
        val ruleVersion: String,
        inputFactIds: Collection<String>,
        evidence: Collection<EvidenceReference> = emptySet(),
    ) : FactProvenance {
        val inputFactIds: Set<String> = inputFactIds.toSet()
        override val evidence: Set<EvidenceReference> = evidence.toSet()

        init {
            require(ruleName.isNotBlank()) { "Derivation rule name cannot be blank." }
            require(ruleVersion.isNotBlank()) { "Derivation rule version cannot be blank." }
            require(this.inputFactIds.isNotEmpty()) {
                "A derived fact requires at least one input."
            }
            require(this.inputFactIds.none(String::isBlank)) {
                "Derived fact input ids cannot be blank."
            }
        }

        override fun equals(other: Any?): Boolean = other is Derived &&
            ruleName == other.ruleName &&
            ruleVersion == other.ruleVersion &&
            inputFactIds == other.inputFactIds &&
            evidence == other.evidence

        override fun hashCode(): Int {
            var result = ruleName.hashCode()
            result = 31 * result + ruleVersion.hashCode()
            result = 31 * result + inputFactIds.hashCode()
            result = 31 * result + evidence.hashCode()
            return result
        }

        override fun toString(): String =
            "Derived(ruleName=$ruleName, ruleVersion=$ruleVersion, " +
                "inputFactIds=$inputFactIds, evidence=$evidence)"
    }
}

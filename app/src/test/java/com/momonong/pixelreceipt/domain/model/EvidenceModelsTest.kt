package com.momonong.pixelreceipt.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceModelsTest {
    @Test
    fun `asset requires a canonical sha256 and valid image metadata`() {
        val validAsset = asset()

        assertEquals(EvidenceAssetKind.PromotionSign, (validAsset.kind as Fact.Known).value)
        assertThrows(IllegalArgumentException::class.java) {
            asset(contentSha256 = "not-a-digest")
        }
        assertThrows(IllegalArgumentException::class.java) {
            asset(widthPx = 0)
        }
    }

    @Test
    fun `region must stay inside its image`() {
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceRegion(
                id = "region-1",
                assetId = "asset-1",
                imageWidthPx = 100,
                imageHeightPx = 100,
                leftPx = 10,
                topPx = 10,
                rightPx = 101,
                bottomPx = 80,
            )
        }
    }

    @Test
    fun `extracted fact requires auditable evidence`() {
        assertThrows(IllegalArgumentException::class.java) {
            Fact.Known(
                value = Money(100),
                provenance = FactProvenance.Extracted(
                    extraction = extraction(),
                    evidence = emptySet(),
                ),
            )
        }
    }

    @Test
    fun `image classification remains unknown until analysis or confirmation`() {
        val unknown = asset(kind = Fact.Unknown(UnknownFactReason.PendingAnalysis))

        assertTrue(unknown.kind is Fact.Unknown)
    }

    @Test
    fun `individual extraction confidence uses optional integer basis points`() {
        val reference = EvidenceReference("asset-1", "region-1")
        val observed = FactProvenance.Extracted(
            extraction = extraction(),
            evidence = setOf(reference),
            confidenceBasisPoints = 9_500,
        )

        assertEquals(9_500, observed.confidenceBasisPoints)
        assertThrows(IllegalArgumentException::class.java) {
            FactProvenance.Extracted(
                extraction = extraction(),
                evidence = setOf(reference),
                confidenceBasisPoints = 10_001,
            )
        }
    }

    @Test
    fun `unknown money is distinct from known zero`() {
        val unknown: Fact<Money> = Fact.Unknown(UnknownFactReason.MissingEvidence)
        val zero: Fact<Money> = Fact.Known(
            value = Money(0),
            provenance = userConfirmed(),
        )

        assertNotEquals(unknown, zero)
        assertTrue(unknown is Fact.Unknown)
        assertEquals(0, (zero as Fact.Known).value.minorUnits)
    }

    @Test
    fun `conflict requires different candidate values`() {
        val first = Fact.Known(Money(100), userConfirmed(1))
        val duplicateValue = Fact.Known(Money(100), userConfirmed(2))

        assertThrows(IllegalArgumentException::class.java) {
            Fact.Conflicting(listOf(first, duplicateValue))
        }

        val conflict = Fact.Conflicting(
            listOf(first, Fact.Known(Money(120), userConfirmed(3))),
        )
        assertEquals(2, conflict.candidates.size)
    }

    @Test
    fun `facts and provenance snapshot mutable collections`() {
        val reference = EvidenceReference("asset-1")
        val mutableEvidence = mutableSetOf(reference)
        val unknown = Fact.Unknown(UnknownFactReason.Unreadable, mutableEvidence)
        val mutableInputs = mutableSetOf("fact-1")
        val derived = FactProvenance.Derived(
            ruleName = "test-rule",
            ruleVersion = "1",
            inputFactIds = mutableInputs,
            evidence = mutableEvidence,
        )

        mutableEvidence.clear()
        mutableInputs.clear()

        assertEquals(setOf(reference), unknown.evidence)
        assertEquals(setOf("fact-1"), derived.inputFactIds)
        assertEquals(setOf(reference), derived.evidence)
    }

    private fun asset(
        contentSha256: String = "a".repeat(64),
        widthPx: Int = 1_000,
        kind: Fact<EvidenceAssetKind> = known(EvidenceAssetKind.PromotionSign),
    ) = EvidenceAsset(
        id = "asset-1",
        kind = kind,
        contentSha256 = contentSha256,
        mimeType = "image/jpeg",
        byteSize = 1_024,
        widthPx = widthPx,
        heightPx = 800,
        importSource = EvidenceImportSource.PhotoPicker,
        importedAtEpochMillis = 1,
    )

    private fun extraction() = ExtractionProvenance(
        runId = "run-1",
        extractorName = "receipt-extractor",
        extractorVersion = "1.0",
        schemaVersion = "1",
        runtime = ExtractionRuntime.Cloud,
        extractedAtEpochMillis = 1,
    )

    private fun userConfirmed(at: Long = 1) = FactProvenance.UserConfirmed(
        confirmedAtEpochMillis = at,
    )

    private fun <T : Any> known(value: T): Fact.Known<T> = Fact.Known(
        value = value,
        provenance = userConfirmed(),
    )
}

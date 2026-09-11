package com.momonong.pixelreceipt.data

import com.momonong.pixelreceipt.data.local.DraftCodec
import com.momonong.pixelreceipt.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class DraftCodecTest {
    private val codec = DraftCodec()
    @Test fun frozenV3ConfirmedPayloadHasNoInventedOwnershipAndKeepsRevision() {
        val draft = codec.decode(javaClass.getResource("/legacy-draft-v3-confirmed.json")!!.readText())
        assertEquals(ReceiptStage.Confirmed, draft.stage)
        assertEquals(3L, draft.revision)
        assertTrue(draft.personalExpenses.isEmpty())
        assertTrue(draft.expenseAdjustments.isEmpty())
        assertEquals(draft, codec.decode(codec.encode(draft)))
    }

    @Test fun corruptV4MissingOwnershipCollectionsIsRejectedWithoutGuessing() {
        val payload = javaClass.getResource("/legacy-draft-v3-confirmed.json")!!.readText().replace("\"format\": 3", "\"format\": 4")
        assertThrows(IllegalArgumentException::class.java) { codec.decode(payload) }
    }
    @Test fun legacyPayloadMigratesMissingDateToUnknownAndWritesV4() {
        val payload = javaClass.getResource("/legacy-draft-v1.json")!!.readText()
        val draft = codec.decode(payload)
        assertEquals(Fact.Unknown(UnknownFactReason.NotObserved), draft.transactionDate)
        assertEquals(3L, draft.revision)
        assertEquals("舊商店", (draft.merchant as Fact.Known).value)
        val updated = draft.copy(transactionDate = Fact.Known("2026-09-08", FactProvenance.UserConfirmed(40)))
        assertTrue(codec.encode(updated).contains("\"format\":4"))
        assertEquals(updated, codec.decode(codec.encode(updated)))
        assertNotEquals(draft, updated)
    }

    @Test fun corruptV2CannotSilentlyCreateNullDate() {
        val payload = javaClass.getResource("/legacy-draft-v1.json")!!.readText().replace("\"format\":1", "\"format\":2")
        assertThrows(IllegalArgumentException::class.java) { codec.decode(payload) }
    }
    @Test fun manualReviewV2ReadsWithNullExtractionWithoutChangingRevision() {
        val draft = ReceiptDraft("legacy-manual", stage = ReceiptStage.NeedsReview, revision = 7,
            transactionDate = Fact.Known("2026-09-08", FactProvenance.UserConfirmed(1)))
        val v2 = codec.encode(draft).replace("\"format\":4", "\"format\":2")
        val restored = codec.decode(v2)
        assertEquals(draft, restored)
        assertNull(restored.extraction)
        assertEquals(7L, restored.revision)
    }
    @Test fun allFactStatesAndProvenanceRoundTripWithoutNumericCoercion() {
        val ref = EvidenceReference("image", "region")
        val extracted = FactProvenance.Extracted(ExtractionProvenance(
            "run", "extractor", "1", "schema1", ExtractionRuntime.OnDevice, 20, "prompt1",
        ), setOf(ref), 7890)
        val user = FactProvenance.UserConfirmed(30, setOf(ref))
        val derived = FactProvenance.Derived("rule", "1", setOf("fact1"), setOf(ref))
        val draft = ReceiptDraft("draft", merchant = Fact.Conflicting(listOf(
            Fact.Known("A", extracted), Fact.Known("B", user),
        )), total = Fact.Known(Money(Long.MAX_VALUE), derived),
            items = listOf(ReceiptLineDraft("line", rawName = Fact.Known("item", user),
                quantity = Fact.Known(3, extracted), barcodeOrSku = Fact.NotApplicable("none"),
                printedTotal = Fact.Unknown(UnknownFactReason.Unreadable, setOf(ref)))),
            evidenceAssetIds = setOf("image"),
            evidenceLinks = listOf(EvidenceLink("link", ref, EvidenceLinkTarget.ReceiptLine("line"),
                EvidenceLinkStatus.Candidate, extracted, 6789, "candidate")),
            adjustments = listOf(ReceiptAdjustment("adj", ReceiptAdjustmentKind.Coupon, AdjustmentDirection.Subtract,
                Fact.Known(Money(1), user), Fact.Known(ReceiptAdjustmentScope.Order, user))),
            allocations = listOf(Allocation("allocation", "adj", LinePortion("line", 1), Money(1), AllocationMethod.UserDecision, user)),
            promotionOffers = listOf(PromotionOffer("offer", Fact.Known("sale", user), emptyList(),
                Fact.Known(PromotionEligibility.StoreWide, user), Fact.Known(PromotionTerms.PercentageOff(500), user))),
            assemblyStatus = ReceiptAssemblyStatus.PossibleGap, stage = ReceiptStage.NeedsReview, revision = 7,
        )
        assertEquals(draft, codec.decode(codec.encode(draft)))
    }

    @Test fun evidenceMetadataAndClassificationRoundTrip() {
        val asset = EvidenceAsset("image", Fact.Unknown(UnknownFactReason.PendingAnalysis), "a".repeat(64),
            "image/png", 20, 2, 3, EvidenceImportSource.ShareSheet, 50, 40)
        assertEquals(asset, codec.decodeAsset(codec.encodeAsset(asset)))
        val classified = asset.copy(kind = Fact.Known(EvidenceAssetKind.ReceiptPage, FactProvenance.UserConfirmed(60)))
        assertEquals(classified, codec.decodeAsset(codec.encodeAsset(classified)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownPayloadVersionIsRejected() { codec.decode("""{"format":99,"value":{}}""") }
}

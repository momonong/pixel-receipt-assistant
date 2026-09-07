package com.momonong.pixelreceipt.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EvidenceLinkTest {
    @Test
    fun `machine extraction creates only an auditable candidate link`() {
        val reference = EvidenceReference("asset-1", "region-1")
        val link = EvidenceLink(
            id = "link-1",
            evidence = reference,
            target = EvidenceLinkTarget.ReceiptLine("line-1"),
            status = EvidenceLinkStatus.Candidate,
            provenance = FactProvenance.Extracted(
                extraction = extraction(),
                evidence = setOf(reference),
                confidenceBasisPoints = 8_500,
            ),
            confidenceBasisPoints = 8_500,
        )

        assertEquals(EvidenceLinkStatus.Candidate, link.status)
    }

    @Test
    fun `machine extraction cannot confirm a link`() {
        val reference = EvidenceReference("asset-1")

        assertThrows(IllegalArgumentException::class.java) {
            EvidenceLink(
                id = "link-1",
                evidence = reference,
                target = EvidenceLinkTarget.Adjustment("adjustment-1"),
                status = EvidenceLinkStatus.Confirmed,
                provenance = FactProvenance.Extracted(
                    extraction = extraction(),
                    evidence = setOf(reference),
                ),
            )
        }
    }

    private fun extraction() = ExtractionProvenance(
        runId = "run-1",
        extractorName = "evidence-matcher",
        extractorVersion = "1",
        schemaVersion = "1",
        runtime = ExtractionRuntime.OnDevice,
        extractedAtEpochMillis = 1,
    )
}

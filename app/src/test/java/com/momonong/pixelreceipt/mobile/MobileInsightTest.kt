package com.momonong.pixelreceipt.mobile

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MobileInsightTest {
    @Test fun crossFiltersArePreservedForEntries() {
        val q = InsightQuery(dateFrom=LocalDate.parse("2026-11-01"), dateTo=LocalDate.parse("2026-11-12"), category="food", meal="dinner", cityKey="a".repeat(64))
        assertTrue(q.path().contains("meal=dinner"))
        assertTrue(q.path(entries=true).contains("city_key=${"a".repeat(64)}"))
        assertTrue(q.path(entries=true).contains("category=food"))
        assertFalse(q.copy(category=null).path().contains("category="))
    }
    @Test fun originalMerchantFilterIsPreservedAlongsideBrand() {
        val q=InsightQuery(brandKey="a".repeat(64),merchantKey="b".repeat(64))
        assertTrue(q.path().contains("merchant_key=${"b".repeat(64)}"))
        assertTrue(q.path(true).contains("brand_key=${"a".repeat(64)}"))
        assertEquals(q,insightQueryFromJson(q.filters().toString()))
    }
    @Test fun periodChangesUseTheirOwnCompleteComparisonRange() {
        val day=LocalDate.parse("2026-11-12")
        val old=InsightQuery.month(day).withPeriod(SpendingPeriod.PreviousMonth,day)
        assertEquals(LocalDate.parse("2026-10-01"),old.dateFrom);assertEquals(LocalDate.parse("2026-10-31"),old.dateTo)
        assertEquals(LocalDate.parse("2026-09-01"),old.compareFrom);assertEquals(LocalDate.parse("2026-09-30"),old.compareTo)
        val year=old.withPeriod(SpendingPeriod.YearToDate,day)
        assertEquals(LocalDate.parse("2025-01-01"),year.compareFrom);assertEquals(LocalDate.parse("2025-11-12"),year.compareTo)
        assertNull(year.withPeriod(SpendingPeriod.All,day).compareFrom)
    }
    @Test fun malformedFilterCannotBecomeTransport() {
        assertTrue(runCatching { InsightQuery(meal="dinner&token=x").path() }.isFailure)
        assertTrue(runCatching { InsightQuery(cityKey="台南").path() }.isFailure)
        assertTrue(runCatching { InsightQuery(dateFrom=LocalDate.now()).path() }.isFailure)
    }
    @Test fun manualAllowsMissingNameAndPhotoButRequiresIntegerMoney() {
        val body = manualBody("2026-11-12", "", "", "300", "advance", "", defaultInsightDocument())
        val json = jsonObject(body)
        assertEquals(300, json["amount_yuan"].asLong)
        assertTrue(json["description"].isJsonNull)
        assertFalse(json.has("photos"))
        assertEquals("advance", json.text("purpose"))
        assertTrue(runCatching { manualBody("2026-11-12", "", "", "3.5", "self", "", defaultInsightDocument()) }.isFailure)
    }
    @Test fun backendMetadataAndManualFixturesMatchNativeContract() {
        val metadata=javaClass.getResource("/insight-metadata-synthetic.json")!!.readText()
        val value=jsonObject(metadata)
        assertEquals(1L,insightLong(parseMetadata(metadata,value.text("ledger_id"),true)["revision"]))
        assertTrue(runCatching { parseMetadata(metadata,"a0f6956e-02c2-4428-9944-243571df60e4",true) }.isFailure)
        val manual=javaClass.getResource("/insight-manual-ledger-synthetic.json")!!.readText()
        val body=manualBody("2026-11-12","","","300","self","",defaultInsightDocument())
        assertEquals(jsonObject(manual).text("id"),validateManualResult(jsonObject(manual),body))
    }
    @Test fun actualHttpProjectionContractIncludesMealCountsAndScopedFilters() {
        val raw=javaClass.getResource("/insights-http-synthetic.json")!!.readText()
        val query=insightQueryFromJson(jsonObject(raw).getAsJsonObject("filters").toString())
        val report=parseInsightReport(raw,query)
        assertEquals(170L,report.summary.getValue("personal_yuan"));assertEquals(30L,report.summary.getValue("advance_yuan"));assertEquals(50L,report.comparisonDelta)
        assertEquals(10,report.groups.size)
        val dinner=report.groups.getValue("meals").first{it.key=="dinner"}
        assertEquals(1L,dinner.meals);assertEquals("120.0",dinner.average)
        val scopedRaw=javaClass.getResource("/insights-scoped-http-synthetic.json")!!.readText()
        val scopedQuery=insightQueryFromJson(jsonObject(scopedRaw).getAsJsonObject("filters").toString())
        assertEquals("food",scopedQuery.category);assertEquals("dinner",scopedQuery.meal);assertNotNull(scopedQuery.cityKey)
        assertEquals(120L,parseInsightReport(scopedRaw,scopedQuery).summary.getValue("personal_yuan"))
        assertTrue(runCatching{parseInsightReport(scopedRaw,scopedQuery.copy(meal="lunch"))}.isFailure)
    }
    @Test fun realSignedResidualRemainsVisibleAndCategorySumMatchesLedger() {
        val raw=javaClass.getResource("/insights-unallocated-http-synthetic.json")!!.readText()
        val query=insightQueryFromJson(jsonObject(raw).getAsJsonObject("filters").toString())
        val report=parseInsightReport(raw,query)
        assertEquals(80L,report.summary.getValue("personal_yuan"))
        val categories=report.groups.getValue("categories")
        assertEquals(100L,categories.first{it.key=="food"}.personal)
        assertEquals(-20L,categories.first{it.key=="unallocated"}.personal)
        assertEquals(80L,categories.sumOf{it.personal})
        assertEquals(-20L,report.coverage.getValue("unallocated_personal_yuan"))
        assertNull(report.groups.getValue("meals").first{it.key=="unallocated"}.average)
        val bad=jsonObject(raw).apply{getAsJsonObject("summary").addProperty("personal_yuan",81);getAsJsonObject("summary").addProperty("self_yuan",81)}
        assertTrue(runCatching{parseInsightReport(bad.toString(),query)}.isFailure)
    }
    @Test fun providedMerchantGroupsMustReconcileWithTheSummary() {
        val raw=javaClass.getResource("/insights-http-synthetic.json")!!.readText()
        val query=insightQueryFromJson(jsonObject(raw).getAsJsonObject("filters").toString())
        val inconsistent=jsonObject(raw).apply {
            getAsJsonObject("groups").getAsJsonArray("merchants")[0].asJsonObject.addProperty("personal_yuan",169)
        }
        assertTrue("Merchant graph must not contradict the same report total",runCatching{parseInsightReport(inconsistent.toString(),query)}.isFailure)
    }
    @Test fun negativeComparisonIsKeptAndWrongScopeRejected() {
        val q=InsightQuery(dateFrom=LocalDate.parse("2026-11-01"),dateTo=LocalDate.parse("2026-11-12"),compareFrom=LocalDate.parse("2026-10-01"),compareTo=LocalDate.parse("2026-10-12"))
        val raw=insightFixture(q)
        val report=parseInsightReport(raw,q)
        assertEquals(-50L,report.comparisonDelta)
        assertEquals("unknown",report.groups.getValue("categories").single().key)
        assertTrue(runCatching { parseInsightReport(raw.replace("active_confirmed_ledger","drafts"),q) }.isFailure)
        assertTrue(runCatching { parseInsightReport(raw,q.copy(meal="lunch")) }.isFailure)
    }
}

internal fun insightFixture(q:InsightQuery=InsightQuery()):String {
    val file=if(q.compareFrom==null)"/insights-unknown-http-synthetic.json"else"/insights-negative-comparison-http-synthetic.json"
    val value=jsonObject(MobileInsightTest::class.java.getResource(file)!!.readText())
    value.add("filters",q.filters())
    return value.toString()
}

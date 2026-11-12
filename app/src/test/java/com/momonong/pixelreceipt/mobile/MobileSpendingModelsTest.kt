package com.momonong.pixelreceipt.mobile

import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MobileSpendingModelsTest {
    private val query=SpendingQuery(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-31"),LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-30"))
    private fun fixture()=javaClass.getResource("/spending-response-synthetic.json")!!.readText()
    @Test fun realBackendProjectionParsesWithoutRecomputingLedgerValues(){
        val value=parseSpending(fixture(),query)
        assertEquals(170L,value.summary.personalYuan);assertEquals(1L,value.summary.receiptCount)
        assertEquals(70L,value.comparison!!.deltaPersonalYuan)
        assertEquals("合成例示商店",value.merchants.single().name)
        assertEquals(170L,value.series.single().personalYuan)
    }
    @Test fun lowerSpendingAndNegativeReconciliationDifferencesRemainSignedIntegers(){
        val lower=jsonObject(fixture())
        lower.getAsJsonObject("comparison").apply { addProperty("personal_yuan",200);addProperty("delta_personal_yuan",-30) }
        lower.getAsJsonObject("summary").apply { addProperty("printed_total_yuan",169);addProperty("difference_yuan",-1) }
        val report=parseSpending(lower.toString(),query)
        assertEquals(-30L,report.comparison!!.deltaPersonalYuan)
        assertEquals(-1L,report.summary.differenceYuan)
        for(value in listOf(JsonPrimitive("-30"),JsonPrimitive(-30.5),JsonPrimitive(java.math.BigInteger("-9223372036854775808")))){
            lower.getAsJsonObject("comparison").add("delta_personal_yuan",value)
            assertTrue(runCatching{parseSpending(lower.toString(),query)}.isFailure)
        }
        assertTrue(runCatching{strictLong(JsonPrimitive(-1),-Long.MAX_VALUE)}.isFailure)
    }
    @Test fun wrongScopeAndNonIntegerOrInconsistentAmountsNeverBecomeCharts(){
        for((key,value) in listOf("personal_yuan" to JsonPrimitive(171),"personal_yuan" to JsonPrimitive("170"),"advance_yuan" to JsonPrimitive(-1),"self_yuan" to JsonPrimitive(java.math.BigInteger("9223372036854775808")))){
            val bad=jsonObject(fixture());bad.getAsJsonObject("summary").add(key,value)
            assertTrue(key,runCatching{parseSpending(bad.toString(),query)}.isFailure)
        }
        val merchant=jsonObject(fixture());merchant.array("merchants")[0].asJsonObject.addProperty("personal_yuan",169)
        assertTrue(runCatching{parseSpending(merchant.toString(),query)}.isFailure)
        assertTrue(runCatching{parseSpending(fixture(),query.copy(dateFrom=LocalDate.parse("2026-11-01"),dateTo=LocalDate.parse("2026-11-30")))}.isFailure)
        assertTrue(runCatching{parseSpending(fixture().replace("active_confirmed_ledger","all_drafts"),query)}.isFailure)
    }
    @Test fun seriesAndShopCountsMustCoverTheFullSummary(){
        val bad=jsonObject(fixture());bad.getAsJsonArray("series").remove(0)
        assertTrue(runCatching{parseSpending(bad.toString(),query)}.isFailure)
        val repeated=jsonObject(fixture());repeated.array("merchants").add(repeated.array("merchants")[0].deepCopy())
        assertTrue(runCatching{parseSpending(repeated.toString(),query)}.isFailure)
    }
    @Test fun monthToDateAndYearToDateComparisonsRespectLeapAndMonthEnds(){
        val month=SpendingQuery.forPeriod(SpendingPeriod.MonthToDate,LocalDate.parse("2024-03-31"))
        assertEquals(LocalDate.parse("2024-03-01"),month.dateFrom)
        assertEquals(LocalDate.parse("2024-03-31"),month.dateTo)
        assertEquals(LocalDate.parse("2024-02-01"),month.compareFrom)
        assertEquals(LocalDate.parse("2024-02-29"),month.compareTo)
        val year=SpendingQuery.forPeriod(SpendingPeriod.YearToDate,LocalDate.parse("2024-02-29"))
        assertEquals(LocalDate.parse("2023-02-28"),year.compareTo)
        val all=SpendingQuery.forPeriod(SpendingPeriod.All,LocalDate.parse("2024-02-29"))
        assertNull(all.dateFrom);assertNull(all.compareFrom)
    }
    @Test fun nativeReadRequestsUseOnlyExistingOriginAndExactFilterParameters(){
        assertTrue(allowedMobileRequest(query.summaryPath(),"GET"))
        val key="a".repeat(64)
        val selection=SpendingSelection(merchantKey=key)
        val path=query.ledgerPath(selection,20)
        assertTrue(path.contains("merchant_key=$key"));assertTrue(path.contains("status=active"))
        assertTrue(allowedMobileRequest(path,"GET"));assertFalse(allowedMobileRequest(path,"POST"))
        for(path2 in listOf("v1/spending?date_from=2026-02-30","v1/spending?bucket=auto&bucket=day","v1/spending?x=https://elsewhere.invalid","v1/ledger?limit=20&offset=0&status=active&merchant_key=../escape"))assertFalse(path2,allowedMobileRequest(path2,"GET"))
        assertTrue(runCatching{query.ledgerPath(SpendingSelection(merchantKey="not-a-key"),0)}.isFailure)
    }
    @Test fun selectedDatesAreConstrainedToThePeriodAndUndatedCannotLeakIntoIt(){
        val selected=SpendingSelection(LocalDate.parse("2026-10-20"),LocalDate.parse("2026-10-20"))
        assertTrue(query.ledgerPath(selected,0).contains("date_from=2026-10-20&date_to=2026-10-20"))
        assertTrue(runCatching{query.ledgerPath(SpendingSelection(LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-02")),0)}.isFailure)
        assertTrue(runCatching{query.ledgerPath(SpendingSelection(undated=true),0)}.isFailure)
        assertTrue(SpendingQuery.forPeriod(SpendingPeriod.All,LocalDate.now()).ledgerPath(SpendingSelection(undated=true),0).contains("undated_only=true"))
    }
}

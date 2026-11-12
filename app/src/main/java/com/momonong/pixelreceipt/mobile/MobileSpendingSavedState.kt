package com.momonong.pixelreceipt.mobile

import androidx.lifecycle.SavedStateHandle
import java.time.LocalDate

/** Only small navigation values use SavedState; reports remain in the separate bounded cache. */
internal class MobileSpendingSavedState(private val saved: SavedStateHandle) {
    val period = runCatching { SpendingPeriod.valueOf(saved["mobileSpendingPeriod"] ?: "MonthToDate") }.getOrDefault(SpendingPeriod.MonthToDate)
    val query = runCatching {
        if (period == SpendingPeriod.Custom) SpendingQuery(LocalDate.parse(saved["mobileSpendingFrom"]), LocalDate.parse(saved["mobileSpendingTo"]))
        else SpendingQuery.forPeriod(period)
    }.getOrElse { SpendingQuery.forPeriod(SpendingPeriod.MonthToDate) }
    val selection = runCatching {
        SpendingSelection(saved.get<String>("mobileSpendingSelectedFrom")?.let(LocalDate::parse), saved.get<String>("mobileSpendingSelectedTo")?.let(LocalDate::parse),
            saved["mobileSpendingMerchant"], saved["mobileSpendingUndated"] ?: false).also { query.ledgerPath(it) }
    }.getOrDefault(SpendingSelection())
    fun save(period: SpendingPeriod, query: SpendingQuery, selection: SpendingSelection) {
        saved["mobileSpendingPeriod"] = period.name
        saved["mobileSpendingFrom"] = query.dateFrom?.toString(); saved["mobileSpendingTo"] = query.dateTo?.toString()
        saved["mobileSpendingSelectedFrom"] = selection.from?.toString(); saved["mobileSpendingSelectedTo"] = selection.to?.toString()
        saved["mobileSpendingMerchant"] = selection.merchantKey; saved["mobileSpendingUndated"] = selection.undated
    }
}

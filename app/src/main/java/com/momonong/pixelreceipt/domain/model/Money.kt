package com.momonong.pixelreceipt.domain.model

/**
 * Monetary value in the currency's smallest unit.
 *
 * Receipt calculations never use floating-point values. Currency is explicit so a future
 * exporter cannot accidentally combine values from different currencies.
 */
data class Money(
    val minorUnits: Long,
    val currencyCode: String = "TWD",
) {
    init {
        require(minorUnits >= 0) { "Money cannot be negative." }
        require(currencyCode.matches(Regex("[A-Z]{3}"))) {
            "Currency must be a three-letter ISO 4217 code."
        }
    }
}

package com.momonong.pixelreceipt.mobile

import org.junit.Assert.*
import org.junit.Test

class MobileAnalyticsApiTest {
    private val id="11111111-1111-4111-8111-111111111111"
    @Test fun newRoutesHaveExactMethodAndScope() {
        assertTrue(allowedMobileRequest("v1/insights?bucket=auto&undated_only=false", "GET"))
        assertTrue(allowedMobileRequest("v1/insights/entries?limit=20&offset=0&bucket=auto&category=food&meal=dinner&city_key=${"a".repeat(64)}", "GET"))
        assertTrue(allowedMobileRequest("v1/ledger/$id/insights", "PUT"))
        assertTrue(allowedMobileRequest("v1/manual-entries", "POST"))
        assertTrue(allowedMobileRequest("v1/analysis-operations/$id", "GET"))
        assertTrue(allowedMobileRequest("v1/insights?bucket=auto&merchant_key=${"a".repeat(64)}", "GET"))
        assertTrue(allowedMobileRequest("v1/products?query=%E8%8C%B6&limit=20&offset=0", "GET"))
        assertTrue(allowedMobileRequest("v1/products/$id/prices?limit=20&offset=0", "GET"))
        assertFalse(allowedMobileRequest("v1/insights?bucket=auto&token=secret", "GET"))
        assertFalse(allowedMobileRequest("v1/insights?bucket=auto&meal=dinner%26token=x", "GET"))
        assertFalse(allowedMobileRequest("v1/insights?bucket=auto&meal=lunch&meal=dinner", "GET"))
        assertFalse(allowedMobileRequest("v1/insights?bucket=auto&merchant_key=untrusted", "GET"))
        assertFalse(allowedMobileRequest("v1/products?query=x&limit=20&offset=0&redirect=https://outside.invalid", "GET"))
        assertFalse(allowedMobileRequest("v1/products?query=%0A&limit=20&offset=0", "GET"))
        assertFalse(allowedMobileRequest("v1/ledger/$id/insights", "POST"))
        assertFalse(allowedMobileRequest("v1/manual-entries", "GET"))
    }
}

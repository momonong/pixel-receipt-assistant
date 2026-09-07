package com.momonong.pixelreceipt.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test
    fun `maps all official width breakpoints`() {
        assertEquals(AdaptiveLayoutMode.Compact, AdaptiveLayoutPolicy.modeFor(599))
        assertEquals(AdaptiveLayoutMode.Medium, AdaptiveLayoutPolicy.modeFor(600))
        assertEquals(AdaptiveLayoutMode.Expanded, AdaptiveLayoutPolicy.modeFor(840))
        assertEquals(AdaptiveLayoutMode.Large, AdaptiveLayoutPolicy.modeFor(1_200))
        assertEquals(AdaptiveLayoutMode.ExtraLarge, AdaptiveLayoutPolicy.modeFor(1_600))
    }
}

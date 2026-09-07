package com.momonong.pixelreceipt.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeContractTest {
    @Test
    fun `home state requires a non-empty layer list`() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeUiState(layers = emptyList(), selectedLayerId = "missing")
        }
    }

    @Test
    fun `view model ignores an unknown layer`() {
        val viewModel = HomeViewModel()
        val initialSelection = viewModel.uiState.value.selectedLayerId

        viewModel.selectLayer("missing")

        assertEquals(initialSelection, viewModel.uiState.value.selectedLayerId)
    }

    @Test
    fun `view model selects a known layer`() {
        val viewModel = HomeViewModel()

        viewModel.selectLayer("domain")

        assertEquals("domain", viewModel.uiState.value.selectedLayerId)
    }
}

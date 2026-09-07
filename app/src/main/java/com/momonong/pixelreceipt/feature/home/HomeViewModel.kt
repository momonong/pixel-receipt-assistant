package com.momonong.pixelreceipt.feature.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class HomeViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(HomeUiState())
    val uiState = mutableUiState.asStateFlow()

    fun selectLayer(layerId: String) {
        mutableUiState.update { current ->
            if (current.layers.none { it.id == layerId }) current
            else current.copy(selectedLayerId = layerId)
        }
    }
}

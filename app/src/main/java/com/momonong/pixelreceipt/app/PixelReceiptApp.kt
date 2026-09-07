package com.momonong.pixelreceipt.app

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.momonong.pixelreceipt.feature.home.AdaptiveLayoutPolicy
import com.momonong.pixelreceipt.feature.home.HomeScreen
import com.momonong.pixelreceipt.feature.home.HomeViewModel

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun PixelReceiptApp(viewModel: HomeViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val layoutMode = AdaptiveLayoutPolicy.modeFor(
        minWidthDp = adaptiveInfo.windowSizeClass.minWidthDp,
    )

    HomeScreen(
        uiState = uiState,
        layoutMode = layoutMode,
        onLayerSelected = viewModel::selectLayer,
    )
}

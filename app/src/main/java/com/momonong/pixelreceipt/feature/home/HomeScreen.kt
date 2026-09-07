package com.momonong.pixelreceipt.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableSupportingPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberSupportingPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.momonong.pixelreceipt.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    layoutMode: AdaptiveLayoutMode,
    onLayerSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navigator = rememberSupportingPaneScaffoldNavigator()
    val coroutineScope = rememberCoroutineScope()
    val supportingPaneIsHidden =
        navigator.scaffoldValue[SupportingPaneScaffoldRole.Supporting] == PaneAdaptedValue.Hidden

    NavigableSupportingPaneScaffold(
        navigator = navigator,
        modifier = modifier.fillMaxSize(),
        mainPane = {
            AnimatedPane(modifier = Modifier.safeContentPadding()) {
                MainPane(
                    uiState = uiState,
                    layoutMode = layoutMode,
                    onLayerSelected = { layerId ->
                        onLayerSelected(layerId)
                        if (supportingPaneIsHidden) {
                            coroutineScope.launch {
                                navigator.navigateTo(SupportingPaneScaffoldRole.Supporting)
                            }
                        }
                    },
                )
            }
        },
        supportingPane = {
            AnimatedPane(modifier = Modifier.safeContentPadding()) {
                DetailPane(
                    layer = uiState.selectedLayer,
                    layoutMode = layoutMode,
                )
            }
        },
    )
}

@Composable
private fun MainPane(
    uiState: HomeUiState,
    layoutMode: AdaptiveLayoutMode,
    onLayerSelected: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.home_subtitle),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(
                        R.string.home_window_mode,
                        stringResource(layoutMode.labelRes()),
                    ),
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            items(uiState.layers, key = ArchitectureLayer::id) { layer ->
                ArchitectureLayerCard(
                    layer = layer,
                    selected = layer.id == uiState.selectedLayerId,
                    onClick = { onLayerSelected(layer.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchitectureLayerCard(
    layer: ArchitectureLayer,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(layer.titleRes),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(layer.status.labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(layer.summaryRes),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DetailPane(
    layer: ArchitectureLayer,
    layoutMode: AdaptiveLayoutMode,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    text = stringResource(layer.titleRes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                Text(
                    text = stringResource(layer.summaryRes),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            items(layer.detailResIds) { detailRes ->
                Text(
                    text = stringResource(R.string.bullet_item, stringResource(detailRes)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Text(
                    text = stringResource(
                        R.string.detail_layout_mode,
                        stringResource(layoutMode.labelRes()),
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun AdaptiveLayoutMode.labelRes(): Int = when (this) {
    AdaptiveLayoutMode.Compact -> R.string.layout_compact
    AdaptiveLayoutMode.Medium -> R.string.layout_medium
    AdaptiveLayoutMode.Expanded -> R.string.layout_expanded
    AdaptiveLayoutMode.Large -> R.string.layout_large
    AdaptiveLayoutMode.ExtraLarge -> R.string.layout_extra_large
}

package com.momonong.pixelreceipt.feature.home

import androidx.annotation.StringRes
import com.momonong.pixelreceipt.R

data class HomeUiState(
    val layers: List<ArchitectureLayer> = defaultArchitectureLayers,
    val selectedLayerId: String = defaultArchitectureLayers.first().id,
) {
    init {
        require(layers.isNotEmpty()) { "Home requires at least one architecture layer." }
    }

    val selectedLayer: ArchitectureLayer
        get() = layers.firstOrNull { it.id == selectedLayerId } ?: layers.first()
}

data class ArchitectureLayer(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    val detailResIds: List<Int>,
    val status: LayerStatus,
)

enum class LayerStatus(@param:StringRes val labelRes: Int) {
    Ready(R.string.status_ready),
    BoundaryReady(R.string.status_boundary_ready),
    Next(R.string.status_next),
}

private val defaultArchitectureLayers = listOf(
    ArchitectureLayer(
        id = "ui",
        titleRes = R.string.layer_ui_title,
        summaryRes = R.string.layer_ui_summary,
        detailResIds = listOf(
            R.string.layer_ui_detail_single_activity,
            R.string.layer_ui_detail_supporting_pane,
            R.string.layer_ui_detail_window_size,
            R.string.layer_ui_detail_edge_to_edge,
        ),
        status = LayerStatus.Ready,
    ),
    ArchitectureLayer(
        id = "domain",
        titleRes = R.string.layer_domain_title,
        summaryRes = R.string.layer_domain_summary,
        detailResIds = listOf(
            R.string.layer_domain_detail_money,
            R.string.layer_domain_detail_untrusted_ai,
            R.string.layer_domain_detail_tests,
            R.string.layer_domain_detail_independent,
        ),
        status = LayerStatus.Ready,
    ),
    ArchitectureLayer(
        id = "data",
        titleRes = R.string.layer_data_title,
        summaryRes = R.string.layer_data_summary,
        detailResIds = listOf(
            R.string.layer_data_detail_save_first,
            R.string.layer_data_detail_room_flow,
            R.string.layer_data_detail_unique_work,
            R.string.layer_data_detail_export_sink,
        ),
        status = LayerStatus.BoundaryReady,
    ),
    ArchitectureLayer(
        id = "integrations",
        titleRes = R.string.layer_integrations_title,
        summaryRes = R.string.layer_integrations_summary,
        detailResIds = listOf(
            R.string.layer_integrations_detail_no_api_key,
            R.string.layer_integrations_detail_app_check,
            R.string.layer_integrations_detail_oauth,
            R.string.layer_integrations_detail_remote_config,
        ),
        status = LayerStatus.Next,
    ),
)

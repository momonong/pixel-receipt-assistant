package com.momonong.pixelreceipt.feature.inbox

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.domain.model.EvidenceAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PhotoChoices(assets: List<EvidenceAsset>, selected: String?, choose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        assets.forEachIndexed { index, asset ->
            FilterChip(asset.id == selected, { choose(asset.id) }, { Text("照片 ${index + 1}") })
        }
    }
}

@Composable
internal fun EvidencePhoto(hash: String, images: ImageStore, modifier: Modifier, zoomable: Boolean = false) {
    var bitmap by remember(hash) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(hash) { mutableStateOf(false) }
    var scale by remember(hash) { mutableFloatStateOf(1f) }
    var offset by remember(hash) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(hash) {
        try { bitmap = withContext(Dispatchers.IO) { images.preview(hash)?.asImageBitmap() }; failed = bitmap == null }
        catch (error: Exception) { if (error is CancellationException) throw error; failed = true }
    }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it, "這筆消費的明細照片", Modifier.fillMaxSize()
                .then(if (zoomable) Modifier.pointerInput(hash) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale == 1f) Offset.Zero else Offset(
                            (offset.x + pan.x).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2),
                            (offset.y + pan.y).coerceIn(-size.height * (scale - 1) / 2, size.height * (scale - 1) / 2))
                    }
                } else Modifier)
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                contentScale = ContentScale.Fit)
        } ?: Text(if (failed) "照片無法讀取，請檢查裝置儲存空間。" else "載入照片…")
    }
}

@Composable
internal fun PhotoPreviewDialog(hash: String, images: ImageStore, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Text("對照這筆消費的照片", style = MaterialTheme.typography.titleLarge)
                Text("雙指放大、拖曳查看。返回後繼續原本的品項。")
                EvidencePhoto(hash, images, Modifier.weight(1f).fillMaxWidth(), zoomable = true)
                Button(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("返回填寫") }
            }
        }
    }
}

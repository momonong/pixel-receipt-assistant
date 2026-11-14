package com.momonong.pixelreceipt.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.momonong.pixelreceipt.data.ingestion.ImageStore

@Composable
internal fun GroupedReceiptCard(child: MobileBatch, images: ImageStore, state: MobileUiState, actions: MobileActions) {
    val info = child.receiptInfo ?: return
    val first = info.regions.firstOrNull()
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()
        .clickable(enabled = state.busy == null) { actions.openReceipt(child.id) }.testTag("group-receipt-${child.id}")) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ReceiptRegionThumb(first?.let { child.images.getOrNull(it.imagePosition - 1) }, first, info.label, images, Modifier.width(72.dp).height(100.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(info.label, style = MaterialTheme.typography.titleSmall)
                StatusTag(receiptStatus(child, state.mutations).label)
                Text(receiptAmountText(child), style = MaterialTheme.typography.titleMedium)
                Text(receiptPurposeText(child), style = MaterialTheme.typography.bodyMedium, color = MobileTealInk)
                info.regions.forEach { region ->
                    Text("原圖 ${region.imagePosition} · ${regionLocation(region)}", color = MobileMuted, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (child.deleted) "查看保留紀錄" else if (child.draftId == null) "查看辨識進度" else "開啟這張收據", color = MobileTealInk, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

internal fun regionLocation(region: ReceiptRegion): String {
    val b = region.box
    val location = when {
        b == listOf(0, 0, 1000, 1000) -> "整張照片"
        b[2] <= 550 -> "左側"
        b[0] >= 450 -> "右側"
        b[3] <= 550 -> "上方"
        b[1] >= 450 -> "下方"
        else -> "中間區域"
    }
    return location
}


/** Derive an in-memory thumbnail from the oriented preview; never modify or persist an original. */
@Composable
private fun ReceiptRegionThumb(image: MobileImage?, region: ReceiptRegion?, label: String, images: ImageStore, modifier: Modifier) {
    val revision by images.changes.collectAsState()
    var bitmap by remember(image?.hash, region, revision) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(image?.hash, region, revision) { mutableStateOf(false) }
    LaunchedEffect(image?.hash, region, revision) {
        try {
            bitmap = withContext(Dispatchers.IO) {
                if (image == null || region == null) null else images.preview(image.hash)?.let { source -> regionThumbnail(source, region).asImageBitmap() }
            }
            failed = bitmap == null
        } catch (error: Exception) { if (error is CancellationException) throw error; failed = true }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MobileGround), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, "$label，收據所在區域", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Text(if (failed) "原圖待讀取" else "載入中", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
    }
}

internal fun regionThumbnail(source: Bitmap, region: ReceiptRegion): Bitmap {
    val b = region.box
    val left = (source.width.toLong() * b[0] / 1000).toInt().coerceIn(0, source.width - 1)
    val top = (source.height.toLong() * b[1] / 1000).toInt().coerceIn(0, source.height - 1)
    val right = ((source.width.toLong() * b[2] + 999) / 1000).toInt().coerceIn(left + 1, source.width)
    val bottom = ((source.height.toLong() * b[3] + 999) / 1000).toInt().coerceIn(top + 1, source.height)
    return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
}

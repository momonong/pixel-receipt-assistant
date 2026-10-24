package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val MobileGround = Color(0xFFF3F5F7)
internal val MobileInk = Color(0xFF1B2928)
internal val MobileMuted = Color(0xFF5C6B69)
internal val MobileRule = Color(0xFFE5EBE9)
internal val MobileTeal = Color(0xFF17675F)
internal val MobileTealSoft = Color(0xFFE2F1ED)
internal val MobileTealInk = Color(0xFF164D47)
internal val MobileWarning = Color(0xFF80501E)
internal val MobileWarningSoft = Color(0xFFFFF3DE)

// System fonts preserve Traditional Chinese coverage and Android font scaling.
internal val MobileTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
)

/** A native receipt mark, derived from the product's paper rather than invented transaction data. */
@Composable
internal fun ReceiptMark(modifier: Modifier = Modifier, color: Color = MobileTeal) {
    Canvas(modifier.size(30.dp)) {
        val sx = size.width / 32f
        val sy = size.height / 36f
        val paper = Path().apply {
            moveTo(7f * sx, 3f * sy); lineTo(25f * sx, 3f * sy)
            lineTo(25f * sx, 32f * sy); lineTo(22f * sx, 30f * sy)
            lineTo(19f * sx, 32f * sy); lineTo(16f * sx, 30f * sy)
            lineTo(13f * sx, 32f * sy); lineTo(10f * sx, 30f * sy)
            lineTo(7f * sx, 32f * sy); close()
        }
        drawPath(paper, color, style = Stroke(1.6.dp.toPx()))
        listOf(21f, 21f, 17f).forEachIndexed { index, end ->
            val y = (10f + index * 6f) * sy
            drawLine(color, Offset(11f * sx, y), Offset(end * sx, y), 1.6.dp.toPx(), StrokeCap.Round)
        }
    }
}

internal fun mobileStatus(batch: MobileBatch): String = when {
    batch.deleted -> "已刪除"
    !batch.importComplete -> "待補照片"
    batch.ledgerStatus == "voided" || batch.draftStatus == "voided" -> "已作廢"
    batch.ledgerStatus == "active" || batch.draftStatus == "confirmed" -> "已完成"
    batch.status == "succeeded" -> if (batch.purpose == "reference") "成果待查看" else "待核對"
    batch.status in setOf("queued", "preparing", "submitting", "processing") -> "中央處理中"
    batch.status == "unknown" -> "結果待查詢"
    batch.status == "failed" -> "辨識未完成"
    batch.status == "unavailable" -> "來源未就緒"
    batch.frozen != null -> "等待同步"
    else -> "尚未送出"
}

internal fun mobileNextStep(batch: MobileBatch): String = when {
    batch.deleted -> "返回收件匣"
    !batch.importComplete -> "補齊照片"
    batch.ledgerStatus == "voided" || batch.draftStatus == "voided" -> "查看作廢紀錄"
    batch.ledgerStatus == "active" || batch.draftStatus == "confirmed" -> "查看已完成帳目"
    batch.status == "succeeded" -> if (batch.purpose == "reference") "查看參考成果" else "前往帳本核對"
    batch.intakeId != null -> "查詢原工作進度"
    batch.frozen != null -> "繼續同步原請求"
    else -> "編輯照片與說明"
}

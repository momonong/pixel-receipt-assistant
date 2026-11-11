package com.momonong.pixelreceipt.mobile

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeParseException

private fun reviewDate(value: String): LocalDate? = try {
    LocalDate.parse(value)
} catch (_: DateTimeParseException) {
    null
}

internal fun reviewDateLabel(value: String, today: LocalDate = LocalDate.now()): String {
    val date = reviewDate(value) ?: return if (value.isBlank()) "日期待確認" else "日期格式需修正"
    return (if (date == today) "今天 · " else "") + "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
}

/** Only a confirmed picker selection or shortcut writes an ISO date to the editor. */
@Composable
internal fun MobileReviewDateField(value: String, error: String?, enabled: Boolean, change: (String) -> Unit) {
    val context = LocalContext.current
    val currentChange by rememberUpdatedState(change)
    val currentEnabled by rememberUpdatedState(enabled)
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("消費日期", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
        OutlinedButton(
            onClick = { open = true }, enabled = enabled, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review-date")
                .semantics { contentDescription = "選擇消費日期，${reviewDateLabel(value)}" },
        ) { Text(reviewDateLabel(value), style = MaterialTheme.typography.bodyLarge) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { change(LocalDate.now().toString()) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("review-date-today")) { Text("今天") }
            TextButton(onClick = { change(LocalDate.now().minusDays(1).toString()) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("review-date-yesterday")) { Text("昨天") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    DisposableEffect(open, enabled, context) {
        val dialog = if (open && enabled) {
            val initial = reviewDate(value) ?: LocalDate.now()
            DatePickerDialog(context, { _, year, month, day ->
                if (currentEnabled) currentChange(LocalDate.of(year, month + 1, day).toString())
                open = false
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
                setTitle("消費日期")
                setButton(DatePickerDialog.BUTTON_POSITIVE, "確定", this)
                setButton(DatePickerDialog.BUTTON_NEGATIVE, "取消", this)
                setOnDismissListener { open = false }
                show()
            }
        } else null
        onDispose { dialog?.dismiss() }
    }
}

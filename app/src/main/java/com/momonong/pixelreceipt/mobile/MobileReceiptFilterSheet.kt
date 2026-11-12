package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceiptFilterSheet(current: ReceiptFilter, statuses: List<ReceiptStatus>, dismiss: () -> Unit, apply: (ReceiptFilter) -> Unit) {
    var selected by remember(current.key) { mutableStateOf(current.key) }
    val pending = restoreReceiptFilter(selected)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MobileGround) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("收據篩選", style = MaterialTheme.typography.titleLarge)
                    Text("選擇要看的收據", color = MobileMuted, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { selected = "normal" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重設") }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("receipt-filter-list"), contentPadding = PaddingValues(12.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(listOf(ReceiptFilter(), ReceiptFilter("attention"), ReceiptFilter("all")), key = { it.key }) { filter ->
                    FilterChoice(filter.key, filter.label, statuses.count(filter::includes), selected == filter.key, null) { selected = filter.key }
                }
                item { Text("依狀態查看", Modifier.padding(horizontal = 12.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge, color = MobileMuted) }
                items(ReceiptStatus.entries.filter { it in statuses || it.key == selected }, key = { it.key }) { status ->
                    FilterChoice("status-${status.key}", status.label, statuses.count { it == status }, selected == status.key, status.colors) { selected = status.key }
                }
            }
            HorizontalDivider(color = MobileRule)
            Button(onClick = { apply(pending) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp).testTag("receipt-filter-apply")) {
                Text("顯示 ${statuses.count(pending::includes)} 筆收據", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun FilterChoice(tag: String, label: String, count: Int, selected: Boolean, colors: ReceiptColors?, choose: () -> Unit) {
    Surface(color = if (selected) MobileTealSoft else MaterialTheme.colorScheme.surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) {
        Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = choose).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 4.dp).testTag("receipt-filter-$tag"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(selected = selected, onClick = null)
            Box(Modifier.weight(1f)) {
                if (colors == null) Text(label, style = MaterialTheme.typography.bodyLarge)
                else StatusTag(label)
            }
            Text("$count 筆", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
        }
    }
}

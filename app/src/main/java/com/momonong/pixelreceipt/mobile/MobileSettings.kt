package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.momonong.pixelreceipt.BuildConfig

@Composable
internal fun MobileSettings(state: MobileUiState, password: String, changePassword: (String) -> Unit, actions: MobileActions, modifier: Modifier) {
    LazyColumn(modifier.testTag("mobile-settings-page"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("帳號與辨識來源", style = MaterialTheme.typography.headlineSmall)
                Text("登入後可在 App 內完成核對與入帳。", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
            }
        }
        state.message?.let { item { MobileNotice(it) } }
        item {
            SettingsSection("中央帳本", "照片先整理；登入後才傳送。") {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("裝置登入", style = MaterialTheme.typography.titleSmall)
                    StatusTag(if (state.signedIn && state.reviewer) "可核對與入帳" else if (state.signedIn) "請重新登入啟用核對" else "此裝置尚未登入", problem = !state.signedIn)
                }
                Text("核對、用途分配與確認入帳，都在 App 內完成。", color = MobileMuted, style = MaterialTheme.typography.bodyMedium)
                if (!state.signedIn || !state.reviewer) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("帳本密碼", style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(password, changePassword, modifier = Modifier.fillMaxWidth().testTag("mobile-password"), singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            enabled = state.busy == null, isError = password.length > 1024, shape = RoundedCornerShape(10.dp),
                            textStyle = MaterialTheme.typography.bodyLarge)
                        Text("密碼僅用於本次登入，按登入後清空。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                    }
                } else {
                    HorizontalDivider(color = MobileRule)
                    TextButton(onClick = actions.logout, enabled = state.busy == null, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-logout")) { Text("登出此裝置") }
                }
            }
        }
        item {
            SettingsSection("辨識來源", "可用狀態由中央帳本回報。") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (state.batch == null) "預設" else "本次"}來源", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = actions.refresh, enabled = state.signedIn && state.busy == null, modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-refresh-engines")) { Text("更新狀態") }
                }
                val batch = state.batch
                val editable = batch?.editable == true && state.busy == null && state.writable
                EngineRow("API Quota Broker", availabilityLabel(state.engines["broker"]), (batch?.engine ?: "broker") == "broker", editable) {
                    batch?.let { value -> actions.edit(value.id) { it.copy(engine = "broker") } }
                }
                HorizontalDivider(color = MobileRule)
                EngineRow("RTX 4090", availabilityLabel(state.engines["local"]), batch?.engine == "local", editable && state.engines["local"]?.available == true) {
                    batch?.let { value -> actions.edit(value.id) { it.copy(engine = "local") } }
                }
                Text(when {
                    batch == null -> "新增收件預設使用 Broker。要更改來源，請先開啟該筆收件。"
                    !batch.editable -> "這筆收件已鎖定原來源；更新狀態不會換來源或重跑辨識。"
                    else -> "目前選擇只適用這筆收件。4090 必須先由中央確認可用；不會自動替換來源。"
                }, style = MaterialTheme.typography.bodySmall, color = MobileMuted)
            }
        }
        item {
            SettingsSection("手機上的帳本", "本機與中央帳本分開保存。") {
                Text("本機帳本（此 App）", style = MaterialTheme.typography.titleSmall)
                Text("此 App 的本機帳本與中央帳本分開保存，不會自動匯入或上傳資料。", color = MobileMuted, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = actions.legacy, enabled = state.busy == null, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("legacy-ledger-entry")) { Text("開啟此 App 的本機帳本 ›") }
            }
        }
        item { Text("PixelReceipt 收件匣 ${BuildConfig.VERSION_NAME} · 核對、入帳與帳本", style = MaterialTheme.typography.bodySmall, color = MobileMuted) }
    }
}

@Composable
private fun SettingsSection(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints {
        if (maxWidth >= 680.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(176.dp).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                }
                Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                }
            }
        }
    }
}

@Composable
private fun EngineRow(name: String, detail: String, selected: Boolean, enabled: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = choose),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MobileMuted)
        }
        RadioButton(selected, onClick = null, enabled = enabled)
    }
}

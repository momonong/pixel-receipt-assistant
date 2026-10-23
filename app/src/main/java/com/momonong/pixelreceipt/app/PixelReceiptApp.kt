package com.momonong.pixelreceipt.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momonong.pixelreceipt.feature.inbox.InboxScreen
import com.momonong.pixelreceipt.feature.inbox.InboxViewModel
import com.momonong.pixelreceipt.mobile.MobileScreen
import com.momonong.pixelreceipt.mobile.MobileViewModel

@Composable
fun PixelReceiptApp(viewModel: InboxViewModel, mobile: MobileViewModel) {
    val legacy by mobile.legacy.collectAsStateWithLifecycle()
    if (!legacy) MobileScreen(mobile)
    else Column(Modifier.fillMaxSize()) {
        BackHandler { mobile.openLegacy(false) }
        TextButton(onClick = { mobile.openLegacy(false) }, modifier = Modifier.statusBarsPadding()) { Text("‹ 返回中央設定") }
        Box(Modifier.weight(1f)) { InboxScreen(viewModel) }
    }
}

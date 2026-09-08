package com.momonong.pixelreceipt.app

import androidx.compose.runtime.Composable
import com.momonong.pixelreceipt.feature.inbox.InboxScreen
import com.momonong.pixelreceipt.feature.inbox.InboxViewModel

@Composable
fun PixelReceiptApp(viewModel: InboxViewModel) = InboxScreen(viewModel)

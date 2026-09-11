package com.momonong.pixelreceipt

import android.os.Bundle
import android.content.Intent
import androidx.activity.viewModels
import com.momonong.pixelreceipt.data.ingestion.SharedImages
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.momonong.pixelreceipt.app.PixelReceiptApp
import com.momonong.pixelreceipt.core.ui.theme.PixelReceiptTheme
import com.momonong.pixelreceipt.feature.inbox.InboxViewModel
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val inbox: InboxViewModel by viewModels()
    private var shareOperationId = UUID.randomUUID().toString()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shareOperationId = savedInstanceState?.getString("shareOperationId") ?: shareOperationId
        receive(intent)
        setContent {
            PixelReceiptTheme {
                PixelReceiptApp(inbox)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("shareOperationId", shareOperationId)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        (application as com.momonong.pixelreceipt.app.ReceiptApplication).isReceiptActivityResumed = true
        inbox.extraction.foreground(true)
    }
    override fun onPause() {
        (application as com.momonong.pixelreceipt.app.ReceiptApplication).isReceiptActivityResumed = false
        inbox.extraction.foreground(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareOperationId = UUID.randomUUID().toString()
        receive(intent)
    }

    private fun receive(intent: Intent) {
        try {
            val uris = SharedImages.from(intent) ?: return
            if (uris.isEmpty()) inbox.showError("分享內容沒有可讀取的圖片，請從相簿重新分享。")
            else inbox.shared(shareOperationId, uris)
        } catch (_: RuntimeException) {
            inbox.showError("無法讀取分享內容，請重新選取圖片。")
        }
    }
}

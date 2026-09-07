package com.momonong.pixelreceipt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.momonong.pixelreceipt.app.PixelReceiptApp
import com.momonong.pixelreceipt.core.ui.theme.PixelReceiptTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PixelReceiptTheme {
                PixelReceiptApp()
            }
        }
    }
}

package com.momonong.pixelreceipt.app

import android.app.Application
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.data.ingestion.ReceiptImporter
import com.momonong.pixelreceipt.data.local.ReceiptDatabase
import com.momonong.pixelreceipt.data.local.RoomReceiptRepository
import java.io.File

class ReceiptApplication : Application() {
    val database by lazy { ReceiptDatabase.open(this) }
    val repository by lazy { RoomReceiptRepository(database) }
    val images by lazy { ImageStore(File(filesDir, "evidence")) }
    val importer by lazy { ReceiptImporter(database, repository, images) }
}

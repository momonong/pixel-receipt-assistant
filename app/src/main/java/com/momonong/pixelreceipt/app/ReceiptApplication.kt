package com.momonong.pixelreceipt.app

import android.app.Application
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.data.ingestion.ReceiptImporter
import com.momonong.pixelreceipt.data.local.ReceiptDatabase
import com.momonong.pixelreceipt.data.local.RoomReceiptRepository
import java.io.File
import com.momonong.pixelreceipt.data.extraction.MlKitReceiptAnalyzer
import com.momonong.pixelreceipt.domain.usecase.ExtractReceipt
import kotlinx.coroutines.flow.first

class ReceiptApplication : Application() {
    val database by lazy { ReceiptDatabase.open(this) }
    val repository by lazy { RoomReceiptRepository(database) }
    val images by lazy { ImageStore(File(filesDir, "evidence")) }
    val importer by lazy { ReceiptImporter(database, repository, images) }
    private val analyzer by lazy { MlKitReceiptAnalyzer(images) }
    val extraction by lazy { ExtractReceipt(repository, analyzer, { repository.evidence(it).first() }, analyzer::verifyImage) }
}

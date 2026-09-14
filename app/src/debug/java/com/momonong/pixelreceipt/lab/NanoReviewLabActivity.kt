package com.momonong.pixelreceipt.lab

import android.os.Bundle
import android.util.Base64
import android.util.Base64InputStream
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.momonong.pixelreceipt.R
import com.momonong.pixelreceipt.core.ui.theme.PixelReceiptTheme
import com.momonong.pixelreceipt.data.extraction.NanoReceiptOutput
import com.momonong.pixelreceipt.data.extraction.NanoReceiptValidation
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.data.local.*
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.DraftWriteResult
import com.momonong.pixelreceipt.domain.usecase.MapReceiptRecognition
import com.momonong.pixelreceipt.feature.inbox.ReviewScreen
import com.momonong.pixelreceipt.feature.inbox.ReviewSession
import com.momonong.pixelreceipt.feature.inbox.PhotoPreviewDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Replays a successful local Nano observation into the production review UI, never the user DB. */
class NanoReviewLabActivity : ComponentActivity() {
    private var database: ReceiptDatabase? = null
    private val stateModel: NanoReviewStateModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val status = TextView(this).apply { setText(R.string.nano_review_loading) }
        setContentView(status)
        val identity = intent.getStringExtra("job") ?: return
        if (!identity.matches(Regex("[a-f0-9]{32}"))) return
        lifecycleScope.launch {
            try {
                val root = File(filesDir, "nano-lab/$identity")
                val result = withContext(Dispatchers.IO) {
                    val file = File(root, "result.json")
                    require(file.isFile && file.length() in 1..2_000_000)
                    JsonParser.parseString(file.readText()).asJsonObject
                }
                require(result["jobId"].asString == identity && result["status"].asString == "succeeded")
                require(result["engine"].asString in setOf("nano-image", "nano-ocr"))
                val hash = result.getAsJsonArray("inputHashes").single().asString
                require(hash.matches(Regex("[a-f0-9]{64}")))
                val images = ImageStore(File(root, "review-images"))
                val asset = withContext(Dispatchers.IO) {
                    val stored = images.copy({ Base64InputStream(File(root, "0.image.b64").inputStream(), Base64.DEFAULT) }, ImageStore.MAX_BYTES)
                    require(stored.hash == hash)
                    EvidenceAsset("image-0", contentSha256 = hash, mimeType = stored.mime,
                        byteSize = stored.bytes, widthPx = stored.width, heightPx = stored.height,
                        importSource = EvidenceImportSource.Other, importedAtEpochMillis = System.currentTimeMillis())
                }
                val db = Room.databaseBuilder(applicationContext, ReceiptDatabase::class.java,
                    File(root, "review.db").absolutePath).build()
                database = db
                val repository = RoomReceiptRepository(db)
                db.withTransaction {
                    if (db.receipts().draft(identity) == null) {
                        val audit = result.getAsJsonObject("recognition")["unlocalizedObservationsJson"].asString
                        require(audit.length <= 100_000)
                        val output = Gson().fromJson(audit, Array<NanoReceiptOutput>::class.java).single()
                        val recognition = NanoReceiptValidation.recognition(output, asset.widthPx, asset.heightPx)
                            .copy(unlocalizedObservationsJson = audit)
                        val descriptor = result.getAsJsonObject("descriptor")
                        val draft = MapReceiptRecognition().map(ReceiptDraft(identity, evidenceAssetIds = listOf(asset.id)),
                            listOf(asset), recognition, ExtractionProvenance(identity, descriptor["analyzerId"].asString,
                                descriptor["modelId"].asString, descriptor["schemaVersion"].asString,
                                ExtractionRuntime.OnDevice, System.currentTimeMillis(), descriptor["promptVersion"].asString))
                        db.receipts().insertBlob(BlobRow(hash, asset.byteSize))
                        db.receipts().insertEvidence(EvidenceRow(asset.id, hash, repository.codec.encodeAsset(asset)))
                        check(repository.createDraft(draft) is DraftWriteResult.Written)
                    }
                }
                // This isolated Room file survives Activity recreation; no production graph is accessed.
                val session = ReviewSession(repository, stateModel.saved, lifecycleScope)
                require(session.state.value.base?.id?.let { it == identity } != false)
                session.open(identity)
                setContent {
                    PixelReceiptTheme {
                        val state by session.state.collectAsState()
                        var preview by remember { mutableStateOf<String?>(null) }
                        val expanded = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() >= 840.dp }
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Text(getString(R.string.nano_review_notice))
                            if (state.base != null) ReviewScreen(session, state, listOf(asset), images, expanded,
                                preview = { preview = it }, notice = getString(R.string.nano_review_notice))
                            else TextButton(onClick = { session.open(identity) }) { Text(getString(R.string.nano_review_reopen)) }
                        }
                        preview?.let { hash -> PhotoPreviewDialog(hash, images) { preview = null } }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { status.text = getString(R.string.nano_review_error, error.message.orEmpty()) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        database?.close()
    }

}

class NanoReviewStateModel(val saved: SavedStateHandle) : ViewModel()

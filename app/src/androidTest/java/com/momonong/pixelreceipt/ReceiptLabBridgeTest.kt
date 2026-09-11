package com.momonong.pixelreceipt

import android.os.Build
import android.util.Base64
import android.util.Base64InputStream
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.momonong.pixelreceipt.data.extraction.MlKitReceiptAnalyzer
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.domain.ai.*
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.*
import com.momonong.pixelreceipt.domain.usecase.MapReceiptRecognition
import com.momonong.pixelreceipt.domain.usecase.inputText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Test APK only. No Activity, Room database, saved receipt, or phone-photo enumeration. */
@RunWith(AndroidJUnit4::class)
class ReceiptLabBridgeTest {
    @Test fun analyzeUploadedCase() = runBlocking {
        val job = InstrumentationRegistry.getArguments().getString("receiptLabJob")
        assumeTrue("Only invoked by the receipt lab", job != null)
        require(requireNotNull(job).matches(Regex("[a-f0-9]{32}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "receipt-lab/$job")
        val cache = File(context.cacheDir, "receipt-lab/$job")
        val result = linkedMapOf<String, Any?>("schemaVersion" to 1, "jobId" to job,
            "engine" to "mlkit", "device" to Build.MODEL, "androidApi" to Build.VERSION.SDK_INT)
        val started = System.nanoTime()
        var stage = "input"
        try {
            withTimeout(120_000) {
                val manifest = File(root, "input.json")
                require(manifest.length() in 1..20_000)
                val hashes = JsonParser.parseString(manifest.readText()).asJsonObject.getAsJsonArray("hashes")
                    .map { it.asString.also { hash -> require(hash.matches(Regex("[a-f0-9]{64}"))) } }
                require(hashes.size in 1..20 && hashes.distinct().size == hashes.size)
                val store = ImageStore(cache)
                var remaining = ImageStore.MAX_BATCH_BYTES
                val sources = hashes.mapIndexed { index, expected ->
                    val image = store.copy({ Base64InputStream(File(root, "$index.image.b64").inputStream(), Base64.DEFAULT) }, remaining)
                    require(image.hash == expected) { "Uploaded bytes changed" }
                    remaining -= image.bytes
                    EvidenceAsset("image-$index", contentSha256 = image.hash, mimeType = image.mime,
                        byteSize = image.bytes, widthPx = image.width, heightPx = image.height,
                        importSource = EvidenceImportSource.Other, importedAtEpochMillis = System.currentTimeMillis())
                }
                val analyzer = MlKitReceiptAnalyzer(store) { pages ->
                    result["ocrPages"] = pages
                    stage = "parser"
                }
                result["descriptor"] = analyzer.descriptor
                val request = AiAnalysisRequest(job, sources.map { LocalImageId(it.id) },
                    sources.associate { LocalImageId(it.id) to it.contentSha256 }, setOf(AiCapability.StructuredOutput))
                stage = "ocr"
                when (val analyzed = analyzer.analyze(request)) {
                    is AnalysisResult.Failure -> error(analyzed.error.kind.name)
                    is AnalysisResult.Success -> {
                        val recognition = requireNotNull(analyzed.receipt.recognition)
                        result["recognition"] = recognition
                        stage = "mapping"
                        val descriptor = analyzer.descriptor
                        val draft = MapReceiptRecognition().map(ReceiptDraft(job, evidenceAssetIds = sources.map { it.id }),
                            sources, recognition, ExtractionProvenance(job, descriptor.analyzerId, descriptor.modelId,
                                requireNotNull(descriptor.schemaVersion), ExtractionRuntime.OnDevice,
                                System.currentTimeMillis(), descriptor.promptVersion))
                        result["candidate"] = mapOf("merchant" to draft.merchant.inputText().ifBlank { null },
                            "date" to draft.transactionDate.inputText().ifBlank { null }, "currency" to "TWD",
                            "totalMinor" to draft.total.inputText().ifBlank { null },
                            "items" to draft.items.map { mapOf("name" to it.rawName.inputText().ifBlank { null },
                                "quantity" to it.quantity.inputText().ifBlank { null },
                                "lineTotalMinor" to it.printedTotal.inputText().ifBlank { null }) },
                            "adjustments" to draft.adjustments.map { mapOf("amountMinor" to it.amount.inputText().ifBlank { null },
                                "direction" to it.direction.name, "scope" to null) })
                        result["warnings"] = draft.extraction?.warnings.orEmpty()
                        result["status"] = "succeeded"
                        stage = "complete"
                    }
                }
            }
        } catch (error: Exception) {
            result["status"] = "failed"
            result["error"] = error.message?.take(1000) ?: error.javaClass.simpleName
        } finally {
            result["stage"] = stage
            result["elapsedMs"] = (System.nanoTime() - started) / 1_000_000
            result["accuracy"] = "not_evaluated"
            root.mkdirs()
            val temporary = File(root, "result.tmp")
            temporary.writeText(GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result))
            check(temporary.renameTo(File(root, "result.json")))
            // Both roots are fixed app-local subdirectories with a validated generated job id.
            cache.deleteRecursively()
        }
    }
}

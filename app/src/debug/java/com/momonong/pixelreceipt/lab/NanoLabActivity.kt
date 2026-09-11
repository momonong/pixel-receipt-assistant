package com.momonong.pixelreceipt.lab

import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Base64InputStream
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.momonong.pixelreceipt.R
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.momonong.pixelreceipt.data.extraction.*
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.domain.ai.*
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.*
import com.momonong.pixelreceipt.domain.usecase.*
import kotlinx.coroutines.*
import java.io.File

/** Foreground diagnostics using production adapter/mapper, with no access to the user's repository. */
class NanoLabActivity : ComponentActivity() {
    private var resumed = false
    private var launched = false
    private var job: Job? = null
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreation cancels the original job; it must never replay inference automatically.
        launched = savedInstanceState != null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply { setText(R.string.nano_lab_intro); textSize = 18f }
        if (launched) status.setText(R.string.nano_lab_interrupted)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 96, 24, 24); addView(status)
            addView(Button(this@NanoLabActivity).apply { setText(R.string.nano_lab_cancel); setOnClickListener { job?.cancel() } })
        })
    }
    override fun onResume() { super.onResume(); resumed = true; if (!launched) { launched = true; launchJob() } }
    override fun onPause() { resumed = false; job?.cancel(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("nanoLabLaunched", launched)
        super.onSaveInstanceState(outState)
    }

    private fun launchJob() {
        val identity = intent.getStringExtra("job") ?: return
        if (!identity.matches(Regex("[a-f0-9]{32}"))) return
        val root = File(filesDir, "nano-lab/$identity")
        val input = File(root, "input.json")
        if (!input.isFile || input.length() !in 1..20_000 || File(root, "result.json").exists()) {
            status.setText(R.string.nano_lab_invalid); return
        }
        job = lifecycleScope.launch {
            val result = linkedMapOf<String, Any?>("schemaVersion" to 1, "jobId" to identity,
                "device" to Build.MODEL, "androidApi" to Build.VERSION.SDK_INT, "accuracy" to "not_evaluated")
            val started = System.nanoTime()
            val cache = File(cacheDir, "nano-lab/$identity")
            val owner = currentCoroutineContext().job
            val cancellation = launch(Dispatchers.IO) {
                while (isActive) {
                    if (File(root, "cancel").isFile) { owner.cancel(); break }
                    delay(200)
                }
            }
            try {
                withTimeout(240_000) {
                    val manifest = withContext(Dispatchers.IO) { JsonParser.parseString(input.readText()).asJsonObject }
                    val mode = manifest["mode"].asString
                    require(mode in setOf("probe", "nano-image", "nano-ocr"))
                    result["engine"] = mode
                    val images = ImageStore(cache)
                    val analyzer = MlKitNanoAnalyzer(images, { resumed },
                        if (mode == "nano-image") NanoInputMode.Image else NanoInputMode.ImageWithOcr) { key, value -> result[key] = value }
                    status.setText(R.string.nano_lab_probe)
                    val capability = analyzer.probe(download = manifest["download"]?.asBoolean == true)
                    result["capability"] = capability
                    result["aicoreVersion"] = try {
                        @Suppress("DEPRECATION")
                        packageManager.getPackageInfo("com.google.android.aicore", 0).versionName
                    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { "not_visible_or_installed" }
                    if (mode == "probe") { result["status"] = "succeeded"; return@withTimeout }
                    check(capability["featureStatus"] == com.google.mlkit.genai.common.FeatureStatus.AVAILABLE) { "Nano 未就緒" }
                    check(capability["structuredOutput"] == true) { "Nano 結構化輸出未就緒" }
                    val hashes = manifest.getAsJsonArray("hashes").map { it.asString }
                    require(hashes.size == 1 && hashes.all { it.matches(Regex("[a-f0-9]{64}")) })
                    result["inputHashes"] = hashes
                    val sources = withContext(Dispatchers.IO) {
                        hashes.mapIndexed { index, expected ->
                            val image = images.copy({ Base64InputStream(File(root, "$index.image.b64").inputStream(), Base64.DEFAULT) }, ImageStore.MAX_BYTES)
                            require(image.hash == expected)
                            EvidenceAsset("image-$index", contentSha256 = image.hash, mimeType = image.mime,
                                byteSize = image.bytes, widthPx = image.width, heightPx = image.height,
                                importSource = EvidenceImportSource.Other, importedAtEpochMillis = System.currentTimeMillis())
                        }
                    }
                    val request = AiAnalysisRequest(identity, sources.map { LocalImageId(it.id) },
                        sources.associate { LocalImageId(it.id) to it.contentSha256 }, setOf(AiCapability.StructuredOutput))
                    status.text = getString(R.string.nano_lab_running, mode)
                    val routed = AiRouter(analyzer, null).analyze(request, AiRoutingPolicy(AiRoutingMode.OnDeviceOnly),
                        AiRuntimeContext(resumed, false, OnDeviceModelState.Available)) as AiRouterResult.Executed
                    val analysis = routed.result
                    if (analysis is AnalysisResult.Failure) error(analysis.error.userMessage ?: analysis.error.kind.name)
                    check(resumed)
                    val descriptor = analyzer.descriptor
                    val draft = MapReceiptRecognition().map(ReceiptDraft(identity, evidenceAssetIds = sources.map { it.id }),
                        sources, requireNotNull((analysis as AnalysisResult.Success).receipt.recognition),
                        ExtractionProvenance(identity, descriptor.analyzerId, descriptor.modelId, requireNotNull(descriptor.schemaVersion),
                            ExtractionRuntime.OnDevice, System.currentTimeMillis(), descriptor.promptVersion))
                    result["candidate"] = mapOf("merchant" to draft.merchant.inputText().ifBlank { null },
                        "date" to draft.transactionDate.inputText().ifBlank { null }, "currency" to "TWD",
                        "totalMinor" to draft.total.inputText().ifBlank { null },
                        "items" to draft.items.mapIndexed { index, item -> mapOf("name" to item.rawName.inputText().ifBlank { null },
                            "quantity" to item.quantity.inputText().ifBlank { null }, "lineTotalMinor" to item.printedTotal.inputText().ifBlank { null },
                            "unitPriceMinor" to analysis.receipt.recognition?.items?.get(index)?.unitPrice?.text) },
                        "adjustments" to draft.adjustments.map { mapOf("amountMinor" to it.amount.inputText().ifBlank { null }, "direction" to it.direction.name, "scope" to null) })
                    result["warnings"] = draft.extraction?.warnings
                    result["status"] = "succeeded"
                }
            } catch (error: CancellationException) {
                result["status"] = "failed"; result["cancelled"] = true
                result["error"] = if (error is TimeoutCancellationException) "Nano 測試逾時" else "取消或離開前景，未套用結果"
            } catch (error: Exception) {
                result["status"] = "failed"; result["error"] = error.message?.take(1000) ?: error.javaClass.simpleName
                if (error is com.google.mlkit.genai.common.GenAiException) result["errorCode"] = error.errorCode
            } finally {
                cancellation.cancel()
                result["totalElapsedMs"] = (System.nanoTime() - started) / 1_000_000
                withContext(NonCancellable + Dispatchers.IO) {
                    val temporary = File(root, "result.tmp")
                    temporary.writeText(GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result))
                    check(temporary.renameTo(File(root, "result.json")))
                    // Fixed cache subdirectory for this validated UUID only; never user's evidence/DB.
                    cache.deleteRecursively()
                }
                status.text = getString(R.string.nano_lab_done, result["status"].orEmptyText(), result["error"].orEmptyText())
            }
        }
    }
}

private fun Any?.orEmptyText() = this?.toString().orEmpty()

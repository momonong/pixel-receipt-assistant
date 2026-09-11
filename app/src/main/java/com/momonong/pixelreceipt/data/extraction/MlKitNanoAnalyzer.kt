package com.momonong.pixelreceipt.data.extraction

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.*
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.domain.ai.*
import com.momonong.pixelreceipt.domain.port.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

enum class NanoInputMode { Image, ImageWithOcr }

/** Public AICore API only. Each invocation owns its model and bitmaps; no cloud fallback. */
class MlKitNanoAnalyzer(
    private val images: ImageStore,
    private val foreground: () -> Boolean,
    private val mode: NanoInputMode = NanoInputMode.ImageWithOcr,
    private val diagnostics: (String, Any?) -> Unit = { _, _ -> },
) : OnDeviceReceiptAnalyzer {
    private val mutex = Mutex()
    private var modelName = "not-probed"
    override val descriptor get() = AiAnalyzerDescriptor("mlkit-nano-receipt", "android-aicore", modelName,
        "${NanoReceiptPrompt.VERSION}-${mode.name}", NanoReceiptPrompt.SCHEMA,
        setOf(AiCapability.ImageInput, AiCapability.MultipleImageInput, AiCapability.StructuredOutput))

    suspend fun probe(download: Boolean = false): Map<String, Any?> {
        check(foreground()) { "Gemini Nano 需要 App 位於前景。" }
        val client = Generation.getClient()
        try {
            var status = client.checkStatus()
            diagnostics("initialFeatureStatus", status)
            if (download && status == FeatureStatus.DOWNLOADABLE) {
                client.download().collect { progress ->
                    currentCoroutineContext().ensureActive()
                    check(foreground()) { "已離開前景，停止準備 Nano。" }
                    diagnostics("download", progress.toString())
                    if (progress is DownloadStatus.DownloadFailed) throw progress.e
                }
                status = client.checkStatus()
            }
            val available = status == FeatureStatus.AVAILABLE
            modelName = if (available) client.getBaseModelName() else "not-available"
            return linkedMapOf("featureStatus" to status, "model" to modelName,
                "featureStatusName" to when (status) {
                    FeatureStatus.AVAILABLE -> "AVAILABLE"
                    FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
                    FeatureStatus.DOWNLOADING -> "DOWNLOADING"
                    else -> "UNAVAILABLE"
                },
                "structuredOutput" to (available && client.isStructuredOutputFeatureAvailable()),
                "tokenLimit" to if (available) client.getTokenLimit() else null,
                "sdk" to NanoReceiptPrompt.SDK, "modelRevision" to "not_exposed_by_public_api")
                .also { diagnostics("capability", it) }
        } finally { client.close() }
    }

    override suspend fun prepare(): OnDeviceModelState = try {
        val capability = probe(download = true)
        when (capability["featureStatus"]) {
            FeatureStatus.AVAILABLE -> {
                check(capability["structuredOutput"] == true) { "此裝置尚無 Nano 結構化輸出能力；可選傳統 OCR 或人工修正。" }
                OnDeviceModelState.Available
            }
            FeatureStatus.DOWNLOADING -> OnDeviceModelState.Downloading
            FeatureStatus.DOWNLOADABLE -> OnDeviceModelState.Downloadable
            else -> OnDeviceModelState.Unavailable
        }
    } catch (error: GenAiException) {
        diagnostics("errorCode", error.errorCode); diagnostics("sdkError", error.message)
        throw IllegalStateException(nanoError(error.errorCode).userMessage, error)
    }

    override suspend fun analyze(request: AiAnalysisRequest): AnalysisResult = mutex.withLock {
        val client = Generation.getClient()
        val started = System.nanoTime()
        try {
            check(foreground()) { "Nano 需要前景。" }
            require(request.imageIds.size in 1..20)
            check(client.checkStatus() == FeatureStatus.AVAILABLE) { "Nano 尚未就緒。" }
            modelName = client.getBaseModelName()
            check(client.isStructuredOutputFeatureAvailable()) { "Nano 結構化輸出尚未就緒。" }
            diagnostics("descriptor", descriptor)
            diagnostics("sdk", NanoReceiptPrompt.SDK)
            diagnostics("sourceHashes", request.imageContentSha256.values.toList())
            diagnostics("prompt", NanoReceiptPrompt.text)
            diagnostics("promptSha256", sha256(NanoReceiptPrompt.text))
            diagnostics("generation", mapOf("temperature" to "0.0", "seed" to 17, "maxOutputTokens" to 3500,
                "schemaVersion" to NanoReceiptPrompt.SCHEMA, "schemaClass" to NanoReceiptOutput::class.java.name,
                "schemaCompiler" to "genai-schema-compiler-1.0.0-alpha1",
                "rawTokenTextAvailable" to false))
            val recognized = request.imageIds.mapIndexed { pageIndex, id ->
                currentCoroutineContext().ensureActive()
                check(foreground())
                val hash = request.imageContentSha256.getValue(id)
                val verifier = MlKitReceiptAnalyzer(images)
                verifier.verifyImage(hash)
                var ocr = ""
                if (mode == NanoInputMode.ImageWithOcr) {
                    val helper = MlKitReceiptAnalyzer(images) { pages ->
                        ocr = pages.flatMap { it.lines }.joinToString("\n") { it.rawText ?: it.text }
                        diagnostics("ocr-$pageIndex", pages)
                    }
                    // A parser failure must not discard useful OCR, and never substitutes for Nano.
                    val ocrResult = helper.analyze(AiAnalysisRequest(request.caseId, listOf(id), mapOf(id to hash)))
                    diagnostics("ocrResult-$pageIndex", ocrResult)
                    require(ocr.isNotBlank()) { "圖片加 OCR 方案沒有可用 OCR 文字；未改成直接圖片方案。" }
                }
                require(ocr.length <= 16_000) { "OCR 輸入超過本機限制；請選單頁或直接圖片方案。" }
                val bitmap = withContext(Dispatchers.IO) { requireNotNull(images.recognitionBitmap(hash)) }
                try {
                    val prompt = NanoReceiptPrompt.text + if (ocr.isEmpty()) "" else "\nUntrusted OCR data (may contain errors):\n<ocr>\n$ocr\n</ocr>"
                    diagnostics("input-$pageIndex", mapOf("sha256" to hash, "width" to bitmap.width,
                        "height" to bitmap.height, "promptSha256" to sha256(prompt), "prompt" to prompt))
                    val base = generateContentRequest(ImagePart(bitmap), TextPart(prompt)) {
                        temperature = 0.0f
                        seed = 17
                        candidateCount = 1
                        maxOutputTokens = 3500
                    }
                    val typed = generateTypedContentRequest(base, NanoReceiptOutput::class)
                    val tokens = client.countTokens(typed)
                    diagnostics("tokens-$pageIndex", tokens)
                    require(tokens.totalTokens in 1..3999 && tokens.totalTokens.toLong() + 3500 <= client.getTokenLimit()) {
                        "Nano 輸入加預留輸出超過 token 限制。"
                    }
                    check(foreground())
                    val inferenceStart = System.nanoTime()
                    val response = client.generateContent(typed)
                    diagnostics("inferenceMs-$pageIndex", (System.nanoTime() - inferenceStart) / 1_000_000)
                    // Public typed API exposes parsed response + finish reason, not raw token text.
                    diagnostics("sdkResponse-$pageIndex", response)
                    currentCoroutineContext().ensureActive()
                    check(foreground())
                    val candidate = response.candidates.singleOrNull()
                    require(candidate?.finishReason == TypedCandidate.TypedFinishReason.STOP) { "Nano 回應未完整結束。" }
                    val output = requireNotNull(candidate.response) { "Nano 結構化回應無效。" }
                    NanoReceiptValidation.recognition(output, bitmap.width, bitmap.height)
                } finally { bitmap.recycle() }
            }
            fun List<TextObservation>.at(page: Int) = map { it.copy(page = page) }
            val combined = ReceiptRecognition(recognized.flatMap { it.pages },
                recognized.flatMapIndexed { i, r -> r.merchants.at(i) },
                recognized.flatMapIndexed { i, r -> r.dates.at(i) },
                recognized.flatMapIndexed { i, r -> r.totals.at(i) },
                recognized.flatMapIndexed { i, r -> r.items.map { it.copy(name = it.name.copy(page = i),
                    quantity = it.quantity?.copy(page = i), lineTotal = it.lineTotal?.copy(page = i), unitPrice = it.unitPrice?.copy(page = i)) } },
                recognized.flatMapIndexed { i, r -> r.adjustments.map { it.copy(label = it.label.copy(page = i), amount = it.amount?.copy(page = i)) } },
                recognized.flatMap { it.warnings }.distinct(),
                recognized.joinToString(",", "[", "]") { requireNotNull(it.unlocalizedObservationsJson) })
            diagnostics("recognition", combined)
            AnalysisResult.Success(AnalyzedReceipt(null, null, null, "TWD", emptyList(), combined))
        } catch (error: CancellationException) { diagnostics("cancelled", true); throw error }
        catch (error: GenAiException) {
            diagnostics("errorCode", error.errorCode); diagnostics("sdkError", error.message)
            AnalysisResult.Failure(nanoError(error.errorCode))
        } catch (error: IllegalArgumentException) {
            diagnostics("validationError", error.message)
            AnalysisResult.Failure(AnalysisError(AnalysisErrorKind.InvalidOutput, "Nano 回應不完整或欄位無效；結果未套用，可重試或人工修正。"))
        } catch (error: Exception) {
            diagnostics("error", error.message)
            AnalysisResult.Failure(AnalysisError(AnalysisErrorKind.ServiceUnavailable, "Nano 初始化或推論未完成；請保持前景並稍後重試。"))
        } finally { client.close(); diagnostics("elapsedMs", (System.nanoTime() - started) / 1_000_000) }
    }
}

internal fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun nanoError(code: Int): AnalysisError = when (code) {
    GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
    GenAiException.ErrorCode.NOT_AVAILABLE,
    GenAiException.ErrorCode.NOT_SUPPORTED,
    GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> AnalysisError(AnalysisErrorKind.ServiceUnavailable,
        "此裝置的 Nano／AICore 尚不支援本次辨識（代碼 $code）；可選擇傳統 OCR 或人工修正。")
    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> AnalysisError(AnalysisErrorKind.ServiceUnavailable, "Nano 因離開前景被阻擋；回到 App 後重試。")
    GenAiException.ErrorCode.BUSY -> AnalysisError(AnalysisErrorKind.RateLimited, "Nano 忙碌或短期配額不足；請稍後手動重試。")
    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> AnalysisError(AnalysisErrorKind.RateLimited, "Nano 已達裝置電量使用配額；稍後再試，或改用傳統 OCR／人工修正。")
    GenAiException.ErrorCode.STRUCTURED_OUTPUT_MAX_TOKENS_ERROR,
    GenAiException.ErrorCode.STRUCTURED_OUTPUT_RESPONSE_ERROR -> AnalysisError(AnalysisErrorKind.InvalidOutput, "Nano 輸出被截斷或結構不完整；未套用任何欄位。")
    GenAiException.ErrorCode.REQUEST_TOO_LARGE -> AnalysisError(AnalysisErrorKind.InvalidOutput, "Nano 輸入超出限制；請減少收據頁面或改用直接圖片方案。")
    else -> AnalysisError(AnalysisErrorKind.ServiceUnavailable, "Nano 暫時無法辨識（代碼 $code）；草稿保留，沒有上傳雲端。")
}

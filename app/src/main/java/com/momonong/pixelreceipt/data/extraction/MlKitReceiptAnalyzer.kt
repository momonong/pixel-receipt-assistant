package com.momonong.pixelreceipt.data.extraction

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.domain.ai.*
import com.momonong.pixelreceipt.domain.port.*
import kotlinx.coroutines.*
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bundled Chinese model: one image per SDK call, local multi-page parsing above the SDK. */
class MlKitReceiptAnalyzer(private val images: ImageStore) : OnDeviceReceiptAnalyzer {
    override val descriptor = AiAnalyzerDescriptor("mlkit-chinese-receipt", "local-mlkit", "text-recognition-chinese-16.0.1",
        promptVersion = LocalReceiptParser.VERSION, schemaVersion = "receipt-observations-1",
        capabilities = setOf(AiCapability.ImageInput, AiCapability.MultipleImageInput, AiCapability.StructuredOutput))

    override suspend fun analyze(request: AiAnalysisRequest): AnalysisResult = withContext(Dispatchers.IO) {
        try {
            require(request.imageIds.size <= 20)
            val pages = request.imageIds.map { id ->
                ensureActive()
                val hash = request.imageContentSha256.getValue(id)
                verifyImage(hash)
                val bitmap = requireNotNull(images.recognitionBitmap(hash)) { "圖片無法解碼。" }
                val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                // SDK Task cannot be cancelled. Resource cleanup belongs to its completion callback;
                // cancelled continuations never apply late results or recycle a bitmap still in use.
                val page = suspendCancellableCoroutine<OcrPage> { continuation ->
                    try {
                        recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(Dispatchers.Default.asExecutor()) { task ->
                            try {
                                if (continuation.isActive) {
                                    if (task.isSuccessful) {
                                        val lines = task.result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                            val box = line.boundingBox ?: return@mapNotNull null
                                            val left = box.left.coerceIn(0, bitmap.width)
                                            val right = box.right.coerceIn(0, bitmap.width)
                                            val top = box.top.coerceIn(0, bitmap.height)
                                            val bottom = box.bottom.coerceIn(0, bitmap.height)
                                            if (right <= left || bottom <= top || line.text.isBlank()) null
                                            else {
                                                val symbols = line.elements.flatMap { it.symbols }.mapNotNull { symbol ->
                                                    symbol.boundingBox?.let { OcrSymbol(symbol.text, it.left, it.right) }
                                                }
                                                OcrLine(spacedOcrText(line.text, symbols, visibleColumnGaps(bitmap, left, top, right, bottom)), left, top, right, bottom, line.text)
                                            }
                                        }
                                        continuation.resume(OcrPage(bitmap.width, bitmap.height, joinRows(lines)))
                                    } else continuation.resumeWithException(task.exception ?: IllegalStateException("OCR 失敗"))
                                }
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            } finally { recognizer.close(); bitmap.recycle() }
                        }
                    } catch (error: Exception) {
                        recognizer.close(); bitmap.recycle()
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                page
            }
            ensureActive()
            val recognition = LocalReceiptParser().parse(pages)
            if (recognition.items.isEmpty()) return@withContext AnalysisResult.Failure(AnalysisError(AnalysisErrorKind.InvalidOutput))
            AnalysisResult.Success(AnalyzedReceipt(null, null, null, "TWD", emptyList(), recognition))
        } catch (error: CancellationException) { throw error }
        catch (_: IllegalArgumentException) { AnalysisResult.Failure(AnalysisError(AnalysisErrorKind.InvalidOutput)) }
        catch (_: Exception) { AnalysisResult.Failure(AnalysisError(AnalysisErrorKind.ServiceUnavailable)) }
    }

    suspend fun verifyImage(hash: String) = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val file = images.file(hash)
        require(file.length() in 1..ImageStore.MAX_BYTES) { "圖片已變更或遺失。" }
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "來源圖片內容已變更。" }
    }

    private fun joinRows(lines: List<OcrLine>): List<OcrLine> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        lines.sortedBy { it.top + it.bottom }.forEach { line ->
            val row = rows.lastOrNull()
            val anchor = row?.firstOrNull()
            val overlap = anchor?.let { minOf(it.bottom, line.bottom) - maxOf(it.top, line.top) } ?: 0
            if (anchor != null && overlap * 2 >= maxOf(anchor.bottom - anchor.top, line.bottom - line.top)) row.add(line)
            else rows += mutableListOf(line)
        }
        return rows.map { row -> OcrLine(row.sortedBy { it.left }.joinToString(" ") { it.text },
            row.minOf { it.left }, row.minOf { it.top }, row.maxOf { it.right }, row.maxOf { it.bottom },
            row.sortedBy { it.left }.joinToString(" ") { it.rawText ?: it.text }) }
    }
}

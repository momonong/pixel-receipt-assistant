package com.momonong.pixelreceipt.domain.port

import com.momonong.pixelreceipt.domain.ai.AiAnalysisRequest
import com.momonong.pixelreceipt.domain.ai.AiAnalyzerDescriptor

/** Implemented by an adapter backed by a model whose inference stays on the device. */
interface OnDeviceReceiptAnalyzer {
    val descriptor: AiAnalyzerDescriptor

    suspend fun analyze(request: AiAnalysisRequest): AnalysisResult
}

/** Implemented by an adapter that may transmit receipt evidence to a remote model. */
interface CloudReceiptAnalyzer {
    val descriptor: AiAnalyzerDescriptor

    suspend fun analyze(request: AiAnalysisRequest): AnalysisResult
}

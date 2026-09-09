package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.ai.*
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.util.UUID

/** No persisted intermediate stage: failure, process death or cancellation leaves the draft intact. */
class ExtractReceipt(
    private val repository: ReceiptRepository,
    private val analyzer: OnDeviceReceiptAnalyzer,
    private val loadEvidence: suspend (String) -> List<EvidenceAsset>,
    private val verifyImage: suspend (String) -> Unit,
) {
    suspend fun run(base: ReceiptDraft, selected: Set<String>, replaceApproved: Boolean,
        foreground: () -> Boolean): ReceiptDraft {
        require(base.stage in setOf(ReceiptStage.Captured, ReceiptStage.NeedsReview)) { "此交易唯讀，不能辨識。" }
        require(base.revision < Long.MAX_VALUE)
        require(replaceApproved || !hasReviewContent(base)) { "重新辨識需明確同意取代目前核對欄位。" }
        require(base.promotionApplications.isEmpty() && base.promotionOffers.isEmpty() && base.allocations.isEmpty()) {
            "此交易含促銷或分攤關聯，請保留人工核對，不能取代。"
        }
        check(repository.observeDraft(base.id).first() == base) { "版本衝突，草稿已變更；請重新載入。" }
        val all = loadEvidence(base.id)
        require(selected.isNotEmpty() && selected.size <= 20 && selected.all { it in base.evidenceAssetIds })
        val sources = all.filter { it.id in selected }
        require(sources.size == selected.size && sources.none { (it.kind as? Fact.Known)?.value in setOf(EvidenceAssetKind.PriceTag, EvidenceAssetKind.PromotionSign, EvidenceAssetKind.Other) }) {
            "只能選取本交易的收據圖片。"
        }
        check(foreground()) { "請回到前景再辨識。" }
        val request = AiAnalysisRequest(base.id, sources.map { LocalImageId(it.id) },
            sources.associate { LocalImageId(it.id) to it.contentSha256 }, setOf(AiCapability.StructuredOutput))
        val routed = AiRouter(analyzer, null).analyze(request, AiRoutingPolicy(AiRoutingMode.OnDeviceOnly),
            AiRuntimeContext(foreground(), false, OnDeviceModelState.Available))
        val result = (routed as? AiRouterResult.Executed)?.result ?: error("本機辨識器未就緒，草稿保留。")
        require(result !is AnalysisResult.Success || result.receipt.currencyCodeText == "TWD") { "本次辨識僅接受 TWD 收據。" }
        val recognized = (result as? AnalysisResult.Success)?.receipt?.recognition ?: error(
            if ((result as? AnalysisResult.Failure)?.error?.kind == AnalysisErrorKind.InvalidOutput)
                "未能擷取可用的品項；可能只有文字、版面不支援或圖片不清楚。可重試或人工核對。"
            else "本機 OCR 未就緒或執行失敗，請重試或人工核對。")
        currentCoroutineContext().ensureActive()
        check(foreground()) { "已離開前景，辨識結果未套用。" }
        check(loadEvidence(base.id) == all) { "來源圖片資料已改變，結果未套用。" }
        sources.forEach { verifyImage(it.contentSha256) }
        val descriptor = analyzer.descriptor
        val mapped = MapReceiptRecognition().map(base, sources, recognized,
            ExtractionProvenance(UUID.randomUUID().toString(), descriptor.analyzerId, descriptor.modelId,
                requireNotNull(descriptor.schemaVersion), ExtractionRuntime.OnDevice, System.currentTimeMillis(), descriptor.promptVersion))
        currentCoroutineContext().ensureActive()
        check(foreground()) { "已離開前景，辨識結果未套用。" }
        val next = mapped.copy(revision = base.revision + 1)
        when (repository.compareAndSetDraft(next, base.revision)) {
            is DraftWriteResult.Written -> return next
            DraftWriteResult.Conflict -> error("版本衝突：草稿已修改或確認，辨識結果未套用。")
            DraftWriteResult.NotFound -> error("草稿已不存在，辨識結果未套用。")
        }
    }
}

fun hasReviewContent(draft: ReceiptDraft): Boolean = draft.extraction != null || draft.items.isNotEmpty() ||
    draft.adjustments.isNotEmpty() || draft.merchant !is Fact.Unknown || draft.total !is Fact.Unknown ||
    draft.transactionDate !is Fact.Unknown || draft.assemblyStatus != ReceiptAssemblyStatus.Collecting

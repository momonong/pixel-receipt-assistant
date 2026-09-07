package com.momonong.pixelreceipt.domain.ai

import com.momonong.pixelreceipt.domain.port.LocalImageId

/** SDK-neutral capabilities an analysis request can require from an AI adapter. */
enum class AiCapability {
    ImageInput,
    MultipleImageInput,
    StructuredOutput,
    ToolCalling,
}

/** A receipt request owns app-private image ids; adapters decide how to load their bytes. */
class AiAnalysisRequest(
    val caseId: String,
    imageIds: Collection<LocalImageId>,
    imageContentSha256: Map<LocalImageId, String>,
    requiredCapabilities: Collection<AiCapability> = emptySet(),
    val purpose: AiAnalysisPurpose = AiAnalysisPurpose.ReceiptEvidenceExtraction,
) {
    val imageIds: List<LocalImageId> = imageIds.toList()
    val imageContentSha256: Map<LocalImageId, String> = imageContentSha256.toMap()
    val requiredCapabilities: Set<AiCapability> = buildSet {
        add(AiCapability.ImageInput)
        if (imageIds.size > 1) add(AiCapability.MultipleImageInput)
        addAll(requiredCapabilities)
    }

    init {
        require(caseId.isNotBlank()) { "AI analysis case id cannot be blank." }
        require(this.imageIds.isNotEmpty()) { "AI analysis requires at least one image." }
        require(this.imageIds.distinct().size == this.imageIds.size) {
            "AI analysis image ids must be unique."
        }
        require(this.imageContentSha256.keys == this.imageIds.toSet()) {
            "AI analysis requires exactly one content digest for every image id."
        }
        require(this.imageContentSha256.values.all(::isLowercaseSha256)) {
            "AI analysis image digests must be lowercase SHA-256 values."
        }
    }
}

enum class AiExecutionTarget {
    OnDevice,
    Cloud,
}

enum class AiRoutingMode {
    OnDeviceOnly,
    PreferOnDevice,
    CloudOnly,
}

enum class AiAnalysisPurpose {
    ReceiptEvidenceExtraction,
}

/** Cloud access is scoped to one case, immutable image contents, purpose and cloud service. */
sealed interface CloudProcessingConsent {
    data object NotRequested : CloudProcessingConsent

    data object Denied : CloudProcessingConsent

    class Granted(
        val caseId: String,
        imageContentSha256: Map<LocalImageId, String>,
        val purpose: AiAnalysisPurpose,
        val cloudAnalyzerId: String,
        val cloudServiceId: String,
        val grantedAtEpochMillis: Long,
    ) : CloudProcessingConsent {
        val imageContentSha256: Map<LocalImageId, String> = imageContentSha256.toMap()

        init {
            require(caseId.isNotBlank()) { "Cloud consent case id cannot be blank." }
            require(this.imageContentSha256.isNotEmpty()) {
                "Cloud consent requires at least one image digest."
            }
            require(this.imageContentSha256.values.all(::isLowercaseSha256)) {
                "Cloud consent image digests must be lowercase SHA-256 values."
            }
            require(cloudAnalyzerId.isNotBlank()) {
                "Cloud consent analyzer id cannot be blank."
            }
            require(cloudServiceId.isNotBlank()) {
                "Cloud consent service id cannot be blank."
            }
            require(grantedAtEpochMillis >= 0) { "Cloud consent timestamp cannot be negative." }
        }

        fun covers(
            request: AiAnalysisRequest,
            analyzer: AiAnalyzerDescriptor,
        ): Boolean =
            caseId == request.caseId &&
                imageContentSha256 == request.imageContentSha256 &&
                purpose == request.purpose &&
                cloudAnalyzerId == analyzer.analyzerId &&
                cloudServiceId == analyzer.serviceId
    }
}

data class AiRoutingPolicy(
    val mode: AiRoutingMode = AiRoutingMode.PreferOnDevice,
    val cloudConsent: CloudProcessingConsent = CloudProcessingConsent.NotRequested,
)

enum class OnDeviceModelState {
    Available,
    Downloadable,
    Downloading,
    Unavailable,
}

/** Volatile device state is sampled immediately before routing. */
data class AiRuntimeContext(
    val isAppInForeground: Boolean,
    val isNetworkAvailable: Boolean,
    val onDeviceModelState: OnDeviceModelState,
)

/** Stable metadata supplied by an adapter and recorded with every executed analysis. */
class AiAnalyzerDescriptor(
    val analyzerId: String,
    val serviceId: String,
    val modelId: String,
    val promptVersion: String? = null,
    val schemaVersion: String? = null,
    capabilities: Collection<AiCapability>,
) {
    val capabilities: Set<AiCapability> = capabilities.toSet()

    init {
        require(analyzerId.isNotBlank()) { "Analyzer id cannot be blank." }
        require(serviceId.isNotBlank()) { "Analyzer service id cannot be blank." }
        require(modelId.isNotBlank()) { "Model id cannot be blank." }
        require(promptVersion == null || promptVersion.isNotBlank()) {
            "Prompt version cannot be blank when present."
        }
        require(schemaVersion == null || schemaVersion.isNotBlank()) {
            "Schema version cannot be blank when present."
        }
    }
}

data class AiExecutionRequirements(
    val capabilities: Set<AiCapability>,
    val requiresForeground: Boolean,
    val requiresNetwork: Boolean,
    val requiresCloudConsent: Boolean,
)

data class AiAnalysisProvenance(
    val target: AiExecutionTarget,
    val analyzerId: String,
    val serviceId: String,
    val modelId: String,
    val promptVersion: String?,
    val schemaVersion: String?,
    val requestedCapabilities: Set<AiCapability>,
    /** The exact grant authorizing this upload; always null for on-device execution. */
    val cloudConsentScope: CloudProcessingConsent.Granted?,
) {
    init {
        require(
            (target == AiExecutionTarget.Cloud) == (cloudConsentScope != null),
        ) { "Cloud provenance requires consent scope; on-device provenance must not contain it." }
        cloudConsentScope?.let { scope ->
            require(
                scope.cloudAnalyzerId == analyzerId && scope.cloudServiceId == serviceId,
            ) { "Cloud provenance analyzer and service must match its consent scope." }
        }
    }

    val usedCloud: Boolean
        get() = target == AiExecutionTarget.Cloud
}

sealed interface AiRouteBlocker {
    data class AnalyzerNotConfigured(val target: AiExecutionTarget) : AiRouteBlocker

    data class MissingCapabilities(
        val missing: Set<AiCapability>,
    ) : AiRouteBlocker

    data class OnDeviceModelNotAvailable(
        val state: OnDeviceModelState,
    ) : AiRouteBlocker

    data object ForegroundRequired : AiRouteBlocker

    data object NetworkRequired : AiRouteBlocker

    data object CloudConsentRequired : AiRouteBlocker

    data object CloudConsentDenied : AiRouteBlocker

    data object CloudConsentScopeMismatch : AiRouteBlocker
}

data class AiRouteAttempt(
    val target: AiExecutionTarget,
    val requirements: AiExecutionRequirements,
    val blockers: List<AiRouteBlocker>,
)

private val lowercaseSha256 = Regex("[0-9a-f]{64}")

private fun isLowercaseSha256(value: String): Boolean = lowercaseSha256.matches(value)

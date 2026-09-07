package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.ai.AiAnalysisProvenance
import com.momonong.pixelreceipt.domain.ai.AiAnalysisRequest
import com.momonong.pixelreceipt.domain.ai.AiAnalyzerDescriptor
import com.momonong.pixelreceipt.domain.ai.AiExecutionRequirements
import com.momonong.pixelreceipt.domain.ai.AiExecutionTarget
import com.momonong.pixelreceipt.domain.ai.AiRouteAttempt
import com.momonong.pixelreceipt.domain.ai.AiRouteBlocker
import com.momonong.pixelreceipt.domain.ai.AiRoutingMode
import com.momonong.pixelreceipt.domain.ai.AiRoutingPolicy
import com.momonong.pixelreceipt.domain.ai.AiRuntimeContext
import com.momonong.pixelreceipt.domain.ai.CloudProcessingConsent
import com.momonong.pixelreceipt.domain.ai.OnDeviceModelState
import com.momonong.pixelreceipt.domain.port.AnalysisResult
import com.momonong.pixelreceipt.domain.port.CloudReceiptAnalyzer
import com.momonong.pixelreceipt.domain.port.OnDeviceReceiptAnalyzer

/**
 * Chooses and invokes an analyzer without depending on a vendor SDK.
 *
 * On-device inference is allowed only while the app is in the foreground. Cloud inference
 * requires both network connectivity and explicit consent; fallback never weakens either rule.
 */
class AiRouter(
    private val onDeviceAnalyzer: OnDeviceReceiptAnalyzer?,
    private val cloudAnalyzer: CloudReceiptAnalyzer?,
) {
    suspend fun analyze(
        request: AiAnalysisRequest,
        policy: AiRoutingPolicy,
        runtime: AiRuntimeContext,
    ): AiRouterResult {
        val attempts = mutableListOf<AiRouteAttempt>()

        for (target in policy.mode.candidates()) {
            val requirements = target.requirements(request)
            val descriptor = target.descriptor()
            val blockers = blockersFor(
                target = target,
                descriptor = descriptor,
                request = request,
                requirements = requirements,
                policy = policy,
                runtime = runtime,
            )

            if (blockers.isNotEmpty()) {
                attempts += AiRouteAttempt(target, requirements, blockers)
                continue
            }

            checkNotNull(descriptor)
            val result = when (target) {
                AiExecutionTarget.OnDevice -> checkNotNull(onDeviceAnalyzer).analyze(request)
                AiExecutionTarget.Cloud -> checkNotNull(cloudAnalyzer).analyze(request)
            }
            return AiRouterResult.Executed(
                result = result,
                requirements = requirements,
                provenance = descriptor.provenanceFor(
                    target = target,
                    request = request,
                    cloudConsent = policy.cloudConsent,
                ),
            )
        }

        return AiRouterResult.Blocked(attempts.toList())
    }

    private fun AiExecutionTarget.descriptor(): AiAnalyzerDescriptor? = when (this) {
        AiExecutionTarget.OnDevice -> onDeviceAnalyzer?.descriptor
        AiExecutionTarget.Cloud -> cloudAnalyzer?.descriptor
    }

    private fun blockersFor(
        target: AiExecutionTarget,
        descriptor: AiAnalyzerDescriptor?,
        request: AiAnalysisRequest,
        requirements: AiExecutionRequirements,
        policy: AiRoutingPolicy,
        runtime: AiRuntimeContext,
    ): List<AiRouteBlocker> = buildList {
        if (descriptor == null) {
            add(AiRouteBlocker.AnalyzerNotConfigured(target))
        } else {
            val missing = requirements.capabilities - descriptor.capabilities
            if (missing.isNotEmpty()) add(AiRouteBlocker.MissingCapabilities(missing))
        }

        when (target) {
            AiExecutionTarget.OnDevice -> {
                if (runtime.onDeviceModelState != OnDeviceModelState.Available) {
                    add(
                        AiRouteBlocker.OnDeviceModelNotAvailable(
                            runtime.onDeviceModelState,
                        ),
                    )
                }
                if (!runtime.isAppInForeground) add(AiRouteBlocker.ForegroundRequired)
            }

            AiExecutionTarget.Cloud -> {
                when (policy.cloudConsent) {
                    CloudProcessingConsent.NotRequested -> {
                        add(AiRouteBlocker.CloudConsentRequired)
                    }

                    CloudProcessingConsent.Denied -> add(AiRouteBlocker.CloudConsentDenied)
                    is CloudProcessingConsent.Granted -> {
                        if (
                            descriptor != null &&
                            !policy.cloudConsent.covers(request, descriptor)
                        ) {
                            add(AiRouteBlocker.CloudConsentScopeMismatch)
                        }
                    }
                }
                if (!runtime.isNetworkAvailable) add(AiRouteBlocker.NetworkRequired)
            }
        }
    }

    private fun AiAnalyzerDescriptor.provenanceFor(
        target: AiExecutionTarget,
        request: AiAnalysisRequest,
        cloudConsent: CloudProcessingConsent,
    ) = AiAnalysisProvenance(
        target = target,
        analyzerId = analyzerId,
        serviceId = serviceId,
        modelId = modelId,
        promptVersion = promptVersion,
        schemaVersion = schemaVersion,
        requestedCapabilities = request.requiredCapabilities,
        cloudConsentScope = when (target) {
            AiExecutionTarget.OnDevice -> null
            AiExecutionTarget.Cloud -> checkNotNull(
                cloudConsent as? CloudProcessingConsent.Granted,
            ) { "Cloud execution requires a validated consent grant." }
        },
    )
}

private fun AiRoutingMode.candidates(): List<AiExecutionTarget> = when (this) {
    AiRoutingMode.OnDeviceOnly -> listOf(AiExecutionTarget.OnDevice)
    AiRoutingMode.PreferOnDevice -> listOf(
        AiExecutionTarget.OnDevice,
        AiExecutionTarget.Cloud,
    )
    AiRoutingMode.CloudOnly -> listOf(AiExecutionTarget.Cloud)
}

private fun AiExecutionTarget.requirements(
    request: AiAnalysisRequest,
): AiExecutionRequirements = when (this) {
    AiExecutionTarget.OnDevice -> AiExecutionRequirements(
        capabilities = request.requiredCapabilities,
        requiresForeground = true,
        requiresNetwork = false,
        requiresCloudConsent = false,
    )

    AiExecutionTarget.Cloud -> AiExecutionRequirements(
        capabilities = request.requiredCapabilities,
        requiresForeground = false,
        requiresNetwork = true,
        requiresCloudConsent = true,
    )
}

sealed interface AiRouterResult {
    /** The adapter ran; [result] can still contain a typed inference failure. */
    data class Executed(
        val result: AnalysisResult,
        val requirements: AiExecutionRequirements,
        val provenance: AiAnalysisProvenance,
    ) : AiRouterResult

    /** No adapter ran. Attempts retain every unmet requirement for recovery UI. */
    data class Blocked(
        val attempts: List<AiRouteAttempt>,
    ) : AiRouterResult
}

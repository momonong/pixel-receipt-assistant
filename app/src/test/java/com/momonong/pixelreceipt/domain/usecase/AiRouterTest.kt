package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.ai.AiAnalysisRequest
import com.momonong.pixelreceipt.domain.ai.AiAnalyzerDescriptor
import com.momonong.pixelreceipt.domain.ai.AiCapability
import com.momonong.pixelreceipt.domain.ai.AiExecutionTarget
import com.momonong.pixelreceipt.domain.ai.AiRouteBlocker
import com.momonong.pixelreceipt.domain.ai.AiRoutingMode
import com.momonong.pixelreceipt.domain.ai.AiRoutingPolicy
import com.momonong.pixelreceipt.domain.ai.AiRuntimeContext
import com.momonong.pixelreceipt.domain.ai.CloudProcessingConsent
import com.momonong.pixelreceipt.domain.ai.OnDeviceModelState
import com.momonong.pixelreceipt.domain.port.AnalysisResult
import com.momonong.pixelreceipt.domain.port.AnalyzedReceipt
import com.momonong.pixelreceipt.domain.port.CloudReceiptAnalyzer
import com.momonong.pixelreceipt.domain.port.LocalImageId
import com.momonong.pixelreceipt.domain.port.OnDeviceReceiptAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRouterTest {
    @Test
    fun `available local analyzer runs without network or cloud consent`() = runBlocking {
        val local = FakeOnDeviceAnalyzer(capabilities = baseCapabilities)
        val cloud = FakeCloudAnalyzer(capabilities = baseCapabilities)
        val router = AiRouter(local, cloud)

        val result = router.analyze(
            request = request(),
            policy = policy(cloudConsent = CloudProcessingConsent.Denied),
            runtime = runtime(network = false),
        )

        assertTrue(result is AiRouterResult.Executed)
        result as AiRouterResult.Executed
        assertEquals(AiExecutionTarget.OnDevice, result.provenance.target)
        assertFalse(result.provenance.usedCloud)
        assertNull(result.provenance.cloudConsentScope)
        assertTrue(result.requirements.requiresForeground)
        assertFalse(result.requirements.requiresNetwork)
        assertEquals(1, local.callCount)
        assertEquals(0, cloud.callCount)
    }

    @Test
    fun `on-device provenance excludes an unused cloud consent grant`() = runBlocking {
        val local = FakeOnDeviceAnalyzer(capabilities = baseCapabilities)
        val cloud = FakeCloudAnalyzer(capabilities = baseCapabilities)
        val router = AiRouter(local, cloud)

        val result = router.analyze(
            request = request(),
            policy = policy(cloudConsent = grantedConsent()),
            runtime = runtime(),
        )

        assertTrue(result is AiRouterResult.Executed)
        result as AiRouterResult.Executed
        assertEquals(AiExecutionTarget.OnDevice, result.provenance.target)
        assertNull(result.provenance.cloudConsentScope)
        assertEquals(1, local.callCount)
        assertEquals(0, cloud.callCount)
    }

    @Test
    fun `cloud fallback is blocked when network is unavailable`() = runBlocking {
        val local = FakeOnDeviceAnalyzer(capabilities = baseCapabilities)
        val cloud = FakeCloudAnalyzer(capabilities = baseCapabilities)
        val router = AiRouter(local, cloud)

        val result = router.analyze(
            request = request(),
            policy = policy(cloudConsent = grantedConsent()),
            runtime = runtime(
                network = false,
                modelState = OnDeviceModelState.Unavailable,
            ),
        )

        assertTrue(result is AiRouterResult.Blocked)
        val cloudAttempt = (result as AiRouterResult.Blocked).attemptFor(AiExecutionTarget.Cloud)
        assertTrue(AiRouteBlocker.NetworkRequired in cloudAttempt.blockers)
        assertTrue(cloudAttempt.requirements.requiresNetwork)
        assertEquals(0, local.callCount)
        assertEquals(0, cloud.callCount)
    }

    @Test
    fun `explicit cloud denial prevents fallback even with network`() = runBlocking {
        val local = FakeOnDeviceAnalyzer(capabilities = baseCapabilities)
        val cloud = FakeCloudAnalyzer(capabilities = baseCapabilities)
        val router = AiRouter(local, cloud)

        val result = router.analyze(
            request = request(),
            policy = policy(cloudConsent = CloudProcessingConsent.Denied),
            runtime = runtime(modelState = OnDeviceModelState.Unavailable),
        )

        assertTrue(result is AiRouterResult.Blocked)
        val cloudAttempt = (result as AiRouterResult.Blocked).attemptFor(AiExecutionTarget.Cloud)
        assertTrue(AiRouteBlocker.CloudConsentDenied in cloudAttempt.blockers)
        assertTrue(cloudAttempt.requirements.requiresCloudConsent)
        assertEquals(0, cloud.callCount)
    }

    @Test
    fun `on-device inference is blocked outside the foreground`() = runBlocking {
        val local = FakeOnDeviceAnalyzer(capabilities = baseCapabilities)
        val router = AiRouter(local, cloudAnalyzer = null)

        val result = router.analyze(
            request = request(),
            policy = policy(mode = AiRoutingMode.OnDeviceOnly),
            runtime = runtime(foreground = false),
        )

        assertTrue(result is AiRouterResult.Blocked)
        val localAttempt = (result as AiRouterResult.Blocked).attemptFor(
            AiExecutionTarget.OnDevice,
        )
        assertTrue(AiRouteBlocker.ForegroundRequired in localAttempt.blockers)
        assertEquals(0, local.callCount)
    }

    @Test
    fun `missing local capability selects consented cloud analyzer`() = runBlocking {
        val local = FakeOnDeviceAnalyzer(capabilities = baseCapabilities)
        val cloud = FakeCloudAnalyzer(
            capabilities = baseCapabilities + AiCapability.ToolCalling,
        )
        val router = AiRouter(local, cloud)
        val consent = grantedConsent()

        val result = router.analyze(
            request = request(required = setOf(AiCapability.ToolCalling)),
            policy = policy(cloudConsent = consent),
            runtime = runtime(),
        )

        assertTrue(result is AiRouterResult.Executed)
        result as AiRouterResult.Executed
        assertEquals(AiExecutionTarget.Cloud, result.provenance.target)
        assertTrue(result.provenance.usedCloud)
        assertSame(consent, result.provenance.cloudConsentScope)
        assertEquals(CASE_ID, result.provenance.cloudConsentScope?.caseId)
        assertEquals(
            DIGEST_A,
            result.provenance.cloudConsentScope?.imageContentSha256?.get(IMAGE_ID),
        )
        assertEquals("cloud-analyzer", result.provenance.cloudConsentScope?.cloudAnalyzerId)
        assertEquals("cloud-service", result.provenance.cloudConsentScope?.cloudServiceId)
        assertEquals(1L, result.provenance.cloudConsentScope?.grantedAtEpochMillis)
        assertEquals(0, local.callCount)
        assertEquals(1, cloud.callCount)
    }

    @Test
    fun `cloud use waits for consent when it has not been requested`() = runBlocking {
        val cloud = FakeCloudAnalyzer(capabilities = baseCapabilities)
        val router = AiRouter(onDeviceAnalyzer = null, cloudAnalyzer = cloud)

        val result = router.analyze(
            request = request(),
            policy = policy(mode = AiRoutingMode.CloudOnly),
            runtime = runtime(),
        )

        assertTrue(result is AiRouterResult.Blocked)
        val attempt = (result as AiRouterResult.Blocked).attemptFor(AiExecutionTarget.Cloud)
        assertTrue(AiRouteBlocker.CloudConsentRequired in attempt.blockers)
        assertEquals(0, cloud.callCount)
    }

    @Test
    fun `cloud consent cannot be reused for a different image batch`() = runBlocking {
        val cloud = FakeCloudAnalyzer(capabilities = baseCapabilities)
        val router = AiRouter(onDeviceAnalyzer = null, cloudAnalyzer = cloud)

        val result = router.analyze(
            request = request(),
            policy = policy(
                mode = AiRoutingMode.CloudOnly,
                cloudConsent = grantedConsent(imageId = LocalImageId("another-image")),
            ),
            runtime = runtime(),
        )

        assertTrue(result is AiRouterResult.Blocked)
        val attempt = (result as AiRouterResult.Blocked).attemptFor(AiExecutionTarget.Cloud)
        assertTrue(AiRouteBlocker.CloudConsentScopeMismatch in attempt.blockers)
        assertEquals(0, cloud.callCount)
    }

    @Test
    fun `cloud consent cannot be reused by another analyzer`() = runBlocking {
        val cloud = FakeCloudAnalyzer(
            capabilities = baseCapabilities,
            analyzerId = "replacement-analyzer",
        )
        val router = AiRouter(onDeviceAnalyzer = null, cloudAnalyzer = cloud)

        val result = router.analyze(
            request = request(),
            policy = policy(
                mode = AiRoutingMode.CloudOnly,
                cloudConsent = grantedConsent(),
            ),
            runtime = runtime(),
        )

        assertTrue(result is AiRouterResult.Blocked)
        val attempt = (result as AiRouterResult.Blocked).attemptFor(AiExecutionTarget.Cloud)
        assertTrue(AiRouteBlocker.CloudConsentScopeMismatch in attempt.blockers)
        assertEquals(0, cloud.callCount)
    }

    @Test
    fun `cloud consent cannot be reused by another service`() = runBlocking {
        val cloud = FakeCloudAnalyzer(
            capabilities = baseCapabilities,
            serviceId = "replacement-service",
        )
        val router = AiRouter(onDeviceAnalyzer = null, cloudAnalyzer = cloud)

        val result = router.analyze(
            request = request(),
            policy = policy(
                mode = AiRoutingMode.CloudOnly,
                cloudConsent = grantedConsent(),
            ),
            runtime = runtime(),
        )

        assertTrue(result is AiRouterResult.Blocked)
        val attempt = (result as AiRouterResult.Blocked).attemptFor(AiExecutionTarget.Cloud)
        assertTrue(AiRouteBlocker.CloudConsentScopeMismatch in attempt.blockers)
        assertEquals(0, cloud.callCount)
    }

    @Test
    fun `cloud consent cannot be reused when the same image id has different content`() =
        runBlocking {
            val cloud = FakeCloudAnalyzer(capabilities = baseCapabilities)
            val router = AiRouter(onDeviceAnalyzer = null, cloudAnalyzer = cloud)

            val result = router.analyze(
                request = request(imageSha256 = DIGEST_B),
                policy = policy(
                    mode = AiRoutingMode.CloudOnly,
                    cloudConsent = grantedConsent(imageSha256 = DIGEST_A),
                ),
                runtime = runtime(),
            )

            assertTrue(result is AiRouterResult.Blocked)
            val attempt = (result as AiRouterResult.Blocked).attemptFor(AiExecutionTarget.Cloud)
            assertTrue(AiRouteBlocker.CloudConsentScopeMismatch in attempt.blockers)
            assertEquals(0, cloud.callCount)
        }

    @Test
    fun `cloud consent cannot be reused for another case`() = runBlocking {
        val cloud = FakeCloudAnalyzer(capabilities = baseCapabilities)
        val router = AiRouter(onDeviceAnalyzer = null, cloudAnalyzer = cloud)

        val result = router.analyze(
            request = request(caseId = "case-2"),
            policy = policy(
                mode = AiRoutingMode.CloudOnly,
                cloudConsent = grantedConsent(caseId = CASE_ID),
            ),
            runtime = runtime(),
        )

        assertTrue(result is AiRouterResult.Blocked)
        val attempt = (result as AiRouterResult.Blocked).attemptFor(AiExecutionTarget.Cloud)
        assertTrue(AiRouteBlocker.CloudConsentScopeMismatch in attempt.blockers)
        assertEquals(0, cloud.callCount)
    }

    @Test
    fun `image content digests must be lowercase SHA-256 values`() {
        assertThrows(IllegalArgumentException::class.java) {
            request(imageSha256 = "A".repeat(64))
        }
    }

    @Test
    fun `cloud consent digests must be lowercase SHA-256 values`() {
        assertThrows(IllegalArgumentException::class.java) {
            grantedConsent(imageSha256 = "A".repeat(64))
        }
    }

    @Test
    fun `request requires a digest for every image id`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiAnalysisRequest(
                caseId = CASE_ID,
                imageIds = listOf(IMAGE_ID),
                imageContentSha256 = emptyMap(),
            )
        }
    }

    private fun request(
        required: Set<AiCapability> = emptySet(),
        caseId: String = CASE_ID,
        imageId: LocalImageId = IMAGE_ID,
        imageSha256: String = DIGEST_A,
    ) = AiAnalysisRequest(
        caseId = caseId,
        imageIds = listOf(imageId),
        imageContentSha256 = mapOf(imageId to imageSha256),
        requiredCapabilities = required,
    )

    private fun policy(
        mode: AiRoutingMode = AiRoutingMode.PreferOnDevice,
        cloudConsent: CloudProcessingConsent = CloudProcessingConsent.NotRequested,
    ) = AiRoutingPolicy(mode, cloudConsent)

    private fun grantedConsent(
        caseId: String = CASE_ID,
        imageId: LocalImageId = IMAGE_ID,
        imageSha256: String = DIGEST_A,
        cloudAnalyzerId: String = "cloud-analyzer",
        cloudServiceId: String = "cloud-service",
    ) = CloudProcessingConsent.Granted(
        caseId = caseId,
        imageContentSha256 = mapOf(imageId to imageSha256),
        purpose = com.momonong.pixelreceipt.domain.ai.AiAnalysisPurpose
            .ReceiptEvidenceExtraction,
        cloudAnalyzerId = cloudAnalyzerId,
        cloudServiceId = cloudServiceId,
        grantedAtEpochMillis = 1,
    )

    private fun runtime(
        foreground: Boolean = true,
        network: Boolean = true,
        modelState: OnDeviceModelState = OnDeviceModelState.Available,
    ) = AiRuntimeContext(
        isAppInForeground = foreground,
        isNetworkAvailable = network,
        onDeviceModelState = modelState,
    )

    private companion object {
        const val CASE_ID = "case-1"
        val IMAGE_ID = LocalImageId("image-1")
        val DIGEST_A = "a".repeat(64)
        val DIGEST_B = "b".repeat(64)
        val baseCapabilities = setOf(AiCapability.ImageInput)
    }
}

private fun AiRouterResult.Blocked.attemptFor(
    target: AiExecutionTarget,
) = attempts.single { it.target == target }

private class FakeOnDeviceAnalyzer(
    capabilities: Set<AiCapability>,
) : OnDeviceReceiptAnalyzer {
    override val descriptor = descriptor("local", capabilities)
    var callCount = 0

    override suspend fun analyze(request: AiAnalysisRequest): AnalysisResult {
        callCount += 1
        return successfulAnalysis()
    }
}

private class FakeCloudAnalyzer(
    capabilities: Set<AiCapability>,
    analyzerId: String = "cloud-analyzer",
    serviceId: String = "cloud-service",
) : CloudReceiptAnalyzer {
    override val descriptor = descriptor(
        id = "cloud",
        capabilities = capabilities,
        analyzerId = analyzerId,
        serviceId = serviceId,
    )
    var callCount = 0

    override suspend fun analyze(request: AiAnalysisRequest): AnalysisResult {
        callCount += 1
        return successfulAnalysis()
    }
}

private fun descriptor(
    id: String,
    capabilities: Set<AiCapability>,
    analyzerId: String = "$id-analyzer",
    serviceId: String = "$id-service",
) = AiAnalyzerDescriptor(
    analyzerId = analyzerId,
    serviceId = serviceId,
    modelId = "$id-model",
    promptVersion = "prompt-v1",
    schemaVersion = "receipt-v1",
    capabilities = capabilities,
)

private fun successfulAnalysis() = AnalysisResult.Success(
    AnalyzedReceipt(
        merchantText = null,
        transactionDateText = null,
        totalText = null,
        currencyCodeText = null,
        items = emptyList(),
    ),
)

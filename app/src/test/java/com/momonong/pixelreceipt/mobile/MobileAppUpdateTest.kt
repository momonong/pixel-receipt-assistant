package com.momonong.pixelreceipt.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class MobileAppUpdateTest {
    private val bytes = "synthetic-apk-bytes".toByteArray()
    private fun metadata() = """{"version_code":13,"version_name":"0.5.6","package_name":"com.momonong.pixelreceipt.mobile","size_bytes":${bytes.size},"sha256":"${sha256(bytes)}","signer_sha256":"${"a".repeat(64)}","download_url":"/receipts/v1/mobile/apk"}"""
    @Test fun releaseMetadataOnlyAllowsTheExistingAuthenticatedApkRoute() {
        val value = parseAppRelease(metadata())
        assertEquals(13L, value.versionCode)
        assertEquals("0.5.6", value.versionName)
        for (url in listOf("https://evil.example/apk", "//evil.example/apk", "/receipts/v1/mobile/apk?token=x", "/receipts/v1/mobile/apk/../apk")) {
            try { parseAppRelease(metadata().replace("/receipts/v1/mobile/apk", url)); fail(url) } catch (_: IllegalArgumentException) { }
        }
        for ((from,to) in listOf("\"version_code\":13" to "\"version_code\":13.5", "\"size_bytes\":${bytes.size}" to "\"size_bytes\":134217729", "com.momonong.pixelreceipt.mobile" to "other.app")) {
            try { parseAppRelease(metadata().replace(from,to)); fail(to) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun onlyAnExactSizeAndHashProducesAnInstallableFile() {
        val dir = Files.createTempDirectory("mobile-update").toFile()
        try {
            val file = File(dir,"update.apk"); val release = parseAppRelease(metadata()); val progress = mutableListOf<Long>()
            writeVerifiedUpdate(ByteArrayInputStream(bytes), file, release, progress::add)
            assertArrayEquals(bytes,file.readBytes()); assertEquals(bytes.size.toLong(),progress.last())
            assertFalse(File(dir,"update.apk.part").exists())
            for (bad in listOf(bytes.copyOf(bytes.size-1),bytes+byteArrayOf(1),"bad".repeat(7).toByteArray())) {
                try { writeVerifiedUpdate(ByteArrayInputStream(bad), file, release, {}); fail() } catch (_: java.io.IOException) { }
                assertArrayEquals(bytes,file.readBytes()); assertFalse(File(dir,"update.apk.part").exists())
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun cancellationNeverLeavesAnInstallablePartialFile() {
        val dir = Files.createTempDirectory("mobile-update-cancel").toFile()
        try {
            val file=File(dir,"update.apk")
            try { writeVerifiedUpdate(ByteArrayInputStream(bytes),file,parseAppRelease(metadata()),{}, { throw kotlinx.coroutines.CancellationException() }); fail() }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertFalse(file.exists()); assertFalse(File(dir,"update.apk.part").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun metadataRequestUsesTheExistingSessionWithoutOpeningAWebPage() = kotlinx.coroutines.runBlocking {
        val seen=mutableListOf<MobileRequest>()
        val api=HttpsMobileApi(MobileTransport { request -> seen+=request; metadata() })
        assertEquals(13L,api.appRelease("prm_fixture").versionCode)
        assertEquals("v1/mobile/release",seen.single().path)
        assertEquals("prm_fixture",seen.single().token)
        assertTrue(allowedMobileRequest("v1/mobile/release","GET"))
        assertFalse(allowedMobileRequest("v1/mobile/apk","POST"))
    }
}

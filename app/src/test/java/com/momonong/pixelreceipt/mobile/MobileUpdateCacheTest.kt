package com.momonong.pixelreceipt.mobile

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MobileUpdateCacheTest {
    private val root = Files.createTempDirectory("installed-update-cache").toFile()
    private val cache = File(root, "cache").apply { mkdirs() }
    private val updates = File(cache, "app-updates").apply { mkdirs() }
    private val apk = File(updates, "update.apk").apply { writeText("synthetic installed APK") }
    private val installed = UpdateApkIdentity("com.momonong.pixelreceipt.mobile", 16, "0.7.2", setOf("a".repeat(64)))

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun installedApkIsRemovedWithoutChangingAnyOtherAppData() {
        val protected = listOf("files/evidence/photo.jpg", "noBackup/mobile-intake/state.json",
            "noBackup/mobile-intake/token", "databases/receipts.db", "cache/spending-analysis/summary.json",
            "cache/app-updates/unrelated.bin").associate { relative ->
            val file = File(root, relative).apply { parentFile!!.mkdirs(); writeText("synthetic data: $relative") }
            file to file.readBytes()
        }
        val result = cleanupInstalledUpdateApk(cache, installed) { installed }
        assertEquals(UpdateCacheCleanupResult.Removed, result)
        assertFalse(apk.exists())
        protected.forEach { (file, bytes) -> assertArrayEquals(file.path, bytes, file.readBytes()) }
    }

    @Test fun returningFromCancelledInstallerKeepsTheNotYetInstalledVersion() {
        val oldInstalled = installed.copy(versionCode = 15, versionName = "0.7.1")
        assertEquals(UpdateCacheCleanupResult.Unchanged, cleanupInstalledUpdateApk(cache, oldInstalled) { installed })
        assertTrue(apk.isFile)
    }

    @Test fun anOlderArchiveSupersededByTheInstalledVersionIsRemoved() {
        assertEquals(UpdateCacheCleanupResult.Removed,
            cleanupInstalledUpdateApk(cache, installed) { installed.copy(versionCode = 15, versionName = "0.7.1") })
        assertFalse(apk.exists())
    }

    @Test fun foreignPackagesDifferentSignersAndMissingSignersAreNeverDeleted() {
        val cases = listOf(
            installed to installed.copy(packageName = "other.app"),
            installed.copy(packageName = "other.app") to installed,
            installed to installed.copy(signers = setOf("b".repeat(64))),
            installed to installed.copy(signers = emptySet()),
            installed.copy(signers = emptySet()) to installed.copy(signers = emptySet()),
        )
        cases.forEach { (current, archive) ->
            assertEquals(UpdateCacheCleanupResult.Unchanged, cleanupInstalledUpdateApk(cache, current) { archive })
            assertTrue(apk.isFile)
        }
    }

    @Test fun unreadableArchivesAndEqualCodeWithDifferentVersionNameAreKept() {
        assertEquals(UpdateCacheCleanupResult.Unchanged, cleanupInstalledUpdateApk(cache, installed) { null })
        assertEquals(UpdateCacheCleanupResult.Unchanged,
            cleanupInstalledUpdateApk(cache, installed) { installed.copy(versionName = "0.7.2-other") })
        assertTrue(apk.isFile)
    }

    @Test fun symlinksCannotRedirectCleanupToReceipts() {
        val photo = File(root, "files/receipt.jpg").apply { parentFile!!.mkdirs(); writeText("original receipt") }
        assertTrue(apk.delete())
        Files.createSymbolicLink(apk.toPath(), photo.toPath())
        assertEquals(UpdateCacheCleanupResult.Unchanged, cleanupInstalledUpdateApk(cache, installed) { installed })
        assertTrue(Files.isSymbolicLink(apk.toPath()))
        assertEquals("original receipt", photo.readText())
        assertTrue(apk.delete()); assertTrue(updates.delete())
        val outside = File(root, "files/update.apk").apply { writeText("original non-cache file") }
        Files.createSymbolicLink(updates.toPath(), outside.parentFile!!.toPath())
        assertEquals(UpdateCacheCleanupResult.Unchanged, cleanupInstalledUpdateApk(cache, installed) { installed })
        assertEquals("original non-cache file", outside.readText())
    }

    @Test fun aFailedInspectionDefersCleanupAndRetainsTheCompleteFile() {
        assertEquals(UpdateCacheCleanupResult.Deferred,
            cleanupInstalledUpdateApk(cache, installed) { throw IOException("synthetic inspection failure") })
        assertTrue(apk.isFile)
    }

    @Test fun cleaningAnInstalledApkDoesNotDeleteAnInProgressDownload() {
        val part = File(updates, "update.apk.part").apply { writeText("newer partial transfer") }
        assertEquals(UpdateCacheCleanupResult.Removed, cleanupInstalledUpdateApk(cache, installed) { installed })
        assertEquals("newer partial transfer", part.readText())
    }

    @Test fun aNewDownloadCannotBeDeletedByCleanupOfThePreviousArchive() {
        val inspecting=CountDownLatch(1);val downloadFinished=CountDownLatch(1)
        val error=AtomicReference<Throwable?>()
        val bytes="synthetic newer APK".toByteArray()
        val release=AppRelease(17,"0.7.3",bytes.size.toLong(),sha256(bytes),installed.signers.single())
        val cleanup=Thread {
            try {
                cleanupInstalledUpdateApk(cache,installed){
                    inspecting.countDown()
                    // A commit without the shared lock would replace the inspected archive here.
                    downloadFinished.await(2,TimeUnit.SECONDS)
                    installed
                }
            }catch(failure:Throwable){error.set(failure)}
        }
        val download=Thread {
            try {
                check(inspecting.await(5,TimeUnit.SECONDS))
                writeVerifiedUpdate(bytes.inputStream(),apk,release,{})
                downloadFinished.countDown()
            }catch(failure:Throwable){error.set(failure)}
        }
        cleanup.start();download.start();cleanup.join(7000);download.join(7000)
        assertFalse(cleanup.isAlive);assertFalse(download.isAlive)
        error.get()?.let{throw AssertionError(it)}
        assertTrue(apk.isFile);assertArrayEquals(bytes,apk.readBytes())
        assertFalse(File(updates,"update.apk.part").exists())
    }
}

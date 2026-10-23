package com.momonong.pixelreceipt.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MobileCredentialsTest {
    @Test fun encryptedTokenSurvivesReopenAndAtomicBackupThenLogoutRemovesIt() {
        val root = File(ApplicationProvider.getApplicationContext<Application>().noBackupFilesDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        try {
            val token = "test-session-for-encryption-only"
            KeystoreTokenVault(root) { key }.save(token)
            val file = File(root, "credential.enc")
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(token))
            assertEquals(token, KeystoreTokenVault(root) { key }.read())
            assertTrue(file.renameTo(File(root, "credential.enc.bak")))
            val reopened = KeystoreTokenVault(root) { key }
            assertEquals(token, reopened.read())
            reopened.clear(); assertNull(reopened.read())
        } finally { root.deleteRecursively() }
    }
    @Test fun lostKeyRequiresLoginWithoutDeletingBatches() {
        val root = File(ApplicationProvider.getApplicationContext<Application>().noBackupFilesDir, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val first = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val second = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val store = MobileStore(root); val batch = store.create(UUID.randomUUID().toString(), "保留照片說明")
            KeystoreTokenVault(root) { first }.save("test-session")
            assertNull(KeystoreTokenVault(root) { second }.read())
            assertEquals(batch, MobileStore(root).batch(batch.id))
        } finally { root.deleteRecursively() }
    }
    @Test fun noCredentialOrPayloadCanLeakThroughRequestOrErrorStrings() {
        val secret = "fake-test-credential"
        val request = MobileRequest("v1/mobile/login", "POST", secret, json = "{password:$secret}")
        assertFalse(request.toString().contains(secret))
        assertFalse(mobileError(IllegalArgumentException(secret)).contains(secret))
        assertFalse(mobileError(java.io.IOException(secret)).contains(secret))
    }
    @Test fun backupAndDeviceTransferPoliciesExcludeApplicationFiles() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(manifest.contains("android.permission.INTERNET"))
        val backup = File("src/main/res/xml/backup_rules.xml").readText()
        val transfer = File("src/main/res/xml/data_extraction_rules.xml").readText()
        listOf("root", "file", "database", "sharedpref").forEach {
            assertTrue(backup.contains("domain=\"$it\" path=\".\""))
            assertEquals(2, Regex("domain=\"$it\" path=\".\"").findAll(transfer).count())
        }
    }
}

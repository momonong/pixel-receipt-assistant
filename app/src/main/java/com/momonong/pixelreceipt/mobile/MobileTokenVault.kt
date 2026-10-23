package com.momonong.pixelreceipt.mobile

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface TokenVault {
    fun read(): String?
    fun save(token: String)
    fun clear()
}

/** Only ciphertext is written, exclusively in noBackupFilesDir. Never logs credential material. */
class KeystoreTokenVault internal constructor(root: File, private val keyProvider: (() -> SecretKey)? = null) : TokenVault {
    private val file = AtomicFile(File(root, "credential.enc"))
    private val alias = "pixelreceipt.mobile.ingest.v1"
    private fun key(): SecretKey {
        keyProvider?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized override fun read(): String? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return try {
            val bytes = file.openRead().use { readBounded(it, 16384) }
            check(bytes.size in 30..16384)
            val buffer = ByteBuffer.wrap(bytes)
            val ivLength = buffer.get().toInt()
            check(ivLength == 12 && buffer.remaining() > ivLength)
            val iv = ByteArray(ivLength).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                doFinal(ciphertext).toString(Charsets.UTF_8)
            }
        } catch (_: Exception) { clear(); null } // Lost/inaccessible key requires a fresh login; batches are untouched.
    }
    @Synchronized override fun save(token: String) {
        require(token.isNotBlank() && token.length <= 8192 && token.none { it.isISOControl() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try {
            output.write(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
            file.finishWrite(output)
        } catch (error: Exception) { file.failWrite(output); throw error }
    }
    @Synchronized override fun clear() { file.delete() }
}

package com.superbot.sati.ai

import android.content.Context
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

internal interface ApiKeyStore {
    fun load(): String?
    fun save(key: String)
    fun clear()
}

/** Only ciphertext lives on disk. The wrapping key stays in Android Keystore. */
internal class AndroidApiKeyStore(
    context: Context,
    private val file: File = File(context.noBackupFilesDir, "credentials/deepseek.key"),
    private val alias: String = "sati.deepseek.api_key",
) : ApiKeyStore {
    private val atomic = AtomicFile(file)

    @Synchronized
    override fun load(): String? {
        try {
            val bytes = atomic.openRead().use { it.readBytes() }
            require(bytes.size in 30..8192)
            val data = ByteBuffer.wrap(bytes)
            require(data.get().toInt() == 1)
            val iv = ByteArray(12).also(data::get)
            val encrypted = ByteArray(data.remaining()).also(data::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(create = false), GCMParameterSpec(128, iv))
            return cipher.doFinal(encrypted).toString(Charsets.UTF_8).also {
                require(validApiKey(it))
            }
        } catch (_: java.io.FileNotFoundException) {
            return null
        } catch (_: Exception) {
            // Lost/invalidated Keystore keys and corrupt files require entering the key again.
            atomic.delete()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            }
            return null
        }
    }

    @Synchronized
    override fun save(key: String) {
        require(validApiKey(key))
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(create = true))
        val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val bytes = ByteBuffer.allocate(1 + 12 + encrypted.size)
            .put(1.toByte()).put(cipher.iv).put(encrypted).array()
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    @Synchronized
    override fun clear() {
        atomic.delete()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun wrappingKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
}

internal fun validApiKey(key: String): Boolean =
    key.isNotEmpty() && key.length <= 4096 && key.all { it.code in 33..126 }

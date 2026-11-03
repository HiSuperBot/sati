package com.superbot.sati

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.superbot.sati.ai.AndroidApiKeyStore
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApiKeyStoreTest {
    private fun withStore(test: (File, AndroidApiKeyStore, () -> AndroidApiKeyStore) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "key-test-$id/credential")
        val alias = "sati.test.$id"
        val factory = { AndroidApiKeyStore(context, file, alias) }
        val store = factory()
        try { test(file, store, factory) } finally {
            store.clear()
            file.parentFile!!.deleteRecursively()
        }
    }

    @Test
    fun encryptsWithFreshIvAndRestoresAcrossStoreInstances() = withStore { file, store, factory ->
        store.save("test-only-secret")
        val first = file.readBytes()
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("test-only-secret"))
        assertEquals("test-only-secret", factory().load())
        store.save("test-only-secret")
        assertFalse(first.contentEquals(file.readBytes()))
        assertEquals("test-only-secret", factory().load())
    }

    @Test
    fun removeClearsBothCiphertextAndTheSavedCredential() = withStore { file, store, factory ->
        store.save("test-only-secret")
        store.clear()
        assertFalse(file.exists())
        assertNull(factory().load())
        store.save("new-test-secret")
        assertEquals("new-test-secret", factory().load())
    }

    @Test
    fun providerCredentialsUseIndependentCiphertextAndKeystoreAliases() =
        withStore { _, groq, groqFactory ->
            withStore { _, deepSeek, deepFactory ->
                groq.save("groq-test-key")
                deepSeek.save("deepseek-test-key")
                assertEquals("groq-test-key", groqFactory().load())
                assertEquals("deepseek-test-key", deepFactory().load())
                groq.clear()
                assertNull(groqFactory().load())
                assertEquals("deepseek-test-key", deepFactory().load())
            }
        }

    @Test
    fun tamperingRequiresReentryAndAllowsSavingANewKey() = withStore { file, store, factory ->
        store.save("test-only-secret")
        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertNull(factory().load())
        assertFalse(file.exists())
        store.save("replacement-test-secret")
        assertEquals("replacement-test-secret", factory().load())
    }
}

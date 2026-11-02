package com.superbot.sati.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class DeepSeekKeySetupTest {
    private class Store(var key: String? = null) : ApiKeyStore {
        var writes = 0
        var failSaving = false
        var beforeSave: ((String) -> Unit)? = null
        var beforeClear: (() -> Unit)? = null
        override fun load() = key
        override fun save(key: String) {
            if (failSaving) error("Storage unavailable")
            beforeSave?.invoke(key)
            this.key = key
            writes++
        }
        override fun clear() { beforeClear?.invoke(); key = null }
    }
    private class Service : DeepSeekService {
        var failure: ReplyFailure? = null
        var entered = CompletableDeferred<Unit>()
        var release: CompletableDeferred<Unit>? = null
        override suspend fun validateKey(key: String) {
            entered.complete(Unit)
            release?.let { withContext(NonCancellable) { it.await() } }
            failure?.let { throw ReplyException(it) }
        }
        override fun stream(key: String, input: String, history: List<ConversationTurn>) =
            error("Setup must not generate a billable reply")
    }
    private suspend fun settle(scope: CoroutineScope) =
        scope.coroutineContext[Job]!!.children.toList().joinAll()

    @Test
    fun validatesSavesAndConnectsOnlyWhenRequested() = runBlocking {
        val store = Store()
        var connections = 0
        val setup = DeepSeekKeySetup(this, store, Service()) { connections++ }
        setup.awaitLoaded()
        setup.open()
        setup.edit("  test-key  ")
        assertEquals(0, connections)
        assertEquals(0, store.writes)
        setup.connect()
        settle(this)
        assertEquals("test-key", store.key)
        assertTrue(setup.status.value.hasKey)
        assertEquals(1, connections)
        assertFalse(setup.state.value.isOpen)
        assertEquals("", setup.state.value.draft)
    }

    @Test
    fun invalidReplacementPreservesTheExistingKeyAndSelection() = runBlocking {
        val store = Store("old-key")
        val service = Service().apply { failure = ReplyFailure.Authentication }
        var connections = 0
        val setup = DeepSeekKeySetup(this, store, service) { connections++ }
        setup.awaitLoaded()
        setup.open()
        setup.edit("wrong-key")
        setup.connect()
        settle(this)
        assertEquals("old-key", setup.key())
        assertEquals("old-key", store.key)
        assertEquals(0, connections)
        assertEquals(ReplyFailure.Authentication, setup.state.value.failure)
    }

    @Test
    fun canceledValidationCannotSaveOrSwitchAfterALateResponse() = runBlocking {
        val store = Store("old-key")
        val service = Service().apply { release = CompletableDeferred() }
        var connections = 0
        val setup = DeepSeekKeySetup(this, store, service) { connections++ }
        setup.awaitLoaded()
        setup.open()
        setup.edit("new-key")
        setup.connect()
        service.entered.await()
        setup.dismiss()
        service.release!!.complete(Unit)
        settle(this)
        assertEquals("old-key", store.key)
        assertEquals(0, connections)
        assertEquals("", setup.state.value.draft)
        assertFalse(setup.state.value.isOpen)
    }

    @Test
    fun storageFailureDoesNotActivateTheCloudModel() = runBlocking {
        val store = Store().apply { failSaving = true }
        var connections = 0
        val setup = DeepSeekKeySetup(this, store, Service()) { connections++ }
        setup.awaitLoaded()
        setup.open()
        setup.edit("test-key")
        setup.connect()
        settle(this)
        assertEquals(ReplyFailure.Storage, setup.state.value.failure)
        assertEquals(0, connections)
        assertFalse(setup.status.value.hasKey)
    }

    @Test
    fun cancelDuringDiskCommitRestoresThePreviousCredential() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store = Store("old-key").apply {
            beforeSave = { key ->
                if (key == "new-key") {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
        }
        var connections = 0
        val setup = DeepSeekKeySetup(this, store, Service()) { connections++ }
        setup.awaitLoaded()
        setup.open()
        setup.edit("new-key")
        setup.connect()
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        setup.dismiss()
        release.countDown()
        settle(this)
        assertEquals("old-key", store.key)
        assertEquals("old-key", setup.key())
        assertEquals(0, connections)
    }

    @Test
    fun removingAKeyClearsItsSavedState() = runBlocking {
        val store = Store("test-key")
        val setup = DeepSeekKeySetup(this, store, Service()) {}
        setup.awaitLoaded()
        assertTrue(setup.status.value.hasKey)
        setup.remove()
        assertNull(store.key)
        assertNull(setup.key())
        assertFalse(setup.status.value.hasKey)
        assertEquals(1L, setup.status.value.revision)
    }

    @Test
    fun removalDisablesTheKeyBeforeTheDiskOperationFinishes() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val store = Store("test-key").apply {
            beforeClear = {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val setup = DeepSeekKeySetup(this, store, Service()) {}
        setup.awaitLoaded()
        val removal = async { setup.remove() }
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            assertFalse(setup.status.value.hasKey)
            assertNull(setup.key())
        } finally {
            release.countDown()
        }
        removal.await()
        assertNull(store.key)
    }

    @Test
    fun rejectsBlankAndHeaderInjectionAndRedactsDraftFromDiagnostics() = runBlocking {
        val store = Store()
        val setup = DeepSeekKeySetup(this, store, Service()) {}
        setup.awaitLoaded()
        setup.open()
        for (invalid in listOf(" ", "key\r\nAuthorization:bad", "key with spaces")) {
            setup.edit(invalid)
            setup.connect()
            assertTrue(setup.state.value.invalidInput)
            assertEquals(0, store.writes)
        }
        setup.edit("sensitive-test-key")
        assertFalse(setup.state.value.toString().contains("sensitive-test-key"))
        setup.dismiss()
        assertEquals("", setup.state.value.draft)
    }
}

package com.superbot.sati.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ApiKeyStatus(val loaded: Boolean = false, val hasKey: Boolean = false, val revision: Long = 0)

data class ApiKeySetupState(
    val isOpen: Boolean = false,
    val draft: String = "",
    val isValidating: Boolean = false,
    val invalidInput: Boolean = false,
    val failure: ReplyFailure? = null,
) {
    override fun toString() =
        "ApiKeySetupState(open=$isOpen, validating=$isValidating, failure=$failure, draft=[redacted])"
}

/** Owned by a ViewModel; drafts survive rotation but never enter Android saved state. */
internal class DeepSeekKeySetup(
    private val scope: CoroutineScope,
    private val store: ApiKeyStore,
    private val client: DeepSeekService,
    private val onConnected: () -> Unit,
) {
    private val mutableState = MutableStateFlow(ApiKeySetupState())
    val state = mutableState.asStateFlow()
    private val mutableStatus = MutableStateFlow(ApiKeyStatus())
    val status = mutableStatus.asStateFlow()
    @Volatile private var savedKey: String? = null
    private var request = 0L
    private var validation: Job? = null
    private val credentialWrites = Mutex()
    private val restore = scope.launch {
        savedKey = withContext(Dispatchers.IO) { runCatching { store.load() }.getOrNull() }
        mutableStatus.value = ApiKeyStatus(loaded = true, hasKey = savedKey != null)
    }

    suspend fun awaitLoaded() = restore.join()
    fun key(): String? = savedKey

    fun open() {
        dismiss()
        mutableState.value = ApiKeySetupState(isOpen = true)
    }

    fun edit(draft: String) {
        if (!state.value.isOpen || state.value.isValidating) return
        mutableState.value = state.value.copy(draft = draft, invalidInput = false, failure = null)
    }

    fun pauseValidation() {
        request++
        validation?.cancel()
        mutableState.value = state.value.copy(isValidating = false)
    }

    fun dismiss() {
        pauseValidation()
        mutableState.value = ApiKeySetupState()
    }

    fun connect() {
        if (!state.value.isOpen || state.value.isValidating) return
        val key = state.value.draft.trim()
        if (!validApiKey(key)) {
            mutableState.value = state.value.copy(invalidInput = true)
            return
        }
        val current = ++request
        mutableState.value = state.value.copy(isValidating = true, failure = null, invalidInput = false)
        validation = scope.launch {
            try {
                awaitLoaded()
                client.validateKey(key)
                currentCoroutineContext().ensureActive()
                if (current != request) return@launch
                credentialWrites.withLock {
                    val previous = savedKey
                    val written = AtomicBoolean()
                    try {
                        withContext(Dispatchers.IO) {
                            currentCoroutineContext().ensureActive()
                            store.save(key)
                            written.set(true)
                        }
                        currentCoroutineContext().ensureActive()
                        if (current != request) throw CancellationException()
                        savedKey = key
                        mutableStatus.value = status.value.copy(
                            hasKey = true, revision = status.value.revision + 1,
                        )
                    } catch (canceled: CancellationException) {
                        // Cancellation may arrive after the atomic disk write completed.
                        // Restore the old credential before another save/removal can run.
                        if (written.get()) withContext(NonCancellable + Dispatchers.IO) {
                            if (previous == null) store.clear() else store.save(previous)
                        }
                        throw canceled
                    } catch (_: Exception) {
                        throw ReplyException(ReplyFailure.Storage)
                    }
                }
                mutableState.value = ApiKeySetupState()
                onConnected()
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: ReplyException) {
                if (current == request) mutableState.value = state.value.copy(
                    isValidating = false, failure = failure.failure,
                )
            } catch (_: Exception) {
                if (current == request) mutableState.value = state.value.copy(
                    isValidating = false, failure = ReplyFailure.Network,
                )
            }
        }
    }

    suspend fun remove() {
        dismiss()
        awaitLoaded()
        val previous = savedKey
        savedKey = null
        mutableStatus.value = status.value.copy(hasKey = false, revision = status.value.revision + 1)
        try {
            credentialWrites.withLock {
                withContext(Dispatchers.IO) { store.clear() }
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (error: Exception) {
            savedKey = previous
            mutableStatus.value = status.value.copy(hasKey = previous != null)
            throw error
        }
    }
}

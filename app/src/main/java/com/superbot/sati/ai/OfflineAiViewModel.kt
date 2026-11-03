package com.superbot.sati.ai

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.superbot.sati.conversation.ReplyCallbacks
import com.superbot.sati.conversation.ReplyGenerator
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class ModelStatus { Missing, Downloading, Loading, Ready, Failed, NeedsApiKey }
enum class ModelProblem { DownloadFailed, VerificationFailed, DownloadCanceled, LoadFailed, UnsupportedDevice, NanoUnavailable, CredentialStorage }

data class AiModelState(
    val status: ModelStatus = ModelStatus.Loading,
    val problem: ModelProblem? = null,
    val downloadedBytes: Long = 0,
    val model: AiModel = AiModel.Qwen,
) {
    val isBusy: Boolean get() = status == ModelStatus.Downloading || status == ModelStatus.Loading
    val downloadPercent: Int
        get() = if (model.downloadBytes > 0)
            (downloadedBytes * 100 / model.downloadBytes).toInt().coerceIn(0, 100) else 0
}

/** Local runtimes stay on one owner thread; only the selected cloud backend sends chat requests. */
class AiViewModel(app: Application) : AndroidViewModel(app), ReplyGenerator {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val keyScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val deepSeek = DeepSeekClient()
    private val mutableQuotas = MutableStateFlow<Map<String, RateLimitSnapshot>>(emptyMap())
    val quotas = mutableQuotas.asStateFlow()
    internal val groq = GroqClient(onQuota = { quota ->
        mutableQuotas.update { it + (quota.modelId to quota) }
    })
    private var pendingApply: (() -> Unit)? = null
    private var keyProvider = CloudProvider.DeepSeek
    private val keys = DeepSeekKeySetup(
        keyScope, AndroidApiKeyStore(app), deepSeek,
        onConnected = { credentialConnected(CloudProvider.DeepSeek) },
    )
    private val groqKeys = DeepSeekKeySetup(
        keyScope,
        AndroidApiKeyStore(app, File(app.noBackupFilesDir, "credentials/groq.key"), "sati.groq.api_key"),
        groq, onConnected = { credentialConnected(CloudProvider.Groq) }, provider = CloudProvider.Groq,
    )
    private val mutableKeySetup = MutableStateFlow(ApiKeySetupState())
    val keySetup = mutableKeySetup.asStateFlow()
    val keyStatus = keys.status
    val groqKeyStatus = groqKeys.status

    private fun credentials(provider: CloudProvider) =
        if (provider == CloudProvider.Groq) groqKeys else keys
    internal fun groqKey(): String? = groqKeys.key()

    fun openKeySetup(provider: CloudProvider = CloudProvider.DeepSeek) {
        pendingApply = null
        keys.dismiss()
        groqKeys.dismiss()
        keyProvider = provider
        credentials(provider).open()
        mutableKeySetup.value = credentials(provider).state.value
    }
    fun editApiKey(draft: String) = credentials(keyProvider).edit(draft.take(4097))
    fun connectApiKey() = credentials(keyProvider).connect()
    fun dismissKeySetup() {
        pendingApply = null
        keys.dismiss()
        groqKeys.dismiss()
        mutableKeySetup.value = ApiKeySetupState(provider = keyProvider)
    }
    fun pauseKeyValidation() {
        keys.pauseValidation()
        groqKeys.pauseValidation()
    }
    private fun credentialConnected(provider: CloudProvider) {
        val apply = pendingApply
        pendingApply = null
        if (apply != null) apply()
        else if (state.value.model.provider == provider) activateModel(state.value.model, force = true)
    }
    private fun withKey(provider: CloudProvider, apply: () -> Unit) {
        if (credentials(provider).key() != null) apply()
        else {
            openKeySetup(provider)
            pendingApply = apply
        }
    }
    fun removeApiKey(provider: CloudProvider = CloudProvider.DeepSeek) {
        if (disposed.get()) return
        cancel()
        dismissKeySetup()
        if (state.value.model.provider == provider) {
            setupRequest.incrementAndGet()
            setupJob?.cancel()
            memory.clear()
            mutableState.value = state.value.copy(status = ModelStatus.NeedsApiKey, problem = null)
        }
        keyScope.launch {
            try { credentials(provider).remove() }
            catch (_: CancellationException) { /* ViewModel disposal. */ }
            catch (_: Exception) {
                credentials(provider).reportStorageFailure()
                keyProvider = provider
                mutableKeySetup.value = credentials(provider).state.value
                if (state.value.model.provider == provider) modelFailed(ModelProblem.CredentialStorage)
            }
        }
    }
    private val requestId = AtomicLong()
    private val setupRequest = AtomicLong()
    private val disposed = AtomicBoolean()
    private val directory = File(app.noBackupFilesDir, "models")
    private val preferences = app.getSharedPreferences("offline_models", Application.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(AiModelState(
        model = AiModel.fromId(preferences.getString("selected_model", null)),
    ))
    private val mutableConfig = MutableStateFlow(PipelineConfig(
        stt = SttModel.fromId(preferences.getString("selected_stt", null)),
        chat = mutableState.value.model,
        tts = TtsModel.fromId(preferences.getString("selected_tts", null)),
        voice = OrpheusVoice.fromId(preferences.getString("selected_voice", null)),
    ))
    val config = mutableConfig.asStateFlow()
    private val mutableNanoAvailability = MutableStateFlow(NanoAvailability.Checking)
    val nanoAvailability = mutableNanoAvailability.asStateFlow()
    private var nano: NanoModel? = null
    private var nanoCheckJob: Job? = null
    private var generationJob: Job? = null
    val state = mutableState.asStateFlow()
    private val memory = ConversationMemory()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var generating = false
    private var setupJob: Job? = null

    override val isReady: Boolean
        get() = !disposed.get() && state.value.status == ModelStatus.Ready

    init {
        keyScope.launch {
            combine(keys.state, groqKeys.state) { deep, groq ->
                if (keyProvider == CloudProvider.Groq) groq else deep
            }.collect { mutableKeySetup.value = it }
        }
        refreshNanoAvailability()
        beginSetup()
    }

    fun refreshNanoAvailability() {
        if (disposed.get() || nanoCheckJob?.isActive == true) return
        mutableNanoAvailability.value = NanoAvailability.Checking
        nanoCheckJob = scope.launch {
            mutableNanoAvailability.value = try {
                withTimeout(15_000L) { nanoClient().availability() }
            } catch (canceled: CancellationException) {
                if (canceled !is TimeoutCancellationException) throw canceled
                NanoAvailability.CheckFailed
            } catch (_: Exception) {
                NanoAvailability.CheckFailed
            } catch (_: LinkageError) {
                NanoAvailability.Unavailable
            }
            Log.i("SatiModels", "Gemini Nano availability=${mutableNanoAvailability.value}")
        }
    }

    fun selectModel(model: AiModel) {
        if (disposed.get()) return
        val provider = model.provider
        if (provider != null) withKey(provider) { activateModel(model) }
        else {
            dismissKeySetup()
            activateModel(model)
        }
    }

    fun selectStt(model: SttModel) {
        val apply = {
            preferences.edit().putString("selected_stt", model.id).apply()
            mutableConfig.value = config.value.copy(stt = model)
        }
        if (model.isCloud) withKey(CloudProvider.Groq, apply) else apply()
    }

    fun selectTts(model: TtsModel) {
        val apply = {
            preferences.edit().putString("selected_tts", model.id).apply()
            mutableConfig.value = config.value.copy(tts = model)
        }
        if (model.isCloud) withKey(CloudProvider.Groq, apply) else apply()
    }

    fun selectVoice(voice: OrpheusVoice) {
        preferences.edit().putString("selected_voice", voice.id).apply()
        mutableConfig.value = config.value.copy(voice = voice)
    }

    val pipelineReady: Boolean
        get() = isReady && (!config.value.usesGroq || groqKeyStatus.value.hasKey)
    private fun activateModel(model: AiModel, force: Boolean = false) {
        if (disposed.get() || (!force && model == state.value.model)) return
        if (model == AiModel.GeminiNano && !nanoAvailability.value.canSelect) return
        cancel()
        setupJob?.cancel()
        memory.clear()
        preferences.edit().putString("selected_model", model.id).apply()
        mutableConfig.value = config.value.copy(chat = model)
        mutableState.value = AiModelState(model = model)
        beginSetup()
    }

    private fun nanoClient(): NanoModel = nano ?: NanoModel().also { nano = it }

    fun retrySetup() {
        if (!disposed.get() && !state.value.isBusy) beginSetup()
    }

    fun cancelDownload() {
        if (state.value.status != ModelStatus.Downloading) return
        setupRequest.incrementAndGet()
        setupJob?.cancel()
        mutableState.value = state.value.copy(
            status = ModelStatus.Missing, problem = ModelProblem.DownloadCanceled,
        )
    }

    private fun beginSetup() {
        val setup = setupRequest.incrementAndGet()
        val selected = state.value.model
        mutableState.value = AiModelState(status = ModelStatus.Loading, model = selected)
        setupJob = scope.launch {
            mutex.withLock {
                stopGeneration()
                try {
                    if (selected.isCloud) {
                        releaseEngine()
                        val credentials = credentials(checkNotNull(selected.provider))
                        credentials.awaitLoaded()
                        currentCoroutineContext().ensureActive()
                        if (setup != setupRequest.get()) return@withLock
                        mutableState.value = state.value.copy(
                            status = if (credentials.key() != null) ModelStatus.Ready else ModelStatus.NeedsApiKey,
                            problem = null,
                        )
                        return@withLock
                    }
                    if (selected == AiModel.GeminiNano) {
                        prepareNano(setup)
                        return@withLock
                    }
                    val downloader = ModelDownloader(checkNotNull(selected.spec))
                    val file = withContext(Dispatchers.IO) {
                        val context = currentCoroutineContext()
                        downloader.obtain(
                            directory,
                            checkCanceled = { context.ensureActive() },
                            onProgress = { bytes ->
                                if (!disposed.get() && setup == setupRequest.get()) {
                                    mutableState.value = AiModelState(
                                        status = ModelStatus.Downloading, downloadedBytes = bytes,
                                        model = selected,
                                    )
                                }
                            },
                        )
                    }
                    if (!disposed.get() && setup == setupRequest.get()) load(file, selected)
                } catch (_: TimeoutCancellationException) {
                    if (setup == setupRequest.get()) modelFailed(ModelProblem.DownloadFailed)
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (_: ModelVerificationException) {
                    if (setup == setupRequest.get()) modelFailed(ModelProblem.VerificationFailed)
                } catch (_: Exception) {
                    if (setup == setupRequest.get()) modelFailed(ModelProblem.DownloadFailed)
                }
            }
        }
    }

    private suspend fun prepareNano(setup: Long) {
        releaseEngine()
        val client = nanoClient()
        val available = withTimeout(15_000L) { client.availability() }
        mutableNanoAvailability.value = available
        if (available == NanoAvailability.Unavailable) {
            modelFailed(ModelProblem.NanoUnavailable)
            return
        }
        if (available != NanoAvailability.Available) {
            mutableState.value = state.value.copy(status = ModelStatus.Downloading)
            withTimeout(15 * 60_000L) {
                client.download { bytes ->
                    if (!disposed.get() && setup == setupRequest.get()) {
                        mutableState.value = state.value.copy(
                            status = ModelStatus.Downloading, downloadedBytes = bytes,
                        )
                    }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (setup != setupRequest.get()) return
        check(client.availability() == NanoAvailability.Available)
        mutableNanoAvailability.value = NanoAvailability.Available
        memory.clear()
        mutableState.value = AiModelState(
            status = ModelStatus.Ready, model = AiModel.GeminiNano,
        )
    }

    private suspend fun load(file: File, selected: AiModel) {
        currentCoroutineContext().ensureActive()
        mutableState.value = AiModelState(status = ModelStatus.Loading, model = selected)
        releaseEngine()
        try {
            val config = EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU(),
                cacheDir = getApplication<Application>().cacheDir.absolutePath,
            )
            // Do not store or close an engine whose initialize() failed.
            engine = Engine(config).also { it.initialize() }
            currentCoroutineContext().ensureActive()
            memory.clear()
            mutableState.value = AiModelState(status = ModelStatus.Ready, model = selected)
        } catch (canceled: CancellationException) {
            releaseEngine()
            throw canceled
        } catch (_: LinkageError) {
            currentCoroutineContext().ensureActive()
            modelFailed(ModelProblem.UnsupportedDevice)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            modelFailed(ModelProblem.LoadFailed)
        }
    }

    override fun generate(text: String, callbacks: ReplyCallbacks) {
        val request = requestId.incrementAndGet()
        generationJob = scope.launch {
            try {
                mutex.withLock {
                    stopGeneration()
                    if (disposed.get() || request != requestId.get()) return@launch
                    if (!isReady) {
                        deliver(request) { callbacks.onError(ReplyFailure.Generation) }
                        return@launch
                    }
                    generating = true
                    if (state.value.model == AiModel.GeminiNano || state.value.model.isCloud) return@withLock
                    releaseConversation()
                    val history = memory.snapshot().flatMap {
                        listOf(Message.user(it.user), Message.model(it.reply))
                    }
                    conversation = checkNotNull(engine).createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(SatiInstructions.system),
                            initialMessages = history,
                            samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.6),
                            maxOutputToken = 128,
                            thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                        ),
                    )
                }
                val response = StringBuilder()
                withTimeout(90_000L) {
                    if (state.value.model == AiModel.DeepSeek) {
                        val key = keys.key() ?: throw ReplyException(ReplyFailure.Authentication)
                        deepSeek.stream(key, text, memory.snapshot()).collect { delta ->
                            response.append(delta)
                            val partial = response.toString()
                            deliver(request) { callbacks.onPartial(partial) }
                        }
                    } else if (state.value.model.provider == CloudProvider.Groq) {
                        val key = groqKeys.key() ?: throw StageException(StageFailure(
                            PipelineStage.Chat, CloudProvider.Groq, state.value.model.id, ReplyFailure.Authentication,
                        ))
                        groq.stream(key, state.value.model, text, memory.snapshot()).collect { delta ->
                            response.append(delta)
                            val partial = response.toString()
                            deliver(request) { callbacks.onPartial(partial) }
                        }
                    } else if (state.value.model == AiModel.GeminiNano) {
                        val reply = nanoClient().generate(text, memory.snapshot()) { partial ->
                            deliver(request) { callbacks.onPartial(partial) }
                        }
                        response.append(reply)
                    } else {
                        checkNotNull(conversation).sendMessageAsync(text).collect { message ->
                            response.append(message.toString())
                            val partial = response.toString()
                            deliver(request) { callbacks.onPartial(partial) }
                        }
                    }
                }
                val reply = response.toString().trim()
                if (request == requestId.get() && reply.isNotEmpty()) {
                    deliver(request) {
                        // Commit only when the response is actually accepted on the UI thread.
                        memory.remember(text, reply)
                        callbacks.onComplete(reply)
                    }
                } else if (request == requestId.get()) {
                    deliver(request) { callbacks.onError(ReplyFailure.Generation) }
                }
            } catch (_: TimeoutCancellationException) {
                runCatching { conversation?.cancelProcess() }
                deliver(request) { callbacks.onError(
                    if (state.value.model.isCloud) ReplyFailure.Network else ReplyFailure.Generation,
                ) }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: StageException) {
                if (request == requestId.get()) {
                    deliver(request) { callbacks.onStageError(failure.failure) }
                }
            } catch (failure: ReplyException) {
                if (request == requestId.get()) {
                    if (failure.failure == ReplyFailure.Authentication) {
                        mutableState.value = state.value.copy(status = ModelStatus.NeedsApiKey)
                    }
                    deliver(request) { callbacks.onError(failure.failure) }
                }
            } catch (_: LinkageError) {
                modelFailed(ModelProblem.UnsupportedDevice)
                deliver(request) { callbacks.onError(ReplyFailure.Generation) }
            } catch (_: Exception) {
                deliver(request) { callbacks.onError(ReplyFailure.Generation) }
            } finally {
                generating = false
            }
        }
    }

    override fun cancel() {
        requestId.incrementAndGet()
        if (state.value.model == AiModel.GeminiNano || state.value.model.isCloud) generationJob?.cancel()
        if (!disposed.get()) scope.launch { mutex.withLock { stopGeneration() } }
    }

    override fun reset() {
        requestId.incrementAndGet()
        if (state.value.model == AiModel.GeminiNano || state.value.model.isCloud) generationJob?.cancel()
        if (!disposed.get()) scope.launch {
            mutex.withLock {
                stopGeneration()
                releaseConversation()
                memory.clear()
            }
        }
    }

    private suspend fun stopGeneration() {
        while (generating) {
            runCatching { conversation?.cancelProcess() }
            delay(50)
        }
    }

    private fun releaseConversation() {
        val previous = conversation
        conversation = null
        runCatching { previous?.close() }
    }

    private suspend fun releaseEngine() {
        stopGeneration()
        try {
            releaseConversation()
        } finally {
            val previous = engine
            engine = null
            runCatching { previous?.close() }
        }
    }

    private fun modelFailed(problem: ModelProblem) {
        mutableState.value = state.value.copy(
            status = ModelStatus.Failed, problem = problem,
        )
    }

    private fun deliver(request: Long, block: () -> Unit) {
        main.post { if (!disposed.get() && request == requestId.get()) block() }
    }

    override fun onCleared() {
        disposed.set(true)
        keyScope.cancel()
        setupRequest.incrementAndGet()
        setupJob?.cancel()
        nanoCheckJob?.cancel()
        requestId.incrementAndGet()
        if (state.value.model == AiModel.GeminiNano || state.value.model.isCloud) generationJob?.cancel()
        scope.launch {
            mutex.withLock { releaseEngine() }
            runCatching { nano?.close() }
            nano = null
        }.invokeOnCompletion {
            scope.cancel()
            dispatcher.close()
        }
    }

}

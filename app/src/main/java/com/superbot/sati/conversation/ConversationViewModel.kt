package com.superbot.sati.conversation

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import com.superbot.sati.ai.ReplyFailure
import com.superbot.sati.ai.StageFailure
import com.superbot.sati.ai.PipelineStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConversationPhase { Idle, PreparingSpeech, Listening, Processing, Speaking, Error }

enum class SpeechProblem {
    PermissionDenied, PermissionSettings, RecognitionUnavailable, SpeechDownloadPending,
    SpeechDownloadFailed, NoSpeech,
    Network, RecognitionFailed, SpeechUnavailable, EnglishUnavailable, KoreanUnavailable,
    PlaybackFailed, ModelUnavailable, GenerationFailed, InputTooLong
}

data class ConversationState(
    val phase: ConversationPhase = ConversationPhase.Idle,
    val transcript: String = "",
    val reply: String = "",
    val problem: SpeechProblem? = null,
    val isGenerating: Boolean = false,
    val replyFailure: ReplyFailure? = null,
    val isPaused: Boolean = false,
    val stageFailure: StageFailure? = null,
) {
    val isActive: Boolean
        get() = phase in setOf(
            ConversationPhase.PreparingSpeech, ConversationPhase.Listening,
            ConversationPhase.Processing, ConversationPhase.Speaking,
        )
}

data class ListeningCallbacks(
    val onReady: () -> Unit,
    val onEnd: () -> Unit,
    val onResult: (String) -> Unit,
    val onError: (SpeechProblem) -> Unit,
    val onPreparing: () -> Unit = {},
    val onStageError: (StageFailure) -> Unit = { onError(SpeechProblem.Network) },
)

interface VoiceSession {
    fun listen(callbacks: ListeningCallbacks)
    fun retryListen(callbacks: ListeningCallbacks) = listen(callbacks)
    fun speak(text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit)
    fun speakWithFailure(
        text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit,
        onStageError: (StageFailure) -> Unit,
    ) = speak(text, onDone, onError)
    fun remainingSpeech(original: String): String = original
    fun cancel()
}

data class ReplyCallbacks(
    val onPartial: (String) -> Unit,
    val onComplete: (String) -> Unit,
    val onError: (ReplyFailure) -> Unit,
    val onStageError: (StageFailure) -> Unit = { onError(it.reason) },
)

interface ReplyGenerator {
    val isReady: Boolean
    fun generate(text: String, callbacks: ReplyCallbacks)
    fun cancel()
    fun reset()
}

class ConversationViewModel(
    private val scheduler: ListeningScheduler = MainListeningScheduler(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(ConversationState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var session: VoiceSession? = null
    private var generator: ReplyGenerator? = null
    private var backend: String? = null
    private var availableVoice: VoiceSession? = null
    private var availableReplies: ReplyGenerator? = null
    private var cancelRestart: (() -> Unit)? = null
    private var recoveryVoice: VoiceSession? = null
    private var retryInput: String? = null
    private var retrySpeech: String? = null
    private var speechIdentity: Pair<String, String>? = null
    private var retryAfterSpeechChange = false

    /** The activity supplies only a resumed, focused, permitted, ready backend. */
    fun updateAvailability(voice: VoiceSession, replies: ReplyGenerator, available: Boolean) {
        if (!available) {
            availableVoice = null
            availableReplies = null
            cancelConversation()
            return
        }
        availableVoice = voice
        availableReplies = replies
        if (retryAfterSpeechChange) retryListening() else listenWhenReady()
    }

    fun pauseListening() {
        retryAfterSpeechChange = false
        mutableState.value = state.value.copy(isPaused = true)
        cancelConversation()
    }

    fun resumeListening() {
        if (!state.value.isPaused) return
        mutableState.value = state.value.copy(isPaused = false)
        retryListening()
    }

    fun retryListening() {
        if (state.value.isActive || state.value.isPaused) return
        val failure = state.value.stageFailure
        if (failure != null) {
            if (failure.remainingSeconds(System.currentTimeMillis()) > 0) return
            val voice = availableVoice ?: return
            val replies = availableReplies ?: return
            recoveryVoice = null
            when (failure.stage) {
                PipelineStage.Stt -> startListening(voice, replies, retry = true)
                PipelineStage.Chat -> {
                    val turn = beginTurn(voice, replies) ?: return
                    generateReply(turn, retryInput ?: state.value.transcript, voice, replies)
                }
                PipelineStage.Tts -> {
                    val turn = beginTurn(voice, replies) ?: return
                    playReply(turn, retrySpeech ?: state.value.reply, voice)
                }
            }
        } else {
            mutableState.value = state.value.copy(
                phase = ConversationPhase.Idle, problem = null, replyFailure = null,
            )
            listenWhenReady()
        }
    }

    fun speechConfigurationChanged(stt: String, tts: String) {
        val previous = speechIdentity
        speechIdentity = stt to tts
        if (previous == null) return
        val failure = state.value.stageFailure ?: return
        if (failure.stage == PipelineStage.Chat ||
            (failure.stage == PipelineStage.Stt && previous.first == stt) ||
            (failure.stage == PipelineStage.Tts && previous.second == tts)
        ) return
        retryAfterSpeechChange = true
        mutableState.value = state.value.copy(stageFailure = failure.copy(retryAtMillis = null))
        retryListening()
    }

    private fun listenWhenReady() {
        val voice = availableVoice ?: return
        val replies = availableReplies ?: return
        if (state.value.isPaused || state.value.phase != ConversationPhase.Idle ||
            cancelRestart != null || !replies.isReady
        ) return
        startConversation(voice, replies)
    }

    private fun scheduleListening(delayMillis: Long) {
        if (availableVoice == null || state.value.isPaused) return
        cancelRestart?.invoke()
        val token = generation
        cancelRestart = scheduler.schedule(delayMillis) {
            if (token == generation) {
                cancelRestart = null
                listenWhenReady()
            }
        }
    }

    fun bindBackend(identity: String, replies: ReplyGenerator) {
        if (backend != null && backend != identity) newConversation(replies)
        backend = identity
    }

    fun startConversation(voice: VoiceSession, replies: ReplyGenerator) =
        startListening(voice, replies, retry = false)

    private fun startListening(voice: VoiceSession, replies: ReplyGenerator, retry: Boolean) {
        cancelRestart?.invoke()
        cancelRestart = null
        val turn = beginTurn(voice, replies) ?: return
        var acceptedResult = false
        fun acceptsInput() = turn == generation && !acceptedResult && state.value.isActive
        val callbacks = ListeningCallbacks(
                onPreparing = {
                    if (acceptsInput()) {
                        mutableState.value = state.value.copy(phase = ConversationPhase.PreparingSpeech)
                    }
                },
                onReady = {
                    if (acceptsInput()) {
                        mutableState.value = state.value.copy(phase = ConversationPhase.Listening)
                    }
                },
                onEnd = {
                    if (acceptsInput()) {
                        mutableState.value = state.value.copy(phase = ConversationPhase.Processing)
                    }
                },
                onResult = { text ->
                    if (acceptsInput()) {
                        acceptedResult = true
                        generateReply(turn, text, voice, replies)
                    }
                },
                onError = { problem -> if (acceptsInput()) fail(problem) },
                onStageError = { if (acceptsInput()) failStage(it) },
            )
        if (retry) voice.retryListen(callbacks) else voice.listen(callbacks)
    }

    private fun beginTurn(voice: VoiceSession, replies: ReplyGenerator): Long? {
        if (state.value.isActive) return null
        if (!replies.isReady) {
            fail(SpeechProblem.ModelUnavailable)
            return null
        }
        val turn = ++generation
        retryAfterSpeechChange = false
        session = voice
        generator = replies
        mutableState.value = state.value.copy(
            phase = ConversationPhase.Processing, problem = null, replyFailure = null, stageFailure = null,
        )
        return turn
    }

    private fun generateReply(
        turn: Long,
        text: String,
        voice: VoiceSession,
        replies: ReplyGenerator,
    ) {
        val input = text.trim()
        retryInput = input
        if (input.isEmpty()) return fail(SpeechProblem.NoSpeech)
        if (input.length > MAX_INPUT_LENGTH) return fail(SpeechProblem.InputTooLong)
        mutableState.value = state.value.copy(
            phase = ConversationPhase.Processing,
            transcript = input,
            reply = "",
            isGenerating = true,
        )
        fun acceptsReply() = turn == generation && state.value.isGenerating
        replies.generate(
            input,
            ReplyCallbacks(
                onPartial = { partial ->
                    if (acceptsReply()) mutableState.value = state.value.copy(reply = partial)
                },
                onComplete = { response ->
                    if (acceptsReply()) {
                        val reply = response.trim()
                        if (reply.isEmpty()) {
                            fail(SpeechProblem.GenerationFailed)
                        } else {
                            mutableState.value = state.value.copy(
                                phase = ConversationPhase.Speaking, reply = reply, isGenerating = false,
                            )
                            playReply(turn, reply, voice)
                        }
                    }
                },
                onError = { if (acceptsReply()) failReply(it) },
                onStageError = { if (acceptsReply()) failStage(it) },
            ),
        )
    }


    private fun playReply(turn: Long, text: String, voice: VoiceSession) {
        mutableState.value = state.value.copy(
            phase = ConversationPhase.Speaking, isGenerating = false, stageFailure = null,
        )
        fun acceptsPlayback() = turn == generation && state.value.phase == ConversationPhase.Speaking
        voice.speakWithFailure(
            text,
            onDone = {
                if (acceptsPlayback()) {
                    session = null
                    generator = null
                    retrySpeech = null
                    mutableState.value = state.value.copy(phase = ConversationPhase.Idle)
                    scheduleListening(500L)
                }
            },
            onError = { if (acceptsPlayback()) fail(it) },
            onStageError = { if (acceptsPlayback()) failStage(it) },
        )
    }

    private fun failStage(failure: StageFailure) {
        val failedVoice = session
        if (failure.stage == PipelineStage.Tts) {
            retrySpeech = failedVoice?.remainingSpeech(state.value.reply) ?: state.value.reply
        }
        val reply = if (state.value.isGenerating) "" else state.value.reply
        invalidateSession(cancelVoice = false)
        recoveryVoice = failedVoice
        mutableState.value = state.value.copy(
            phase = ConversationPhase.Error, isGenerating = false, reply = reply,
            problem = null, replyFailure = null, stageFailure = failure,
        )
    }

    fun newConversation(replies: ReplyGenerator) {
        invalidateSession()
        retryInput = null
        retrySpeech = null
        retryAfterSpeechChange = false
        replies.reset()
        mutableState.value = ConversationState(isPaused = state.value.isPaused)
        scheduleListening(0L)
    }

    private fun failReply(failure: ReplyFailure) {
        val reply = if (state.value.isGenerating) "" else state.value.reply
        invalidateSession()
        mutableState.value = state.value.copy(
            phase = ConversationPhase.Error, problem = null, replyFailure = failure,
            isGenerating = false, reply = reply,
        )
    }

    fun showProblem(problem: SpeechProblem) = fail(problem)

    fun cancelConversation() {
        recoveryVoice?.cancel()
        recoveryVoice = null
        if (state.value.stageFailure?.stage == PipelineStage.Stt) {
            mutableState.value = state.value.copy(
                stageFailure = state.value.stageFailure?.copy(recordingExpired = true),
            )
        }
        cancelRestart?.invoke()
        cancelRestart = null
        if (!state.value.isActive) {
            generation++
            return
        }
        val reply = if (state.value.isGenerating) "" else state.value.reply
        invalidateSession()
        mutableState.value = state.value.copy(
            phase = ConversationPhase.Idle, problem = null, replyFailure = null,
            isGenerating = false, reply = reply,
        )
    }

    fun refreshMicrophonePermission(granted: Boolean) {
        if (granted && state.value.problem in setOf(
                SpeechProblem.PermissionDenied, SpeechProblem.PermissionSettings,
            )
        ) {
            mutableState.value = state.value.copy(phase = ConversationPhase.Idle, problem = null)
        }
    }

    private fun fail(problem: SpeechProblem) {
        if (problem == SpeechProblem.NoSpeech && availableVoice != null && !state.value.isPaused) {
            invalidateSession()
            mutableState.value = state.value.copy(
                phase = ConversationPhase.Idle, problem = null, replyFailure = null,
                isGenerating = false,
            )
            scheduleListening(1_000L)
            return
        }
        val reply = if (state.value.isGenerating) "" else state.value.reply
        invalidateSession()
        mutableState.value = state.value.copy(
            phase = ConversationPhase.Error, problem = problem, replyFailure = null,
            isGenerating = false, reply = reply,
        )
    }

    private fun invalidateSession(cancelVoice: Boolean = true) {
        if (cancelVoice) {
            recoveryVoice?.cancel()
            recoveryVoice = null
        }
        cancelRestart?.invoke()
        cancelRestart = null
        generation++
        val previousSession = session
        val previousGenerator = generator
        session = null
        generator = null
        if (cancelVoice) previousSession?.cancel()
        previousGenerator?.cancel()
    }

    override fun onCleared() {
        availableVoice = null
        availableReplies = null
        invalidateSession()
    }

    companion object {
        const val MAX_INPUT_LENGTH = 240
    }
}

/** Schedules one restart without blocking the speech callback stack. */
fun interface ListeningScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit
}

private class MainListeningScheduler : ListeningScheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
        val task = Runnable(action)
        handler.postDelayed(task, delayMillis)
        return { handler.removeCallbacks(task) }
    }
}

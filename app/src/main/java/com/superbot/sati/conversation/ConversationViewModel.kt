package com.superbot.sati.conversation

import androidx.lifecycle.ViewModel
import com.superbot.sati.ai.ReplyFailure
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
)

interface VoiceSession {
    fun listen(callbacks: ListeningCallbacks)
    fun speak(text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit)
    fun cancel()
}

data class ReplyCallbacks(
    val onPartial: (String) -> Unit,
    val onComplete: (String) -> Unit,
    val onError: (ReplyFailure) -> Unit,
)

interface ReplyGenerator {
    val isReady: Boolean
    fun generate(text: String, callbacks: ReplyCallbacks)
    fun cancel()
    fun reset()
}

class ConversationViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ConversationState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var session: VoiceSession? = null
    private var generator: ReplyGenerator? = null
    private var backend: String? = null

    fun bindBackend(identity: String, replies: ReplyGenerator) {
        if (backend != null && backend != identity) newConversation(replies)
        backend = identity
    }

    fun startConversation(voice: VoiceSession, replies: ReplyGenerator) {
        val turn = beginTurn(voice, replies) ?: return
        var acceptedResult = false
        fun acceptsInput() = turn == generation && !acceptedResult && state.value.isActive
        voice.listen(
            ListeningCallbacks(
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
            ),
        )
    }

    private fun beginTurn(voice: VoiceSession, replies: ReplyGenerator): Long? {
        if (state.value.isActive) return null
        if (!replies.isReady) {
            fail(SpeechProblem.ModelUnavailable)
            return null
        }
        val turn = ++generation
        session = voice
        generator = replies
        mutableState.value = state.value.copy(
            phase = ConversationPhase.Processing, problem = null, replyFailure = null,
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
                            voice.speak(
                                reply,
                                onDone = {
                                    if (turn == generation &&
                                        state.value.phase == ConversationPhase.Speaking
                                    ) {
                                        session = null
                                        generator = null
                                        mutableState.value = state.value.copy(phase = ConversationPhase.Idle)
                                    }
                                },
                                onError = { problem ->
                                    if (turn == generation &&
                                        state.value.phase == ConversationPhase.Speaking
                                    ) fail(problem)
                                },
                            )
                        }
                    }
                },
                onError = { if (acceptsReply()) failReply(it) },
            ),
        )
    }

    fun newConversation(replies: ReplyGenerator) {
        invalidateSession()
        replies.reset()
        mutableState.value = ConversationState()
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
        if (!state.value.isActive) return
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
        val reply = if (state.value.isGenerating) "" else state.value.reply
        invalidateSession()
        mutableState.value = state.value.copy(
            phase = ConversationPhase.Error, problem = problem, replyFailure = null,
            isGenerating = false, reply = reply,
        )
    }

    private fun invalidateSession() {
        generation++
        val previousSession = session
        val previousGenerator = generator
        session = null
        generator = null
        previousSession?.cancel()
        previousGenerator?.cancel()
    }

    override fun onCleared() = invalidateSession()

    companion object {
        const val MAX_INPUT_LENGTH = 240
    }
}

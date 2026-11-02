package com.superbot.sati.conversation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConversationPhase { Idle, Listening, Processing, Speaking, Error }

enum class SpeechProblem {
    PermissionDenied, PermissionSettings, RecognitionUnavailable, NoSpeech,
    Network, RecognitionFailed, SpeechUnavailable, EnglishUnavailable, PlaybackFailed
}

data class ConversationState(
    val phase: ConversationPhase = ConversationPhase.Idle,
    val transcript: String = "",
    val reply: String = "",
    val problem: SpeechProblem? = null,
) {
    val isActive: Boolean
        get() = phase == ConversationPhase.Listening ||
            phase == ConversationPhase.Processing || phase == ConversationPhase.Speaking
}

data class ListeningCallbacks(
    val onReady: () -> Unit,
    val onEnd: () -> Unit,
    val onResult: (String) -> Unit,
    val onError: (SpeechProblem) -> Unit,
)

interface VoiceSession {
    fun listen(callbacks: ListeningCallbacks)
    fun speak(text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit)
    fun cancel()
}

class ConversationViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ConversationState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var session: VoiceSession? = null

    fun startConversation(voiceSession: VoiceSession) {
        if (state.value.isActive) return
        val turn = ++generation
        session = voiceSession
        mutableState.value = state.value.copy(
            phase = ConversationPhase.Processing,
            problem = null,
        )
        var acceptedResult = false
        fun acceptsInput() = turn == generation && !acceptedResult && state.value.isActive
        voiceSession.listen(
            ListeningCallbacks(
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
                        if (text.isBlank()) {
                            fail(SpeechProblem.NoSpeech)
                        } else {
                            mutableState.value = state.value.copy(
                                phase = ConversationPhase.Speaking,
                                transcript = text.trim(),
                                reply = SCRIPTED_REPLY,
                            )
                            voiceSession.speak(
                                SCRIPTED_REPLY,
                                onDone = {
                                    if (turn == generation &&
                                        state.value.phase == ConversationPhase.Speaking
                                    ) {
                                        session = null
                                        mutableState.value = state.value.copy(
                                            phase = ConversationPhase.Idle,
                                        )
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
                onError = { problem -> if (acceptsInput()) fail(problem) },
            ),
        )
    }

    fun showProblem(problem: SpeechProblem) = fail(problem)

    fun cancelConversation() {
        if (!state.value.isActive) return
        invalidateSession()
        mutableState.value = state.value.copy(phase = ConversationPhase.Idle, problem = null)
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
        invalidateSession()
        mutableState.value = state.value.copy(phase = ConversationPhase.Error, problem = problem)
    }

    private fun invalidateSession() {
        generation++
        val previous = session
        session = null
        previous?.cancel()
    }

    override fun onCleared() {
        invalidateSession()
    }

    companion object {
        const val SCRIPTED_REPLY = "Hello! What's your name?"
    }
}

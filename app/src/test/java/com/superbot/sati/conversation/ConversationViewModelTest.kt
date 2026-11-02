package com.superbot.sati.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationViewModelTest {
    private val model = ConversationViewModel()
    private val voice = FakeVoiceSession()

    @Test
    fun successfulTurnDisplaysTranscriptAndSpeaksExactlyOnce() {
        model.startConversation(voice)
        voice.input.onReady()
        assertEquals(ConversationPhase.Listening, model.state.value.phase)
        voice.input.onEnd()
        assertEquals(ConversationPhase.Processing, model.state.value.phase)
        voice.input.onResult("  Hello Sati  ")

        assertEquals("Hello Sati", model.state.value.transcript)
        assertEquals(ConversationViewModel.SCRIPTED_REPLY, model.state.value.reply)
        assertEquals(listOf(ConversationViewModel.SCRIPTED_REPLY), voice.spoken)
        assertEquals(ConversationPhase.Speaking, model.state.value.phase)

        // A service may deliver duplicate or late input callbacks.
        voice.input.onResult("duplicate")
        voice.input.onError(SpeechProblem.Network)
        assertEquals(1, voice.spoken.size)
        assertEquals(ConversationPhase.Speaking, model.state.value.phase)
        voice.done()
        assertEquals(ConversationPhase.Idle, model.state.value.phase)

        model.startConversation(voice)
        voice.input.onResult("Again")
        voice.done()
        assertEquals(2, voice.spoken.size)
        assertEquals("Again", model.state.value.transcript)
    }

    @Test
    fun emptyResultOffersRetryWithoutSpeaking() {
        model.startConversation(voice)
        voice.input.onResult("  ")
        assertEquals(SpeechProblem.NoSpeech, model.state.value.problem)
        assertEquals(ConversationPhase.Error, model.state.value.phase)
        assertTrue(voice.spoken.isEmpty())
        model.startConversation(voice)
        assertEquals(ConversationPhase.Processing, model.state.value.phase)
        assertEquals(null, model.state.value.problem)
    }

    @Test
    fun recognitionFailureCancelsEngineAndAllowsNewTurn() {
        model.startConversation(voice)
        voice.input.onError(SpeechProblem.Network)
        assertEquals(SpeechProblem.Network, model.state.value.problem)
        assertEquals(1, voice.cancellations)
        assertTrue(voice.spoken.isEmpty())

        model.startConversation(voice)
        voice.input.onResult("Hello")
        assertEquals(ConversationPhase.Speaking, model.state.value.phase)
    }

    @Test
    fun playbackFailureKeepsVisibleConversationAndAllowsRetry() {
        model.startConversation(voice)
        voice.input.onResult("Hi")
        voice.failed(SpeechProblem.PlaybackFailed)
        assertEquals(ConversationPhase.Error, model.state.value.phase)
        assertEquals("Hi", model.state.value.transcript)
        assertEquals(ConversationViewModel.SCRIPTED_REPLY, model.state.value.reply)
        assertEquals(1, voice.cancellations)
        model.startConversation(voice)
        assertEquals(null, model.state.value.problem)
    }

    @Test
    fun repeatedTalkTapsCannotStartOverlappingTurns() {
        model.startConversation(voice)
        model.startConversation(voice)
        voice.input.onReady()
        model.startConversation(voice)
        voice.input.onEnd()
        model.startConversation(voice)
        voice.input.onResult("Hello")
        model.startConversation(voice)
        assertEquals(1, voice.listenCalls)
        assertEquals(1, voice.spoken.size)
    }

    @Test
    fun canceledRecognitionCannotUpdateANewerTurn() {
        model.startConversation(voice)
        val oldInput = voice.input
        model.cancelConversation()
        assertEquals(ConversationPhase.Idle, model.state.value.phase)
        model.startConversation(voice)
        oldInput.onReady()
        oldInput.onResult("late transcript")
        oldInput.onError(SpeechProblem.Network)
        assertEquals(ConversationPhase.Processing, model.state.value.phase)
        assertEquals("", model.state.value.transcript)
        assertTrue(voice.spoken.isEmpty())
        voice.input.onResult("current transcript")
        assertEquals("current transcript", model.state.value.transcript)
    }

    @Test
    fun cancelingPlaybackPreservesTextAndIgnoresLateCompletionAndFailure() {
        model.startConversation(voice)
        voice.input.onResult("Hi")
        val oldDone = voice.done
        val oldFailure = voice.failed
        model.cancelConversation()
        assertFalse(model.state.value.isActive)
        assertEquals("Hi", model.state.value.transcript)

        model.startConversation(voice)
        voice.input.onResult("New turn")
        oldDone()
        oldFailure(SpeechProblem.PlaybackFailed)
        assertEquals(ConversationPhase.Speaking, model.state.value.phase)
        assertEquals("New turn", model.state.value.transcript)
    }

    @Test
    fun permissionRecoveryClearsOnlyPermissionErrorsAndDoesNotRecord() {
        model.showProblem(SpeechProblem.PermissionSettings)
        model.refreshMicrophonePermission(false)
        assertEquals(SpeechProblem.PermissionSettings, model.state.value.problem)
        model.refreshMicrophonePermission(true)
        assertEquals(ConversationPhase.Idle, model.state.value.phase)
        assertEquals(0, voice.listenCalls)

        model.showProblem(SpeechProblem.Network)
        model.refreshMicrophonePermission(true)
        assertEquals(SpeechProblem.Network, model.state.value.problem)
    }

    @Test
    fun canceledTurnIgnoresCallbacksEvenWhenEngineCancelsSynchronously() {
        model.startConversation(voice)
        voice.onCancel = { voice.input.onError(SpeechProblem.RecognitionFailed) }
        model.cancelConversation()
        assertEquals(ConversationPhase.Idle, model.state.value.phase)
        assertEquals(null, model.state.value.problem)
    }

    private class FakeVoiceSession : VoiceSession {
        lateinit var input: ListeningCallbacks
        var done: () -> Unit = {}
        var failed: (SpeechProblem) -> Unit = {}
        var onCancel: () -> Unit = {}
        var listenCalls = 0
        var cancellations = 0
        val spoken = mutableListOf<String>()

        override fun listen(callbacks: ListeningCallbacks) {
            listenCalls++
            input = callbacks
        }

        override fun speak(text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit) {
            spoken += text
            done = onDone
            failed = onError
        }

        override fun cancel() {
            cancellations++
            onCancel()
        }
    }
}

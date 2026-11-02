package com.superbot.sati.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationViewModelTest {
    private val model = ConversationViewModel()
    private val voice = FakeVoiceSession()
    private val replies = FakeReplies()

    private fun start() = model.startConversation(voice, replies)

    private fun recognize(text: String) {
        start()
        voice.input.onResult(text)
    }

    @Test
    fun recognizedInputGetsGeneratedReplyAndSpeaksExactlyOnce() {
        start()
        voice.input.onReady()
        assertEquals(ConversationPhase.Listening, model.state.value.phase)
        voice.input.onEnd()
        voice.input.onResult("  My name is Mina  ")
        assertEquals(listOf("My name is Mina"), replies.inputs)
        assertEquals(ConversationPhase.Processing, model.state.value.phase)
        assertTrue(voice.spoken.isEmpty())
        replies.callbacks.onPartial("Nice to")
        assertEquals("Nice to", model.state.value.reply)
        assertTrue(voice.spoken.isEmpty())
        replies.callbacks.onComplete("Nice to meet you, Mina!")
        assertEquals(listOf("Nice to meet you, Mina!"), voice.spoken)
        assertEquals("My name is Mina", model.state.value.transcript)

        voice.input.onResult("duplicate")
        voice.input.onError(SpeechProblem.Network)
        replies.callbacks.onComplete("duplicate")
        assertEquals(1, voice.spoken.size)
        voice.done()
        assertEquals(ConversationPhase.Idle, model.state.value.phase)

        start()
        voice.input.onResult("What is my name?")
        replies.callbacks.onComplete("Your name is Mina.")
        voice.done()
        assertEquals(listOf("Nice to meet you, Mina!", "Your name is Mina."), voice.spoken)
    }

    @Test
    fun missingModelCannotStartRecordingOrGenerateAFakeReply() {
        replies.isReady = false
        start()
        assertEquals(SpeechProblem.ModelUnavailable, model.state.value.problem)
        assertEquals(0, voice.listenCalls)
        assertTrue(replies.inputs.isEmpty())
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun speechSetupStaysCancelableAndDoesNotGenerateUntilRecognition() {
        start()
        val oldInput = voice.input
        oldInput.onPreparing()
        assertEquals(ConversationPhase.PreparingSpeech, model.state.value.phase)
        assertTrue(model.state.value.isActive)
        assertTrue(replies.inputs.isEmpty())
        model.cancelConversation()
        oldInput.onReady()
        oldInput.onPreparing()
        oldInput.onResult("late")
        assertEquals(ConversationPhase.Idle, model.state.value.phase)
        assertTrue(replies.inputs.isEmpty())
        recognize("Hello")
        assertEquals(listOf("Hello"), replies.inputs)
    }

    @Test
    fun emptyResultOffersRetryWithoutGenerating() {
        start()
        voice.input.onResult("  ")
        assertEquals(SpeechProblem.NoSpeech, model.state.value.problem)
        assertTrue(replies.inputs.isEmpty())
        start()
        assertEquals(ConversationPhase.Processing, model.state.value.phase)
    }

    @Test
    fun longInputDoesNotReachTheModel() {
        recognize("x".repeat(ConversationViewModel.MAX_INPUT_LENGTH + 1))
        assertEquals(SpeechProblem.InputTooLong, model.state.value.problem)
        assertTrue(replies.inputs.isEmpty())
    }

    @Test
    fun recognitionFailureCancelsEngineAndAllowsAnotherTurn() {
        start()
        voice.input.onError(SpeechProblem.RecognitionUnavailable)
        assertEquals(SpeechProblem.RecognitionUnavailable, model.state.value.problem)
        assertEquals(1, voice.cancellations)
        assertTrue(replies.inputs.isEmpty())
        start()
        voice.input.onResult("Hello")
        assertEquals(listOf("Hello"), replies.inputs)
    }

    @Test
    fun generationFailureClearsPartialReplyAndNeverSpeaksIt() {
        recognize("Hello")
        replies.callbacks.onPartial("unfinished")
        replies.callbacks.onError()
        assertEquals(SpeechProblem.GenerationFailed, model.state.value.problem)
        assertEquals("", model.state.value.reply)
        assertTrue(voice.spoken.isEmpty())
        recognize("Try again")
        assertEquals(null, model.state.value.problem)
    }

    @Test
    fun emptyGeneratedReplyOffersRetry() {
        recognize("Hi")
        replies.callbacks.onComplete("  ")
        assertEquals(SpeechProblem.GenerationFailed, model.state.value.problem)
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun missingOfflineVoiceKeepsGeneratedReplyReadable() {
        recognize("Hi")
        replies.callbacks.onComplete("Hi! How are you?")
        voice.failed(SpeechProblem.EnglishUnavailable)
        assertEquals(SpeechProblem.EnglishUnavailable, model.state.value.problem)
        assertEquals("Hi! How are you?", model.state.value.reply)
        assertFalse(model.state.value.isActive)
    }

    @Test
    fun repeatedTapsCannotStartOverlappingRecognitionOrGeneration() {
        start()
        start()
        voice.input.onReady()
        start()
        voice.input.onResult("Hello")
        start()
        voice.input.onResult("another message")
        replies.callbacks.onComplete("Hi!")
        start()
        assertEquals(1, voice.listenCalls)
        assertEquals(listOf("Hello"), replies.inputs)
        assertEquals(1, voice.spoken.size)
    }

    @Test
    fun canceledRecognitionCannotUpdateANewerTurn() {
        start()
        val oldInput = voice.input
        model.cancelConversation()
        start()
        oldInput.onReady()
        oldInput.onResult("late")
        oldInput.onError(SpeechProblem.Network)
        assertEquals(ConversationPhase.Processing, model.state.value.phase)
        assertEquals("", model.state.value.transcript)
        assertTrue(replies.inputs.isEmpty())
    }

    @Test
    fun canceledGenerationCannotSpeakOrOverwriteANewerTurn() {
        recognize("First")
        val old = replies.callbacks
        old.onPartial("incomplete")
        model.cancelConversation()
        assertEquals("", model.state.value.reply)
        recognize("Second")
        old.onPartial("late")
        old.onComplete("late reply")
        old.onError()
        assertEquals("Second", model.state.value.transcript)
        assertEquals("", model.state.value.reply)
        assertTrue(voice.spoken.isEmpty())
        replies.callbacks.onComplete("Current reply.")
        assertEquals(listOf("Current reply."), voice.spoken)
    }

    @Test
    fun canceledPlaybackPreservesTextAndIgnoresOldCallbacks() {
        recognize("First")
        replies.callbacks.onComplete("First reply.")
        val oldDone = voice.done
        val oldFailure = voice.failed
        model.cancelConversation()
        assertEquals("First reply.", model.state.value.reply)
        recognize("Second")
        replies.callbacks.onComplete("Second reply.")
        oldDone()
        oldFailure(SpeechProblem.PlaybackFailed)
        assertEquals(ConversationPhase.Speaking, model.state.value.phase)
        assertEquals("Second reply.", model.state.value.reply)
    }

    @Test
    fun startingOverCancelsActiveWorkAndClearsConversationMemory() {
        recognize("My name is Mina")
        val old = replies.callbacks
        model.newConversation(replies)
        assertEquals(1, replies.resets)
        assertEquals(ConversationState(), model.state.value)
        old.onComplete("late")
        assertEquals(ConversationState(), model.state.value)
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun permissionRecoveryDoesNotAutomaticallyRecordOrClearUnrelatedErrors() {
        model.showProblem(SpeechProblem.PermissionSettings)
        model.refreshMicrophonePermission(false)
        assertEquals(SpeechProblem.PermissionSettings, model.state.value.problem)
        model.refreshMicrophonePermission(true)
        assertEquals(ConversationPhase.Idle, model.state.value.phase)
        assertEquals(0, voice.listenCalls)
        model.showProblem(SpeechProblem.GenerationFailed)
        model.refreshMicrophonePermission(true)
        assertEquals(SpeechProblem.GenerationFailed, model.state.value.problem)
    }

    @Test
    fun synchronousCancellationCallbackCannotPutTheScreenIntoError() {
        start()
        voice.onCancel = { voice.input.onError(SpeechProblem.RecognitionFailed) }
        model.cancelConversation()
        assertEquals(ConversationPhase.Idle, model.state.value.phase)
        assertEquals(null, model.state.value.problem)
    }

    private class FakeReplies : ReplyGenerator {
        override var isReady = true
        val inputs = mutableListOf<String>()
        lateinit var callbacks: ReplyCallbacks
        var resets = 0
        override fun generate(text: String, callbacks: ReplyCallbacks) {
            inputs += text
            this.callbacks = callbacks
        }
        override fun cancel() = Unit
        override fun reset() { resets++ }
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

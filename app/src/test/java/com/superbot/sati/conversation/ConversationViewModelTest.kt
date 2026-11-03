package com.superbot.sati.conversation

import com.superbot.sati.ai.ReplyFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationViewModelTest {
    private val scheduler = FakeScheduler()
    private val model = ConversationViewModel(scheduler)
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
        replies.callbacks.onError(ReplyFailure.Generation)
        assertEquals(ReplyFailure.Generation, model.state.value.replyFailure)
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
        old.onError(ReplyFailure.Generation)
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

    @Test
    fun modelChangeClearsConversationButRotationAndCanceledSetupDoNot() {
        model.bindBackend("qwen:0", replies)
        recognize("Hello")
        replies.callbacks.onComplete("Hi!")
        voice.done()
        model.bindBackend("qwen:0", replies)
        assertEquals("Hi!", model.state.value.reply)
        assertEquals(0, replies.resets)
        model.bindBackend("deepseek:1", replies)
        assertEquals(ConversationState(), model.state.value)
        assertEquals(1, replies.resets)
        recognize("Hello cloud")
        replies.callbacks.onPartial("incomplete")
        val old = replies.callbacks
        model.bindBackend("deepseek:2", replies)
        old.onComplete("late reply from the old key")
        assertEquals(ConversationState(), model.state.value)
        assertEquals(listOf("Hi!"), voice.spoken)
    }

    @Test
    fun cloudFailureClearsPartialReplyAndANewTurnClearsTheError() {
        recognize("Hello")
        replies.callbacks.onPartial("unfinished")
        replies.callbacks.onError(ReplyFailure.Authentication)
        assertEquals(ReplyFailure.Authentication, model.state.value.replyFailure)
        assertEquals(null, model.state.value.problem)
        assertEquals("", model.state.value.reply)
        assertTrue(voice.spoken.isEmpty())
        recognize("Try again")
        assertEquals(null, model.state.value.replyFailure)
        replies.callbacks.onComplete("Hi!")
        assertEquals(listOf("Hi!"), voice.spoken)
    }


    @Test
    fun availabilityStartsListeningOnceAndWaitsForPlaybackBeforeTheNextTurn() {
        model.updateAvailability(voice, replies, false)
        assertEquals(0, voice.listenCalls)
        model.updateAvailability(voice, replies, true)
        model.updateAvailability(voice, replies, true)
        assertEquals(1, voice.listenCalls)
        voice.input.onReady()
        voice.input.onResult("Hello")
        replies.callbacks.onPartial("Hi")
        model.updateAvailability(voice, replies, true)
        assertEquals(1, voice.listenCalls)
        replies.callbacks.onComplete("Hi!")
        assertEquals(ConversationPhase.Speaking, model.state.value.phase)
        assertEquals(0, scheduler.pending)
        voice.done()
        assertEquals(1, scheduler.pending)
        scheduler.advanceBy(499)
        assertEquals(1, voice.listenCalls)
        scheduler.advanceBy(1)
        assertEquals(2, voice.listenCalls)
        assertEquals("Hi!", model.state.value.reply)
        assertEquals(listOf("Hello"), replies.inputs)
    }

    @Test
    fun silenceAndEmptyResultsRetryWithoutErrorsOrAiRequests() {
        model.updateAvailability(voice, replies, true)
        val old = voice.input
        old.onError(SpeechProblem.NoSpeech)
        assertEquals(null, model.state.value.problem)
        assertEquals(ConversationPhase.Idle, model.state.value.phase)
        old.onError(SpeechProblem.NoSpeech)
        assertEquals(1, scheduler.pending)
        scheduler.advanceBy(999)
        assertEquals(1, voice.listenCalls)
        scheduler.advanceBy(1)
        assertEquals(2, voice.listenCalls)
        voice.input.onResult("  ")
        scheduler.advanceBy(1_000)
        assertEquals(3, voice.listenCalls)
        assertTrue(replies.inputs.isEmpty())
        assertTrue(voice.spoken.isEmpty())
    }

    @Test
    fun pauseCancelsAScheduledRestartAndResumeStartsExactlyOnce() {
        model.updateAvailability(voice, replies, true)
        voice.input.onError(SpeechProblem.NoSpeech)
        val lateRestart = scheduler.tasks.last().action
        model.pauseListening()
        assertTrue(model.state.value.isPaused)
        assertEquals(0, scheduler.pending)
        scheduler.advanceBy(10_000)
        lateRestart()
        model.updateAvailability(voice, replies, false)
        model.updateAvailability(voice, replies, true)
        assertEquals(1, voice.listenCalls)
        model.resumeListening()
        assertFalse(model.state.value.isPaused)
        assertEquals(2, voice.listenCalls)
    }

    @Test
    fun pauseDuringGenerationDiscardsPartialReplyAndRejectsLateCompletion() {
        model.updateAvailability(voice, replies, true)
        voice.input.onResult("Hello")
        val old = replies.callbacks
        old.onPartial("unfinished")
        model.pauseListening()
        old.onComplete("late")
        assertEquals("", model.state.value.reply)
        assertTrue(voice.spoken.isEmpty())
        assertEquals(0, scheduler.pending)
        model.resumeListening()
        assertEquals(2, voice.listenCalls)
    }

    @Test
    fun pauseDuringPlaybackStopsItAndDoesNotRestartFromALateCallback() {
        model.updateAvailability(voice, replies, true)
        voice.input.onResult("Hello")
        replies.callbacks.onComplete("Hi!")
        val done = voice.done
        model.pauseListening()
        done()
        scheduler.advanceBy(1_000)
        assertEquals(1, voice.listenCalls)
        assertEquals(1, voice.cancellations)
        assertEquals("Hi!", model.state.value.reply)
    }

    @Test
    fun backgroundOrDialogCancelsListeningAndReturnUsesANewVoiceSession() {
        model.updateAvailability(voice, replies, true)
        val old = voice.input
        model.updateAvailability(voice, replies, false)
        old.onResult("late")
        assertTrue(replies.inputs.isEmpty())
        val recreatedVoice = FakeVoiceSession()
        model.updateAvailability(recreatedVoice, replies, true)
        assertEquals(1, recreatedVoice.listenCalls)
        old.onReady()
        old.onError(SpeechProblem.RecognitionFailed)
        assertEquals(null, model.state.value.problem)
        model.updateAvailability(recreatedVoice, replies, false)
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun availabilityLossDuringPlaybackDelayInvalidatesEvenAnAlreadyQueuedRestart() {
        model.updateAvailability(voice, replies, true)
        voice.input.onResult("Hello")
        replies.callbacks.onComplete("Hi!")
        voice.done()
        val late = scheduler.tasks.last().action
        model.updateAvailability(voice, replies, false)
        late()
        scheduler.advanceBy(1_000)
        assertEquals(1, voice.listenCalls)
        assertEquals(0, scheduler.pending)
        model.updateAvailability(voice, replies, true)
        assertEquals(2, voice.listenCalls)
        assertEquals("Hi!", model.state.value.reply)
    }

    @Test
    fun setupReadinessGatesAutomaticListeningAndLosingItCancelsRecording() {
        replies.isReady = false
        model.updateAvailability(voice, replies, true)
        assertEquals(0, voice.listenCalls)
        replies.isReady = true
        model.updateAvailability(voice, replies, true)
        assertEquals(1, voice.listenCalls)
        replies.isReady = false
        model.updateAvailability(voice, replies, false)
        voice.input.onResult("late")
        assertTrue(replies.inputs.isEmpty())
        assertEquals(ConversationPhase.Idle, model.state.value.phase)
    }

    @Test
    fun nonSilenceFailuresRequireExplicitRetryAndNeverResendThePreviousInput() {
        model.updateAvailability(voice, replies, true)
        voice.input.onResult("Hello")
        replies.callbacks.onError(ReplyFailure.RateLimit)
        model.updateAvailability(voice, replies, false)
        model.updateAvailability(voice, replies, true)
        scheduler.advanceBy(60_000)
        assertEquals(listOf("Hello"), replies.inputs)
        assertEquals(1, voice.listenCalls)
        assertEquals(ReplyFailure.RateLimit, model.state.value.replyFailure)
        model.retryListening()
        assertEquals(2, voice.listenCalls)
        assertEquals(null, model.state.value.replyFailure)
        assertEquals(listOf("Hello"), replies.inputs)
        voice.input.onError(SpeechProblem.RecognitionFailed)
        scheduler.advanceBy(60_000)
        assertEquals(2, voice.listenCalls)
        assertEquals(SpeechProblem.RecognitionFailed, model.state.value.problem)
    }

    @Test
    fun startOverClearsMemoryAndRestartsUnlessManuallyPaused() {
        model.updateAvailability(voice, replies, true)
        voice.input.onResult("Hello")
        model.newConversation(replies)
        scheduler.advanceBy(0)
        assertEquals(1, replies.resets)
        assertEquals("", model.state.value.transcript)
        assertEquals(2, voice.listenCalls)
        model.pauseListening()
        model.newConversation(replies)
        scheduler.advanceBy(0)
        assertTrue(model.state.value.isPaused)
        assertEquals(2, voice.listenCalls)
    }

    @Test
    fun backendChangeCancelsOldInputAndAutomaticallyStartsWithFreshHistory() {
        model.bindBackend("qwen:0", replies)
        model.updateAvailability(voice, replies, true)
        val old = voice.input
        model.bindBackend("deepseek:1", replies)
        scheduler.advanceBy(0)
        old.onResult("late")
        assertEquals(1, replies.resets)
        assertEquals(2, voice.listenCalls)
        assertTrue(replies.inputs.isEmpty())
        assertEquals("", model.state.value.transcript)
        model.pauseListening()
        model.bindBackend("deepseek:2", replies)
        scheduler.advanceBy(0)
        assertTrue(model.state.value.isPaused)
        assertEquals(2, voice.listenCalls)
    }


    private fun stageFailure(stage: com.superbot.sati.ai.PipelineStage, deadline: Long? = null) =
        com.superbot.sati.ai.StageFailure(stage, com.superbot.sati.ai.CloudProvider.Groq,
            "test-model", ReplyFailure.RateLimit, deadline)

    @Test
    fun chatStageRetryReusesTheRecognizedInputAndRejectsTheOldStream() {
        model.updateAvailability(voice, replies, true)
        voice.input.onResult("Hello Mina")
        val old = replies.callbacks
        old.onPartial("unfinished")
        old.onStageError(stageFailure(com.superbot.sati.ai.PipelineStage.Chat))
        assertEquals("", model.state.value.reply)
        assertEquals(0, scheduler.pending)
        model.retryListening()
        old.onComplete("late")
        assertTrue(voice.spoken.isEmpty())
        assertEquals(1, voice.listenCalls)
        assertEquals(listOf("Hello Mina", "Hello Mina"), replies.inputs)
        replies.callbacks.onComplete("Hello!")
        assertEquals(listOf("Hello!"), voice.spoken)
    }

    @Test
    fun ttsStageRetrySpeaksOnlyTheRemainingTextWithoutGeneratingAnotherReply() {
        model.updateAvailability(voice, replies, true)
        voice.input.onResult("Hello")
        replies.callbacks.onComplete("First sentence. Second sentence.")
        voice.remaining = "Second sentence."
        voice.stageFailed(stageFailure(com.superbot.sati.ai.PipelineStage.Tts))
        assertEquals("First sentence. Second sentence.", model.state.value.reply)
        model.retryListening()
        assertEquals(listOf("Hello"), replies.inputs)
        assertEquals(listOf("First sentence. Second sentence.", "Second sentence."), voice.spoken)
        voice.done()
        scheduler.advanceBy(500)
        assertEquals(2, voice.listenCalls)
    }

    @Test
    fun sttStageRetryUsesTheVoiceRetryPathAndNeverCallsTheModelBeforeRecognition() {
        model.updateAvailability(voice, replies, true)
        val old = voice.input
        old.onStageError(stageFailure(com.superbot.sati.ai.PipelineStage.Stt))
        model.retryListening()
        assertEquals(1, voice.listenCalls)
        assertEquals(1, voice.recognitionRetries)
        assertTrue(replies.inputs.isEmpty())
        old.onResult("late")
        assertTrue(replies.inputs.isEmpty())
        voice.input.onResult("Current message")
        assertEquals(listOf("Current message"), replies.inputs)
    }

    @Test
    fun cooldownSurvivesRebindingAndUnchangedSpeechSettingsWithoutAnyAutomaticRequest() {
        model.speechConfigurationChanged("stt", "tts")
        model.updateAvailability(voice, replies, true)
        voice.input.onResult("Hello")
        val failure = stageFailure(com.superbot.sati.ai.PipelineStage.Chat,
            System.currentTimeMillis() + 60_000)
        replies.callbacks.onStageError(failure)
        model.retryListening()
        model.updateAvailability(voice, replies, true)
        model.speechConfigurationChanged("stt", "tts")
        scheduler.advanceBy(60_000)
        assertEquals(failure, model.state.value.stageFailure)
        assertEquals(listOf("Hello"), replies.inputs)
        assertEquals(1, voice.listenCalls)
    }

    @Test
    fun changingTheOtherSpeechStageDoesNotBypassARecognitionCooldown() {
        model.speechConfigurationChanged("groq", "android")
        model.updateAvailability(voice, replies, true)
        val failure = stageFailure(com.superbot.sati.ai.PipelineStage.Stt,
            System.currentTimeMillis() + 60_000)
        voice.input.onStageError(failure)
        model.speechConfigurationChanged("groq", "orpheus")
        assertEquals(failure, model.state.value.stageFailure)
        assertEquals(0, voice.recognitionRetries)
        model.speechConfigurationChanged("android", "orpheus")
        assertEquals(1, voice.recognitionRetries)
        assertTrue(replies.inputs.isEmpty())
    }

    @Test
    fun backgroundingACapturedAudioFailureClearsTheRecordingAndKeepsItsCooldown() {
        model.updateAvailability(voice, replies, true)
        voice.input.onStageError(stageFailure(com.superbot.sati.ai.PipelineStage.Stt,
            System.currentTimeMillis() + 60_000))
        assertEquals(0, voice.cancellations)
        model.updateAvailability(voice, replies, false)
        assertEquals(1, voice.cancellations)
        assertTrue(model.state.value.stageFailure!!.recordingExpired)
        model.updateAvailability(voice, replies, true)
        model.retryListening()
        assertEquals(0, voice.recognitionRetries)
        assertEquals(1, voice.listenCalls)
    }

    @Test
    fun changingTtsPreservesTheReplyAndOnlyRetriesPlayback() {
        model.speechConfigurationChanged("android", "groq")
        model.updateAvailability(voice, replies, true)
        voice.input.onResult("Hello")
        replies.callbacks.onComplete("Hello friend.")
        voice.stageFailed(stageFailure(com.superbot.sati.ai.PipelineStage.Tts,
            System.currentTimeMillis() + 60_000))
        model.updateAvailability(voice, replies, false)
        model.speechConfigurationChanged("android", "android")
        assertEquals(listOf("Hello friend."), voice.spoken)
        model.updateAvailability(voice, replies, true)
        assertEquals(listOf("Hello"), replies.inputs)
        assertEquals(listOf("Hello friend.", "Hello friend."), voice.spoken)
    }

    private class FakeScheduler : ListeningScheduler {
        data class Task(val due: Long, val action: () -> Unit, var canceled: Boolean = false)
        val tasks = mutableListOf<Task>()
        private var time = 0L
        val pending get() = tasks.count { !it.canceled }

        override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
            val task = Task(time + delayMillis, action)
            tasks += task
            return { task.canceled = true }
        }

        fun advanceBy(millis: Long) {
            val target = time + millis
            while (true) {
                val next = tasks.filter { !it.canceled && it.due <= target }.minByOrNull { it.due }
                    ?: break
                next.canceled = true
                time = next.due
                next.action()
            }
            time = target
        }
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
        var recognitionRetries = 0
        var remaining: String? = null
        var stageFailed: (com.superbot.sati.ai.StageFailure) -> Unit = {}
        var cancellations = 0
        val spoken = mutableListOf<String>()
        override fun listen(callbacks: ListeningCallbacks) {
            listenCalls++
            input = callbacks
        }
        override fun retryListen(callbacks: ListeningCallbacks) {
            recognitionRetries++
            input = callbacks
        }
        override fun remainingSpeech(original: String) = remaining ?: original
        override fun speakWithFailure(
            text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit,
            onStageError: (com.superbot.sati.ai.StageFailure) -> Unit,
        ) {
            stageFailed = onStageError
            speak(text, onDone, onError)
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

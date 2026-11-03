package com.superbot.sati

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbot.sati.ai.ApiKeySetupState
import com.superbot.sati.ai.ApiKeyStatus
import com.superbot.sati.ai.AiModel
import com.superbot.sati.ai.NanoAvailability
import com.superbot.sati.ai.ModelStatus
import com.superbot.sati.ai.ReplyFailure
import com.superbot.sati.ai.AiModelState
import com.superbot.sati.conversation.ConversationPhase
import com.superbot.sati.conversation.ConversationState
import com.superbot.sati.conversation.SpeechProblem
import com.superbot.sati.ui.ConversationScreen
import com.superbot.sati.ui.theme.SatiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private val ready = AiModelState(status = ModelStatus.Ready)

    @Test
    fun idleScreenShowsPauseWithoutRequiringATalkTap() {
        var pauses = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(ConversationState(), {}, { pauses++ }, {}, model = ready)
            }
        }
        compose.onNodeWithText("Sati will listen when ready.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Talk").assertDoesNotExist()
        assertEquals(0, pauses)
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        assertEquals(1, pauses)
    }

    @Test
    fun activeTurnOffersPauseAndDisplaysConversation() {
        var cancellations = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(
                        phase = ConversationPhase.Speaking,
                        transcript = "My name is Mina",
                        reply = "Nice to meet you, Mina!",
                    ),
                    onRetry = { error("Cannot start another turn while speaking") },
                    onPauseResume = { cancellations++ },
                    onOpenSettings = {},
                    model = ready,
                )
            }
        }
        compose.onNodeWithText("My name is Mina").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sati is speaking…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        assertEquals(1, cancellations)
        compose.onNodeWithText("Change model").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun permanentPermissionDenialOffersSettingsInsteadOfRecording() {
        var settingsOpened = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(
                        phase = ConversationPhase.Error, problem = SpeechProblem.PermissionSettings,
                    ),
                    onRetry = { error("Must recover permission first") },
                    onPauseResume = {},
                    onOpenSettings = { settingsOpened++ },
                    model = ready,
                )
            }
        }
        compose.onNodeWithText("Open settings").performScrollTo().performClick()
        assertEquals(1, settingsOpened)
    }

    @Test
    fun missingModelDisablesChatAndOffersSetupRetry() {
        var retries = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(),
                    onRetry = { error("Cannot record without a local model") },
                    onPauseResume = {},
                    onOpenSettings = {},
                    onRetryModel = { retries++ },
                )
            }
        }
        compose.onNodeWithText("Pause").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Retry setup").performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun voiceOnlyScreenHasNoTextInputOrSendButton() {
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(
                        phase = ConversationPhase.Error,
                        problem = SpeechProblem.RecognitionUnavailable,
                    ),
                    {}, {}, {}, model = ready,
                )
            }
        }
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Send").assertDoesNotExist()
        compose.onNodeWithText("Or type a message").assertDoesNotExist()
        compose.onNodeWithText("Try again").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun speechModelSetupOffersPauseWithoutStartingAnotherRecording() {
        var cancellations = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(phase = ConversationPhase.PreparingSpeech),
                    onRetry = { error("Setup must finish before recording") },
                    onPauseResume = { cancellations++ },
                    onOpenSettings = {},
                    model = ready,
                )
            }
        }
        compose.onNodeWithText("Downloading English speech data… Please wait.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        assertEquals(1, cancellations)
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun startOverRunsOnlyWhenTapped() {
        var resets = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(), {}, {}, {}, model = ready,
                    onNewConversation = { resets++ },
                )
            }
        }
        assertEquals(0, resets)
        compose.onNodeWithText("Start over").performScrollTo().performClick()
        assertEquals(1, resets)
    }

    @Test
    fun downloadProgressOffersCancelAndNeverAsksForAModelFile() {
        var canceled = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(), {}, {}, {},
                    model = AiModelState(
                        status = ModelStatus.Downloading, downloadedBytes = 306_861_132L,
                    ),
                    onCancelDownload = { canceled++ },
                )
            }
        }
        compose.onNodeWithText("Downloading offline AI: 50%")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pause").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Choose model file").assertDoesNotExist()
        compose.onNodeWithText("Cancel download").performScrollTo().performClick()
        assertEquals(1, canceled)
    }

    @Test
    fun chooserShowsDownloadSizesAndDisablesUnsupportedNano() {
        val choices = mutableListOf<AiModel>()
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(), {}, {}, {}, model = ready,
                    nanoAvailability = NanoAvailability.Unavailable,
                    onSelectModel = { choices += it },
                )
            }
        }
        compose.onNodeWithText("Change model").performScrollTo().performClick()
        assertEquals(emptyList<AiModel>(), choices)
        compose.onNodeWithText("Choose model").assertIsDisplayed()
        compose.onNodeWithTag("model-choice-gemini-nano").assertIsNotEnabled()
        compose.onNodeWithText("2.59 GB download. Larger model; needs more free storage and memory.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("model-choice-gemma4-e2b").performScrollTo().performClick()
        assertEquals(listOf(AiModel.Gemma), choices)
        compose.onNodeWithText("Choose model").assertDoesNotExist()
    }

    @Test
    fun supportedNanoCanBeSelected() {
        var selected: AiModel? = null
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(), {}, {}, {}, model = ready,
                    nanoAvailability = NanoAvailability.Downloadable,
                    onSelectModel = { selected = it },
                )
            }
        }
        compose.onNodeWithText("Change model").performScrollTo().performClick()
        compose.onNodeWithTag("model-choice-gemini-nano").performScrollTo().performClick()
        assertEquals(AiModel.GeminiNano, selected)
    }

    @Test
    fun gemmaDownloadUsesItsOwnSizeAndCanChangeModelWhileDownloading() {
        var selected: AiModel? = null
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(), {}, {}, {},
                    model = AiModelState(
                        status = ModelStatus.Downloading,
                        downloadedBytes = AiModel.Gemma.downloadBytes / 2,
                        model = AiModel.Gemma,
                    ),
                    onSelectModel = { selected = it },
                )
            }
        }
        compose.onNodeWithText("Downloading offline AI: 50%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Change model").performScrollTo().performClick()
        compose.onNodeWithTag("model-choice-qwen3-0.6b").performScrollTo().performClick()
        assertEquals(AiModel.Qwen, selected)
    }

    @Test
    fun selectingDeepSeekOpensMaskedKeySetupAndConnectUsesThePastedKey() {
        val setup = mutableStateOf(ApiKeySetupState())
        var connected = ""
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(), {}, {}, {}, model = ready,
                    keySetup = setup.value,
                    onSelectModel = {
                        if (it == AiModel.DeepSeek) setup.value = ApiKeySetupState(isOpen = true)
                    },
                    onKeyDraftChange = { setup.value = setup.value.copy(draft = it) },
                    onConnectKey = { connected = setup.value.draft },
                    onDismissKey = { setup.value = ApiKeySetupState() },
                )
            }
        }
        compose.onNodeWithText("Change model").performScrollTo().performClick()
        compose.onNodeWithTag("model-choice-deepseek-flash").performScrollTo().performClick()
        compose.onNodeWithText("Connect DeepSeek").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithTag("deepseek-api-key")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextInput("test-only-key")
        compose.onNodeWithText("Connect").performClick()
        assertEquals("test-only-key", connected)
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Connect DeepSeek").assertDoesNotExist()
        compose.onNodeWithText("Qwen3 0.6B").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun savedCloudKeyEnablesVoiceAndOffersKeyManagement() {
        var edits = 0
        var removals = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(), {}, {}, {},
                    model = AiModelState(status = ModelStatus.Ready, model = AiModel.DeepSeek),
                    keyStatus = ApiKeyStatus(loaded = true, hasKey = true),
                    onEditKey = { edits++ },
                    onRemoveKey = { removals++ },
                )
            }
        }
        compose.onNodeWithText("Cloud · internet required").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pause").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Offline AI ready").assertDoesNotExist()
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Change API key").performScrollTo().performClick()
        compose.onNodeWithText("Remove API key").performScrollTo().performClick()
        assertEquals(1, edits)
        assertEquals(1, removals)
    }

    @Test
    fun removedKeyDisablesListeningAndOffersSetup() {
        var edits = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(), {}, {}, {},
                    model = AiModelState(status = ModelStatus.NeedsApiKey, model = AiModel.DeepSeek),
                    keyStatus = ApiKeyStatus(loaded = true),
                    onEditKey = { edits++ },
                )
            }
        }
        compose.onNodeWithText("Pause").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Set API key").performScrollTo().performClick()
        assertEquals(1, edits)
    }

    @Test
    fun cloudBalanceErrorIsSeparateFromMicrophoneErrors() {
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(phase = ConversationPhase.Error, replyFailure = ReplyFailure.Balance),
                    {}, {}, {},
                    model = AiModelState(status = ModelStatus.Ready, model = AiModel.DeepSeek),
                )
            }
        }
        compose.onNodeWithText(
            "Your DeepSeek API balance is insufficient. Add credit to your DeepSeek account, then try again.",
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Try again").performScrollTo().assertIsEnabled()
    }


    @Test
    fun pauseAndResumeUpdateTheControlWithoutAddingATalkButton() {
        val state = mutableStateOf(ConversationState(phase = ConversationPhase.Listening))
        var toggles = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    state.value, {},
                    {
                        toggles++
                        state.value = state.value.copy(
                            phase = ConversationPhase.Idle, isPaused = !state.value.isPaused,
                        )
                    },
                    {}, model = ready,
                )
            }
        }
        compose.onNodeWithText("Pause").performScrollTo().performClick()
        compose.onNodeWithText("Listening paused.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Resume").performScrollTo().performClick()
        compose.onNodeWithText("Pause").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Talk").assertDoesNotExist()
        assertEquals(2, toggles)
    }

    @Test
    fun listeningAllowsStartOverAndModelChooserSuspendsTheMicrophone() {
        val blocking = mutableListOf<Boolean>()
        var resets = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(phase = ConversationPhase.Listening), {}, {}, {},
                    model = ready,
                    onNewConversation = { resets++ },
                    onDialogVisibilityChanged = { blocking += it },
                )
            }
        }
        compose.onNodeWithText("Start over").performScrollTo().performClick()
        assertEquals(1, resets)
        compose.onNodeWithText("Change model").performScrollTo().performClick()
        compose.onNodeWithText("Choose model").assertIsDisplayed()
        compose.runOnIdle { assertEquals(true, blocking.last()) }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(false, blocking.last()) }
    }

    @Test
    fun deniedPermissionOffersAnExplicitRequestWithoutStartingListening() {
        var requests = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(
                        phase = ConversationPhase.Error, problem = SpeechProblem.PermissionDenied,
                    ),
                    onRetry = { requests++ },
                    onPauseResume = { error("Permission is required first") },
                    onOpenSettings = {},
                    model = ready,
                )
            }
        }
        assertEquals(0, requests)
        compose.onNodeWithText("Allow microphone").performScrollTo().performClick()
        assertEquals(1, requests)
        compose.onNodeWithText("Talk").assertDoesNotExist()
    }

    @Test
    fun keySetupBlocksListeningUntilDismissed() {
        val setup = mutableStateOf(ApiKeySetupState(isOpen = true))
        val blocking = mutableListOf<Boolean>()
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(), {}, {}, {}, model = ready,
                    keySetup = setup.value,
                    onDismissKey = { setup.value = ApiKeySetupState() },
                    onDialogVisibilityChanged = { blocking += it },
                )
            }
        }
        compose.onNodeWithText("Connect DeepSeek").assertIsDisplayed()
        compose.runOnIdle { assertEquals(true, blocking.last()) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(false, blocking.last()) }
    }


    @Test
    fun threeStagesCanBeSelectedIndependentlyWithTheSameGroqConnection() {
        val config = mutableStateOf(com.superbot.sati.ai.PipelineConfig())
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(), {}, {}, {},
                    model = AiModelState(status = ModelStatus.Ready, model = config.value.chat),
                    config = config.value,
                    groqKeyStatus = ApiKeyStatus(loaded = true, hasKey = true),
                    keyStatus = ApiKeyStatus(loaded = true, hasKey = true),
                    onSelectStt = { config.value = config.value.copy(stt = it) },
                    onSelectTts = { config.value = config.value.copy(tts = it) },
                    onSelectModel = { config.value = config.value.copy(chat = it) },
                    onSelectVoice = { config.value = config.value.copy(voice = it) },
                )
            }
        }
        compose.onNodeWithText("STT · Listen").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Model · Conversation").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("TTS · Speak").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Change STT").performScrollTo().performClick()
        compose.onNodeWithTag("speech-choice-whisper-large-v3-turbo").performScrollTo().performClick()
        compose.onNodeWithText("Change model").performScrollTo().performClick()
        compose.onNodeWithTag("model-choice-openai/gpt-oss-20b").performScrollTo().performClick()
        compose.onNodeWithText("Change TTS").performScrollTo().performClick()
        compose.onNodeWithTag("speech-choice-canopylabs/orpheus-v1-english").performScrollTo().performClick()
        compose.onNodeWithText("Voice: Troy").performScrollTo().performClick()
        compose.onNodeWithTag("speech-choice-daniel").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(com.superbot.sati.ai.SttModel.WhisperTurbo, config.value.stt)
            assertEquals(AiModel.Groq20, config.value.chat)
            assertEquals(com.superbot.sati.ai.TtsModel.Orpheus, config.value.tts)
            assertEquals(com.superbot.sati.ai.OrpheusVoice.Daniel, config.value.voice)
        }
        compose.onNodeWithTag("groq-api-key").assertDoesNotExist()
        compose.onNodeWithText("Talk").assertDoesNotExist()
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun aMissingGroqKeyOpensMaskedSetupAndCancelPreservesThePreviousStage() {
        val setup = mutableStateOf(ApiKeySetupState())
        val config = com.superbot.sati.ai.PipelineConfig()
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(), {}, {}, {}, model = ready, config = config,
                    groqKeyStatus = ApiKeyStatus(loaded = true),
                    keySetup = setup.value,
                    onSelectStt = {
                        setup.value = ApiKeySetupState(
                            isOpen = true, provider = com.superbot.sati.ai.CloudProvider.Groq)
                    },
                    onKeyDraftChange = { setup.value = setup.value.copy(draft = it) },
                    onDismissKey = { setup.value = ApiKeySetupState() },
                )
            }
        }
        compose.onNodeWithText("Change STT").performScrollTo().performClick()
        compose.onNodeWithTag("speech-choice-whisper-large-v3-turbo").performScrollTo().performClick()
        compose.onNodeWithText("Connect Groq").assertIsDisplayed()
        compose.onNodeWithTag("groq-api-key")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextInput("test-groq-key")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("groq-api-key").assertDoesNotExist()
        compose.runOnIdle { assertEquals(com.superbot.sati.ai.SttModel.Android, config.stt) }
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun aTtsLimitShowsACountdownAndAnExplicitOnDeviceRecoveryWithoutRetrying() {
        var switched = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(
                        phase = ConversationPhase.Error, reply = "Hello friend!",
                        stageFailure = com.superbot.sati.ai.StageFailure(
                            com.superbot.sati.ai.PipelineStage.Tts,
                            com.superbot.sati.ai.CloudProvider.Groq,
                            "canopylabs/orpheus-v1-english", ReplyFailure.RateLimit,
                            System.currentTimeMillis() + 60_000,
                        ),
                    ),
                    onRetry = { error("A cooldown must not submit any request") },
                    onPauseResume = {}, onOpenSettings = {}, model = ready,
                    config = com.superbot.sati.ai.PipelineConfig(tts = com.superbot.sati.ai.TtsModel.Orpheus),
                    groqKeyStatus = ApiKeyStatus(loaded = true, hasKey = true),
                    onUseDevice = { switched++ },
                )
            }
        }
        compose.onNodeWithTag("retry-stage").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Your reply is saved. Retry resumes the unplayed part without generating a new reply.")
            .performScrollTo().assertIsDisplayed()
        assertEquals(0, switched)
        compose.onNodeWithText("Use Android TTS · offline voice required").performScrollTo().performClick()
        assertEquals(1, switched)
        compose.onNodeWithText("Hello friend!").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unknownRetryTimeAndReportedQuotaDoNotInventAnAccountAllowance() {
        var retries = 0
        val config = com.superbot.sati.ai.PipelineConfig(stt = com.superbot.sati.ai.SttModel.Whisper)
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(phase = ConversationPhase.Error,
                        stageFailure = com.superbot.sati.ai.StageFailure(
                            com.superbot.sati.ai.PipelineStage.Stt,
                            com.superbot.sati.ai.CloudProvider.Groq, config.stt.id, ReplyFailure.RateLimit)),
                    { retries++ }, {}, {}, model = ready, config = config,
                    groqKeyStatus = ApiKeyStatus(loaded = true, hasKey = true),
                    quotas = mapOf(config.stt.id to com.superbot.sati.ai.RateLimitSnapshot(
                        config.stt.id, System.currentTimeMillis(), remainingRequests = 27)),
                )
            }
        }
        compose.onNodeWithText("Daily requests remaining: 27").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Groq did not report a retry time.").performScrollTo().assertIsDisplayed()
        assertEquals(0, retries)
        compose.onNodeWithTag("retry-stage").performScrollTo().performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("Daily requests remaining: 2000").assertDoesNotExist()
    }

    @Test
    fun readyModelOffersModelSelectionWithoutAManualFilePicker() {
        compose.setContent {
            SatiTheme { ConversationScreen(ConversationState(), {}, {}, {}, model = ready) }
        }
        compose.onNodeWithText("Offline AI ready").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose model file").assertDoesNotExist()
        compose.onNodeWithText("Change model").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry setup").assertDoesNotExist()
    }
}

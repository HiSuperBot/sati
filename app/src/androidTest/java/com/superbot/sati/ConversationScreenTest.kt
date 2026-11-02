package com.superbot.sati

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superbot.sati.ai.AiModel
import com.superbot.sati.ai.NanoAvailability
import com.superbot.sati.ai.ModelStatus
import com.superbot.sati.ai.OfflineModelState
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
    private val ready = OfflineModelState(status = ModelStatus.Ready)

    @Test
    fun idleScreenStartsOnlyOnTalk() {
        var starts = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(ConversationState(), { starts++ }, {}, {}, model = ready)
            }
        }
        compose.onNodeWithText("Tap Talk and say something in English.")
            .performScrollTo().assertIsDisplayed()
        assertEquals(0, starts)
        compose.onNodeWithText("Talk").performScrollTo().performClick()
        assertEquals(1, starts)
    }

    @Test
    fun activeTurnOffersCancelAndDisplaysConversation() {
        var cancellations = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(
                        phase = ConversationPhase.Speaking,
                        transcript = "My name is Mina",
                        reply = "Nice to meet you, Mina!",
                    ),
                    onTalk = { error("Cannot start another turn while speaking") },
                    onCancel = { cancellations++ },
                    onOpenSettings = {},
                    model = ready,
                )
            }
        }
        compose.onNodeWithText("My name is Mina").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sati is speaking…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
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
                    onTalk = { error("Must recover permission first") },
                    onCancel = {},
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
                    onTalk = { error("Cannot record without a local model") },
                    onCancel = {},
                    onOpenSettings = {},
                    onRetryModel = { retries++ },
                )
            }
        }
        compose.onNodeWithText("Talk").performScrollTo().assertIsNotEnabled()
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
    fun speechModelSetupOffersCancelWithoutStartingAnotherRecording() {
        var cancellations = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(phase = ConversationPhase.PreparingSpeech),
                    onTalk = { error("Setup must finish before recording") },
                    onCancel = { cancellations++ },
                    onOpenSettings = {},
                    model = ready,
                )
            }
        }
        compose.onNodeWithText("Downloading English speech data… Please wait.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
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
                    model = OfflineModelState(
                        status = ModelStatus.Downloading, downloadedBytes = 306_861_132L,
                    ),
                    onCancelDownload = { canceled++ },
                )
            }
        }
        compose.onNodeWithText("Downloading offline AI: 50%")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Talk").performScrollTo().assertIsNotEnabled()
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
        compose.onNodeWithText("Choose offline model").assertIsDisplayed()
        compose.onNodeWithTag("model-choice-gemini-nano").assertIsNotEnabled()
        compose.onNodeWithText("2.59 GB download. Larger model; needs more free storage and memory.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("model-choice-gemma4-e2b").performScrollTo().performClick()
        assertEquals(listOf(AiModel.Gemma), choices)
        compose.onNodeWithText("Choose offline model").assertDoesNotExist()
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
                    model = OfflineModelState(
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

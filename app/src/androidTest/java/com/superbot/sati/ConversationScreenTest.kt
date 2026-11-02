package com.superbot.sati

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Test
    fun idleScreenStartsOnlyOnTalk() {
        var starts = 0
        compose.setContent {
            SatiTheme { ConversationScreen(ConversationState(), { starts++ }, {}, {}) }
        }
        compose.onNodeWithText("Tap Talk and say something in English.").assertIsDisplayed()
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
                        transcript = "Hello",
                        reply = "Hello! What's your name?",
                    ),
                    onTalk = { error("Cannot start another turn while speaking") },
                    onCancel = { cancellations++ },
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithText("Hello").assertIsDisplayed()
        compose.onNodeWithText("Sati is speaking…").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(1, cancellations)
    }

    @Test
    fun permanentPermissionDenialOffersSettingsInsteadOfRecording() {
        var settingsOpened = 0
        compose.setContent {
            SatiTheme {
                ConversationScreen(
                    ConversationState(
                        phase = ConversationPhase.Error,
                        problem = SpeechProblem.PermissionSettings,
                    ),
                    onTalk = { error("Must recover permission first") },
                    onCancel = {},
                    onOpenSettings = { settingsOpened++ },
                )
            }
        }
        compose.onNodeWithText("Open settings").performScrollTo().performClick()
        assertEquals(1, settingsOpened)
    }
}

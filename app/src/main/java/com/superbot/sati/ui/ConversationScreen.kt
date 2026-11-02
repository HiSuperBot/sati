package com.superbot.sati.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.superbot.sati.R
import com.superbot.sati.conversation.ConversationPhase
import com.superbot.sati.conversation.ConversationState
import com.superbot.sati.conversation.SpeechProblem
import com.superbot.sati.ui.theme.SatiTheme

@Composable
fun ConversationScreen(
    state: ConversationState,
    onTalk: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Text(
                    stringResource(R.string.introduction),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
                SatiEyes(state.phase)
                Text(
                    text = stringResource(state.statusResource()),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = if (state.problem != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                )
                if (state.transcript.isNotEmpty()) {
                    ConversationCard(stringResource(R.string.you_said), state.transcript)
                }
                if (state.reply.isNotEmpty()) {
                    ConversationCard(stringResource(R.string.sati_says), state.reply)
                }
                val needsSettings = state.problem == SpeechProblem.PermissionSettings
                Button(
                    onClick = when {
                        state.isActive -> onCancel
                        needsSettings -> onOpenSettings
                        else -> onTalk
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text(
                        stringResource(when {
                            state.isActive -> R.string.cancel
                            needsSettings -> R.string.open_settings
                            state.phase == ConversationPhase.Error -> R.string.try_again
                            else -> R.string.talk
                        }),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                Text(
                    stringResource(R.string.turn_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ConversationCard(label: String, text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SatiEyes(phase: ConversationPhase) {
    val transition = rememberInfiniteTransition(label = "Sati eyes")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(keyframes {
            durationMillis = 4_000
            1f at 0
            1f at 3_600
            0.12f at 3_700
            1f at 3_850
        }),
        label = "Blink",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "Speaking pulse",
    )
    val openness by animateFloatAsState(
        targetValue = when (phase) {
            ConversationPhase.Listening -> 1.15f
            ConversationPhase.Processing -> 0.55f
            ConversationPhase.Error -> 0.7f
            else -> 1f
        },
        label = "Expression",
    )
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(240.dp, 150.dp).clearAndSetSemantics {}) {
        val eyeWidth = size.width * 0.19f
        val eyeHeight = size.height * 0.57f * openness * blink *
            if (phase == ConversationPhase.Speaking) pulse else 1f
        for (center in listOf(size.width * 0.3f, size.width * 0.7f)) {
            drawRoundRect(
                color = color,
                topLeft = Offset(center - eyeWidth / 2f, (size.height - eyeHeight) / 2f),
                size = Size(eyeWidth, eyeHeight),
                cornerRadius = CornerRadius(eyeWidth / 2f, eyeWidth / 2f),
            )
        }
    }
}

private fun ConversationState.statusResource(): Int = when (problem) {
    SpeechProblem.PermissionDenied -> R.string.error_permission
    SpeechProblem.PermissionSettings -> R.string.error_permission_settings
    SpeechProblem.RecognitionUnavailable -> R.string.error_recognition_unavailable
    SpeechProblem.NoSpeech -> R.string.error_no_speech
    SpeechProblem.Network -> R.string.error_network
    SpeechProblem.RecognitionFailed -> R.string.error_recognition
    SpeechProblem.SpeechUnavailable -> R.string.error_speech_unavailable
    SpeechProblem.EnglishUnavailable -> R.string.error_english_unavailable
    SpeechProblem.PlaybackFailed -> R.string.error_playback
    null -> when (phase) {
        ConversationPhase.Idle -> R.string.status_idle
        ConversationPhase.Listening -> R.string.status_listening
        ConversationPhase.Processing -> R.string.status_processing
        ConversationPhase.Speaking -> R.string.status_speaking
        ConversationPhase.Error -> R.string.error_recognition
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationPreview() {
    SatiTheme {
        ConversationScreen(ConversationState(), {}, {}, {})
    }
}

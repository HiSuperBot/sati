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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
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
import com.superbot.sati.ai.AiModel
import com.superbot.sati.ai.CloudProvider
import com.superbot.sati.ai.PipelineConfig
import com.superbot.sati.ai.PipelineStage
import com.superbot.sati.ai.SttModel
import com.superbot.sati.ai.TtsModel
import com.superbot.sati.ai.OrpheusVoice
import com.superbot.sati.ai.RateLimitSnapshot
import com.superbot.sati.ai.NanoAvailability
import com.superbot.sati.ai.ApiKeyStatus
import com.superbot.sati.ai.ApiKeySetupState
import com.superbot.sati.ai.ModelProblem
import com.superbot.sati.ai.ModelStatus
import com.superbot.sati.ai.AiModelState
import com.superbot.sati.conversation.ConversationPhase
import com.superbot.sati.conversation.ConversationState
import com.superbot.sati.conversation.SpeechProblem
import com.superbot.sati.ui.theme.SatiTheme

@Composable
fun ConversationScreen(
    state: ConversationState,
    onRetry: () -> Unit,
    onPauseResume: () -> Unit,
    onOpenSettings: () -> Unit,
    model: AiModelState = AiModelState(status = ModelStatus.Missing),
    onCancelDownload: () -> Unit = {},
    onRetryModel: () -> Unit = {},
    onNewConversation: () -> Unit = {},
    nanoAvailability: NanoAvailability = NanoAvailability.Checking,
    onSelectModel: (AiModel) -> Unit = {},
    onCheckNano: () -> Unit = {},
    keySetup: ApiKeySetupState = ApiKeySetupState(),
    keyStatus: ApiKeyStatus = ApiKeyStatus(),
    onEditKey: () -> Unit = {},
    onKeyDraftChange: (String) -> Unit = {},
    onConnectKey: () -> Unit = {},
    onDismissKey: () -> Unit = {},
    onRemoveKey: () -> Unit = {},
    onDialogVisibilityChanged: (Boolean) -> Unit = {},
    config: PipelineConfig = PipelineConfig(chat = model.model),
    groqKeyStatus: ApiKeyStatus = ApiKeyStatus(),
    quotas: Map<String, RateLimitSnapshot> = emptyMap(),
    onSelectStt: (SttModel) -> Unit = {},
    onSelectTts: (TtsModel) -> Unit = {},
    onSelectVoice: (OrpheusVoice) -> Unit = {},
    onGroqKey: () -> Unit = {},
    onRemoveGroqKey: () -> Unit = {},
    onUseDevice: (PipelineStage) -> Unit = {},
    androidInputAvailable: Boolean = true,
) {
    val ready = model.status == ModelStatus.Ready && (!config.usesGroq || groqKeyStatus.hasKey)
    val canChange = !state.isActive || state.phase == ConversationPhase.Listening
    var showSpeech by rememberSaveable { mutableStateOf<String?>(null) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showModels, showSpeech, keySetup.isOpen) {
        onDialogVisibilityChanged(showModels || showSpeech != null || keySetup.isOpen)
    }
    DisposableEffect(Unit) {
        onDispose { onDialogVisibilityChanged(false) }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding)
                .consumeWindowInsets(padding),
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
                state.stageFailure?.let { failure ->
                    FailureRecoveryCard(
                        failure, onRetry,
                        onChoose = {
                            onDialogVisibilityChanged(true)
                            if (failure.stage == PipelineStage.Chat) showModels = true
                            else showSpeech = failure.stage.name
                        },
                        onDevice = { onUseDevice(failure.stage) },
                        onKey = if (failure.provider == CloudProvider.Groq) onGroqKey else onEditKey,
                        deviceAvailable = failure.stage != PipelineStage.Stt || androidInputAvailable,
                        canRetry = ready && !state.isPaused,
                    )
                }
                Text(
                    text = stringResource(
                        if (ready || state.problem != null || state.replyFailure != null || state.stageFailure != null) state.statusResource()
                        else if (config.usesGroq && !groqKeyStatus.hasKey) R.string.groq_key_required
                        else if (model.model.isCloud) R.string.api_key_required
                        else R.string.offline_setup_required,
                    ),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = if (state.problem != null || state.replyFailure != null || state.stageFailure != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                )
                val needsSettings = state.problem == SpeechProblem.PermissionSettings
                val needsPermission = state.problem == SpeechProblem.PermissionDenied
                Button(
                    onClick = when {
                        needsSettings -> onOpenSettings
                        needsPermission -> onRetry
                        state.isPaused || state.stageFailure != null -> onPauseResume
                        state.phase == ConversationPhase.Error -> onRetry
                        else -> onPauseResume
                    },
                    enabled = ready || state.isActive || state.isPaused || needsSettings || needsPermission,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text(
                        stringResource(when {
                            needsSettings -> R.string.open_settings
                            needsPermission -> R.string.allow_microphone
                            state.isPaused -> R.string.resume_listening
                            state.stageFailure != null -> R.string.pause_listening
                            state.phase == ConversationPhase.Error -> R.string.try_again
                            else -> R.string.pause_listening
                        }),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                SpeechSelectionCard(
                    PipelineStage.Stt, config.stt.label, config.stt.isCloud, groqKeyStatus,
                    quotas[config.stt.id],
                    onChange = { onDialogVisibilityChanged(true); showSpeech = "Stt" },
                    onKey = onGroqKey, onRemoveKey = onRemoveGroqKey, canChange = canChange,
                )
                ModelCard(
                    model, onCancelDownload, onRetryModel,
                    canChangeModel = !state.isActive || state.phase == ConversationPhase.Listening,
                    onChangeModel = {
                        onDialogVisibilityChanged(true)
                        showModels = true
                    },
                    keyStatus = keyStatus, onEditKey = onEditKey, onRemoveKey = onRemoveKey,
                )
                if (model.model.provider == CloudProvider.Groq) QuotaStatus(quotas[model.model.id])
                SpeechSelectionCard(
                    PipelineStage.Tts, config.tts.label, config.tts.isCloud, groqKeyStatus,
                    quotas[config.tts.id],
                    onChange = { onDialogVisibilityChanged(true); showSpeech = "Tts" },
                    onKey = onGroqKey, onRemoveKey = onRemoveGroqKey, canChange = canChange,
                    voice = if (config.tts.isCloud) config.voice else null,
                    onVoice = { onDialogVisibilityChanged(true); showSpeech = "Voice" },
                )
                if (state.transcript.isNotEmpty()) {
                    ConversationCard(stringResource(R.string.you_said), state.transcript)
                }
                if (state.reply.isNotEmpty()) {
                    ConversationCard(stringResource(R.string.sati_says), state.reply)
                }
                TextButton(
                    onClick = onNewConversation,
                    enabled = ready,
                ) { Text(stringResource(R.string.start_over)) }
                Text(
                    stringResource(R.string.turn_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                PipelinePrivacy(config)
            }
        }
    }
    if (keySetup.isOpen) {
        ApiKeySetupDialog(keySetup, onKeyDraftChange, onConnectKey, onDismissKey)
    }

    if (showSpeech != null) {
        val selection = showSpeech
        val choices = when (selection) {
            "Stt" -> SttModel.entries.map {
                SpeechChoice(it.id, it.label,
                    stringResource(if (it.isCloud) R.string.groq_stt_description else R.string.android_stt_description),
                    it.isCloud || androidInputAvailable)
            }
            "Tts" -> TtsModel.entries.map {
                SpeechChoice(it.id, it.label,
                    stringResource(if (it.isCloud) R.string.groq_tts_description else R.string.android_tts_description))
            }
            else -> OrpheusVoice.entries.map { SpeechChoice(it.id, it.label, "") }
        }
        SpeechChooser(
            title = stringResource(when (selection) {
                "Stt" -> R.string.choose_stt
                "Tts" -> R.string.choose_tts
                else -> R.string.choose_voice
            }),
            choices = choices,
            selected = when (selection) {
                "Stt" -> config.stt.id
                "Tts" -> config.tts.id
                else -> config.voice.id
            },
            onChoose = {
                when (selection) {
                    "Stt" -> onSelectStt(SttModel.fromId(it))
                    "Tts" -> onSelectTts(TtsModel.fromId(it))
                    else -> onSelectVoice(OrpheusVoice.fromId(it))
                }
                showSpeech = null
                onDialogVisibilityChanged(keySetup.isOpen)
            },
            onDismiss = {
                showSpeech = null
                onDialogVisibilityChanged(keySetup.isOpen)
            },
        )
    }

    if (showModels) {
        ModelChooser(
            selected = model.model,
            nanoAvailability = nanoAvailability,
            onSelect = {
                onSelectModel(it)
                showModels = false
                onDialogVisibilityChanged(keySetup.isOpen)
            },
            onCheckNano = onCheckNano,
            onDismiss = {
                showModels = false
                onDialogVisibilityChanged(keySetup.isOpen)
            },
        )
    }
}

@Composable
private fun ModelChooser(
    selected: AiModel,
    nanoAvailability: NanoAvailability,
    onSelect: (AiModel) -> Unit,
    onCheckNano: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_model)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.model_switch_hint))
                AiModel.entries.forEach { choice ->
                    OutlinedButton(
                        onClick = { onSelect(choice) },
                        enabled = choice != AiModel.GeminiNano || nanoAvailability.canSelect,
                        modifier = Modifier.fillMaxWidth().testTag("model-choice-${choice.id}"),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(choice.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(when (choice) {
                                    AiModel.Qwen -> R.string.qwen_description
                                    AiModel.Gemma -> R.string.gemma_description
                                    AiModel.GeminiNano -> nanoAvailability.descriptionResource()
                                    AiModel.DeepSeek -> R.string.deepseek_description
                                    AiModel.Groq20, AiModel.Groq120 -> R.string.groq_description
                                }),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (choice == selected) Text(stringResource(R.string.selected_model))
                        }
                    }
                }
                TextButton(
                    onClick = onCheckNano,
                    enabled = nanoAvailability != NanoAvailability.Checking,
                ) { Text(stringResource(R.string.check_nano)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

private fun NanoAvailability.descriptionResource() = when (this) {
    NanoAvailability.Checking -> R.string.nano_checking
    NanoAvailability.Available -> R.string.nano_available
    NanoAvailability.Downloadable -> R.string.nano_downloadable
    NanoAvailability.Downloading -> R.string.nano_downloading
    NanoAvailability.Unavailable -> R.string.nano_unavailable
    NanoAvailability.CheckFailed -> R.string.nano_check_failed
}

@Composable
private fun setupDescription(model: AiModel): String = stringResource(
    when (model) {
        AiModel.Qwen -> R.string.qwen_description
        AiModel.Gemma -> R.string.gemma_description
        AiModel.GeminiNano -> R.string.nano_downloadable
        AiModel.DeepSeek -> R.string.deepseek_description
        AiModel.Groq20, AiModel.Groq120 -> R.string.groq_description
    },
)

@Composable
private fun ModelCard(
    state: AiModelState,
    onCancelDownload: () -> Unit,
    onRetry: () -> Unit,
    canChangeModel: Boolean,
    onChangeModel: () -> Unit,
    keyStatus: ApiKeyStatus,
    onEditKey: () -> Unit,
    onRemoveKey: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.model_heading), style = MaterialTheme.typography.titleMedium)
            Text(state.model.displayName, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (state.model.isCloud) R.string.cloud_mode else R.string.on_device_mode),
                style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onChangeModel, enabled = canChangeModel) {
                Text(stringResource(R.string.change_model))
            }
            if (state.model.isCloud) {
                Text(stringResource(when (state.status) {
                    ModelStatus.Ready -> if (state.model.provider == CloudProvider.Groq)
                        R.string.groq_key_connected else R.string.cloud_ready
                    ModelStatus.Loading -> if (state.model.provider == CloudProvider.Groq)
                        R.string.groq_preparing else R.string.cloud_preparing
                    else -> if (state.model.provider == CloudProvider.Groq)
                        R.string.groq_key_required else R.string.api_key_required
                }), style = MaterialTheme.typography.titleSmall)
                if (state.problem == ModelProblem.CredentialStorage) {
                    Text(stringResource(R.string.error_key_storage))
                }
                TextButton(onClick = onEditKey, enabled = canChangeModel && keyStatus.loaded) {
                    Text(stringResource(if (keyStatus.hasKey) R.string.change_api_key else R.string.set_api_key))
                }
                if (keyStatus.hasKey) {
                    TextButton(onClick = onRemoveKey, enabled = canChangeModel) {
                        Text(stringResource(R.string.remove_api_key))
                    }
                }
            } else {
                Text(
                    stringResource(when (state.status) {
                        ModelStatus.Ready -> R.string.offline_ready
                        ModelStatus.Loading -> R.string.offline_loading
                        ModelStatus.Downloading -> if (state.model == AiModel.GeminiNano)
                            R.string.nano_downloading else R.string.offline_downloading
                        else -> R.string.offline_missing
                    }, state.downloadPercent),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                when (state.status) {
                    ModelStatus.Downloading -> {
                        Text(setupDescription(state.model))
                        if (state.model == AiModel.GeminiNano) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { state.downloadPercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TextButton(onClick = onCancelDownload) {
                            Text(stringResource(R.string.cancel_download))
                        }
                    }
                    ModelStatus.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                    ModelStatus.Ready -> Unit
                    else -> {
                        val problem = state.problem
                        if (problem == null) Text(setupDescription(state.model))
                        else Text(stringResource(when (problem) {
                            ModelProblem.DownloadFailed -> R.string.model_download_failed
                            ModelProblem.VerificationFailed -> R.string.model_verification_failed
                            ModelProblem.DownloadCanceled -> R.string.model_download_canceled
                            ModelProblem.LoadFailed -> R.string.model_load_failed
                            ModelProblem.UnsupportedDevice -> R.string.model_device_unsupported
                            ModelProblem.NanoUnavailable -> R.string.nano_unavailable
                            ModelProblem.CredentialStorage -> R.string.error_key_storage
                        }))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry_setup)) }
                    }
                }
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

private fun ConversationState.statusResource(): Int = if (isPaused) R.string.status_paused
    else stageFailure?.messageResource() ?: replyFailure?.messageResource() ?: when (problem) {
    SpeechProblem.PermissionDenied -> R.string.error_permission
    SpeechProblem.PermissionSettings -> R.string.error_permission_settings
    SpeechProblem.RecognitionUnavailable -> R.string.error_recognition_unavailable
    SpeechProblem.SpeechDownloadPending -> R.string.error_speech_download_pending
    SpeechProblem.SpeechDownloadFailed -> R.string.error_speech_download_failed
    SpeechProblem.NoSpeech -> R.string.error_no_speech
    SpeechProblem.Network -> R.string.error_network
    SpeechProblem.RecognitionFailed -> R.string.error_recognition
    SpeechProblem.SpeechUnavailable -> R.string.error_speech_unavailable
    SpeechProblem.EnglishUnavailable -> R.string.error_english_unavailable
    SpeechProblem.KoreanUnavailable -> R.string.error_korean_unavailable
    SpeechProblem.PlaybackFailed -> R.string.error_playback
    SpeechProblem.ModelUnavailable -> R.string.error_model_unavailable
    SpeechProblem.GenerationFailed -> R.string.error_generation
    SpeechProblem.InputTooLong -> R.string.error_input_length
    null -> when (phase) {
        ConversationPhase.Idle -> R.string.status_idle
        ConversationPhase.PreparingSpeech -> R.string.status_preparing_speech
        ConversationPhase.Listening -> R.string.status_listening
        ConversationPhase.Processing -> R.string.status_processing
        ConversationPhase.Speaking -> R.string.status_speaking
        ConversationPhase.Error -> R.string.error_recognition
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationPreview() {
    SatiTheme { ConversationScreen(ConversationState(), {}, {}, {}) }
}

@Preview(name = "DeepSeek setup", showBackground = true)
@Composable
private fun DeepSeekSetupPreview() {
    SatiTheme {
        ConversationScreen(
            ConversationState(), {}, {}, {},
            model = AiModelState(status = ModelStatus.Ready),
            keySetup = ApiKeySetupState(isOpen = true),
        )
    }
}

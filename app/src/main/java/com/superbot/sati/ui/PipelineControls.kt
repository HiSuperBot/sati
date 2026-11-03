package com.superbot.sati.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.superbot.sati.R
import com.superbot.sati.ai.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
internal fun SpeechSelectionCard(
    stage: PipelineStage, label: String, cloud: Boolean, status: ApiKeyStatus,
    quota: RateLimitSnapshot?, onChange: () -> Unit, onKey: () -> Unit,
    onRemoveKey: () -> Unit, canChange: Boolean,
    voice: OrpheusVoice? = null, onVoice: () -> Unit = {},
) {
    Card(Modifier.fillMaxWidth().testTag("pipeline-${stage.name}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (stage == PipelineStage.Stt) R.string.stt_heading else R.string.tts_heading),
                style = MaterialTheme.typography.titleMedium)
            Text(label)
            Text(stringResource(if (cloud) R.string.cloud_mode else R.string.on_device_mode),
                style = MaterialTheme.typography.labelMedium)
            if (cloud) {
                Text(stringResource(if (!status.loaded) R.string.groq_key_loading
                    else if (status.hasKey) R.string.groq_key_connected else R.string.groq_key_required))
                TextButton(onClick = onKey, enabled = canChange && status.loaded,
                    modifier = Modifier.testTag("key-${stage.name}")) {
                    Text(stringResource(if (status.hasKey) R.string.manage_groq_key else R.string.connect_groq))
                }
                if (status.hasKey) TextButton(onClick = onRemoveKey, enabled = canChange,
                    modifier = Modifier.testTag("remove-key-${stage.name}")) {
                    Text(stringResource(R.string.remove_groq_key))
                }
                QuotaStatus(quota)
            }
            TextButton(onClick = onChange, enabled = canChange) {
                Text(stringResource(if (stage == PipelineStage.Stt) R.string.change_stt else R.string.change_tts))
            }
            if (voice != null) TextButton(onClick = onVoice, enabled = canChange) {
                Text(stringResource(R.string.selected_voice, voice.label))
            }
        }
    }
}

internal data class SpeechChoice(val id: String, val label: String, val detail: String, val enabled: Boolean = true)

@Composable
internal fun SpeechChooser(
    title: String, choices: List<SpeechChoice>, selected: String,
    onChoose: (String) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                choices.forEach { choice ->
                    OutlinedButton(onClick = { onChoose(choice.id) }, enabled = choice.enabled,
                        modifier = Modifier.fillMaxWidth().testTag("speech-choice-${choice.id}")) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(choice.label)
                            Text(choice.detail, style = MaterialTheme.typography.bodySmall)
                            if (choice.id == selected) Text(stringResource(R.string.selected_model))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
internal fun QuotaStatus(quota: RateLimitSnapshot?) {
    if (quota == null || (quota.remainingRequests == null && quota.remainingTokens == null)) {
        Text(stringResource(R.string.quota_unknown), style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        quota.remainingRequests?.let {
            Text(stringResource(R.string.quota_requests, it), style = MaterialTheme.typography.bodySmall)
        }
        quota.remainingTokens?.let {
            Text(stringResource(R.string.quota_tokens, it), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.quota_observed,
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(quota.observedAtMillis))),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun rememberWallClock(deadline: Long?): State<Long> =
    produceState(System.currentTimeMillis(), deadline) {
        value = System.currentTimeMillis()
        while (deadline != null && value < deadline) {
            delay(minOf(1_000L, (deadline - value).coerceAtLeast(1)))
            value = System.currentTimeMillis()
        }
    }

internal fun StageFailure.messageResource(): Int = when (hint) {
    FailureHint.AccessRequired -> R.string.error_groq_access
    FailureHint.ModelUnavailable -> R.string.error_groq_model
    null -> when (reason) {
        ReplyFailure.Authentication -> R.string.error_groq_auth
        ReplyFailure.Balance -> R.string.error_groq_balance
        ReplyFailure.RateLimit -> R.string.error_groq_rate
        ReplyFailure.Network -> R.string.error_groq_network
        ReplyFailure.Service -> R.string.error_groq_service
        ReplyFailure.Storage -> R.string.error_key_storage
        else -> R.string.error_groq_response
    }
}

@Composable
internal fun FailureRecoveryCard(
    failure: StageFailure, onRetry: () -> Unit, onChoose: () -> Unit,
    onDevice: () -> Unit, onKey: () -> Unit, deviceAvailable: Boolean, canRetry: Boolean,
) {
    val now by rememberWallClock(failure.retryAtMillis)
    val remaining = failure.remainingSeconds(now)
    val uri = LocalUriHandler.current
    Card(Modifier.fillMaxWidth().testTag("pipeline-failure")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${failure.stage.label} · ${failure.provider.label}",
                style = MaterialTheme.typography.titleMedium)
            Text(failure.modelId, style = MaterialTheme.typography.labelMedium)
            Text(stringResource(failure.messageResource()), color = MaterialTheme.colorScheme.error)
            if (failure.reason == ReplyFailure.RateLimit) {
                Text(stringResource(if (remaining > 0) R.string.retry_countdown
                    else if (failure.retryAtMillis == null) R.string.retry_time_unknown
                    else R.string.retry_available, remaining))
            }
            if (failure.stage == PipelineStage.Tts) Text(stringResource(R.string.tts_retry_hint))
            if (failure.stage == PipelineStage.Chat) Text(stringResource(R.string.chat_retry_hint))
            if (failure.recordingExpired) Text(stringResource(R.string.recording_expired))
            TextButton(onClick = onRetry, enabled = canRetry && remaining == 0L,
                modifier = Modifier.testTag("retry-stage")) { Text(stringResource(R.string.try_again)) }
            TextButton(onClick = onChoose) { Text(stringResource(R.string.choose_stage, failure.stage.label)) }
            TextButton(onClick = onDevice, enabled = deviceAvailable) {
                Text(stringResource(when (failure.stage) {
                    PipelineStage.Stt -> R.string.use_android_stt
                    PipelineStage.Chat -> R.string.use_qwen
                    PipelineStage.Tts -> R.string.use_android_tts
                }))
            }
            if (failure.reason == ReplyFailure.Authentication) TextButton(onClick = onKey) {
                Text(stringResource(R.string.change_api_key))
            }
            TextButton(onClick = {
                uri.openUri(if (failure.hint == FailureHint.AccessRequired)
                    "https://console.groq.com/playground?model=" + android.net.Uri.encode(failure.modelId)
                    else failure.provider.limitsUrl)
            }) { Text(stringResource(R.string.open_provider_console, failure.provider.label)) }
        }
    }
}

@Composable
internal fun PipelinePrivacy(config: PipelineConfig) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (config.stt.isCloud) Text(stringResource(R.string.groq_audio_privacy),
            style = MaterialTheme.typography.bodySmall)
        if (config.chat.isCloud) Text(stringResource(R.string.chat_text_privacy,
            config.chat.provider!!.label), style = MaterialTheme.typography.bodySmall)
        if (config.tts.isCloud) Text(stringResource(R.string.groq_tts_privacy),
            style = MaterialTheme.typography.bodySmall)
        if (!config.stt.isCloud && !config.chat.isCloud && !config.tts.isCloud) {
            Text(stringResource(R.string.offline_privacy), style = MaterialTheme.typography.bodySmall)
        }
    }
}

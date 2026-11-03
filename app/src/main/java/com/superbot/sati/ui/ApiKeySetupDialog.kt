package com.superbot.sati.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.superbot.sati.R
import com.superbot.sati.ai.ApiKeySetupState
import com.superbot.sati.ai.CloudProvider

@Composable
internal fun ApiKeySetupDialog(
    state: ApiKeySetupState,
    onDraftChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    var visible by remember(state.provider) { mutableStateOf(false) }
    val groq = state.provider == CloudProvider.Groq
    val now by rememberWallClock(state.stageFailure?.retryAtMillis)
    val remaining = state.stageFailure?.remainingSeconds(now) ?: 0
    val keyboard = LocalSoftwareKeyboardController.current
    val uri = LocalUriHandler.current
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (groq) R.string.groq_key_title else R.string.deepseek_key_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(if (groq) R.string.groq_key_setup_hint else R.string.deepseek_privacy))
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth().testTag(if (groq) "groq-api-key" else "deepseek-api-key"),
                    label = { Text(stringResource(if (groq) R.string.groq_api_key else R.string.api_key)) },
                    singleLine = true,
                    enabled = !state.isValidating,
                    visualTransformation = if (visible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password, autoCorrectEnabled = false,
                    ),
                    isError = state.invalidInput || state.failure != null,
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) {
                            Text(stringResource(if (visible) R.string.hide_key else R.string.show_key))
                        }
                    },
                )
                if (state.invalidInput) Text(stringResource(R.string.api_key_invalid_input))
                state.failure?.let {
                    Text(stringResource(state.stageFailure?.messageResource() ?: it.messageResource()))
                }
                if (remaining > 0) Text(stringResource(R.string.retry_countdown, remaining))
                if (state.isValidating) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.api_key_checking))
                }
                Text(stringResource(R.string.api_key_storage))
                TextButton(onClick = { uri.openUri(state.provider.keyUrl) }) {
                    Text(stringResource(if (groq) R.string.get_groq_key else R.string.get_api_key))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConnect, enabled = !state.isValidating && state.draft.isNotBlank() && remaining == 0L) {
                Text(stringResource(R.string.connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

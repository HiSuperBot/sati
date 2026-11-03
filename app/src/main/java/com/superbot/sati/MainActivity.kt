package com.superbot.sati

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superbot.sati.ai.AiViewModel
import com.superbot.sati.ai.CloudProvider
import com.superbot.sati.ai.PipelineStage
import com.superbot.sati.ai.SttModel
import com.superbot.sati.ai.TtsModel
import com.superbot.sati.conversation.ConversationViewModel
import com.superbot.sati.conversation.SpeechProblem
import com.superbot.sati.speech.PipelineVoiceSession
import com.superbot.sati.ui.ConversationScreen
import com.superbot.sati.ui.theme.SatiTheme

class MainActivity : ComponentActivity() {
    private lateinit var conversation: ConversationViewModel
    private lateinit var voice: PipelineVoiceSession
    private lateinit var ai: AiViewModel
    private var foreground by mutableStateOf(false)
    private var windowFocused by mutableStateOf(false)
    private var dialogOpen by mutableStateOf(false)
    private var screenReady by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conversation = ViewModelProvider(this)[ConversationViewModel::class.java]
        ai = ViewModelProvider(this)[AiViewModel::class.java]
        voice = PipelineVoiceSession(this, ai.groq, { ai.config.value }, ai::groqKey)
        val permissions = getSharedPreferences("voice_permissions", MODE_PRIVATE)
        enableEdgeToEdge()
        setContent {
            SatiTheme {
                val state by conversation.state.collectAsStateWithLifecycle()
                val model by ai.state.collectAsStateWithLifecycle()
                val nanoAvailability by ai.nanoAvailability.collectAsStateWithLifecycle()
                val keySetup by ai.keySetup.collectAsStateWithLifecycle()
                val deepSeekKey by ai.keyStatus.collectAsStateWithLifecycle()
                val groqKey by ai.groqKeyStatus.collectAsStateWithLifecycle()
                val config by ai.config.collectAsStateWithLifecycle()
                val quotas by ai.quotas.collectAsStateWithLifecycle()
                val keyStatus = if (model.model.provider == CloudProvider.Groq) groqKey else deepSeekKey

                var showRationale by rememberSaveable { mutableStateOf(false) }
                var permissionRequestInFlight by rememberSaveable { mutableStateOf(false) }
                val microphonePermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    permissionRequestInFlight = false
                    if (granted) {
                        conversation.refreshMicrophonePermission(true)
                        syncListening()
                    } else {
                        showPermissionProblem()
                    }
                }
                fun launchPermissionRequest() {
                    permissions.edit().putBoolean("microphone_requested", true).apply()
                    permissionRequestInFlight = true
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                fun requestMicrophone() {
                    when {
                        hasMicrophonePermission() -> {
                            conversation.refreshMicrophonePermission(true)
                            conversation.retryListening()
                            syncListening()
                        }
                        ActivityCompat.shouldShowRequestPermissionRationale(
                            this, Manifest.permission.RECORD_AUDIO,
                        ) -> showRationale = true
                        else -> launchPermissionRequest()
                    }
                }

                LaunchedEffect(
                    model.model, model.status, keyStatus.revision, groqKey.hasKey, keySetup.isOpen,
                    config.stt, config.tts, config.voice,
                    screenReady, foreground, windowFocused, dialogOpen, showRationale,
                    permissionRequestInFlight, state.isPaused,
                ) {
                    syncListening()
                    if (screenReady && foreground && windowFocused && !dialogOpen && !keySetup.isOpen &&
                        !showRationale && !permissionRequestInFlight && !state.isPaused &&
                        ai.pipelineReady && !hasMicrophonePermission()
                    ) {
                        if (!permissions.getBoolean("microphone_requested", false)) {
                            requestMicrophone()
                        } else {
                            showPermissionProblem()
                        }
                    }
                }

                LaunchedEffect(config.stt, config.tts, config.voice) {
                    syncListening()
                    conversation.speechConfigurationChanged(config.stt.id, "${config.tts.id}:${config.voice.id}")
                }

                ConversationScreen(
                    state = state,
                    onRetry = {
                        if (hasMicrophonePermission()) {
                            conversation.retryListening()
                            syncListening()
                        } else requestMicrophone()
                    },
                    onPauseResume = {
                        if (state.isPaused) {
                            conversation.resumeListening()
                            syncListening()
                        } else conversation.pauseListening()
                    },
                    onDialogVisibilityChanged = {
                        screenReady = true
                        dialogOpen = it
                        syncListening()
                    },
                    model = model,
                    nanoAvailability = nanoAvailability,
                    onCheckNano = ai::refreshNanoAvailability,
                    onSelectModel = {
                        conversation.updateAvailability(voice, ai, false)
                        ai.selectModel(it)
                    },
                    keySetup = keySetup,
                    keyStatus = keyStatus,
                    config = config,
                    groqKeyStatus = groqKey,
                    quotas = quotas,
                    androidInputAvailable = voice.supportsAndroidInput,
                    onSelectStt = {
                        conversation.updateAvailability(voice, ai, false)
                        ai.selectStt(it)
                    },
                    onSelectTts = {
                        conversation.updateAvailability(voice, ai, false)
                        ai.selectTts(it)
                    },
                    onSelectVoice = {
                        conversation.updateAvailability(voice, ai, false)
                        ai.selectVoice(it)
                    },
                    onGroqKey = {
                        conversation.updateAvailability(voice, ai, false)
                        ai.openKeySetup(CloudProvider.Groq)
                    },
                    onRemoveGroqKey = {
                        conversation.updateAvailability(voice, ai, false)
                        ai.removeApiKey(CloudProvider.Groq)
                    },
                    onUseDevice = {
                        conversation.updateAvailability(voice, ai, false)
                        when (it) {
                            PipelineStage.Stt -> ai.selectStt(SttModel.Android)
                            PipelineStage.Tts -> ai.selectTts(TtsModel.Android)
                            PipelineStage.Chat -> ai.selectModel(com.superbot.sati.ai.AiModel.Qwen)
                        }
                    },
                    onEditKey = {
                        conversation.updateAvailability(voice, ai, false)
                        ai.openKeySetup(model.model.provider ?: CloudProvider.DeepSeek)
                    },
                    onKeyDraftChange = ai::editApiKey,
                    onConnectKey = ai::connectApiKey,
                    onDismissKey = ai::dismissKeySetup,
                    onRemoveKey = {
                        conversation.updateAvailability(voice, ai, false)
                        ai.removeApiKey(model.model.provider ?: CloudProvider.DeepSeek)
                    },
                    onCancelDownload = ai::cancelDownload,
                    onRetryModel = ai::retrySetup,
                    onNewConversation = { conversation.newConversation(ai) },
                    onOpenSettings = {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            })
                        } catch (_: ActivityNotFoundException) {
                            conversation.showProblem(SpeechProblem.PermissionDenied)
                        }
                    },
                )

                if (showRationale) {
                    AlertDialog(
                        onDismissRequest = { showRationale = false },
                        title = { Text(stringResource(R.string.microphone_title)) },
                        text = { Text(stringResource(R.string.microphone_rationale)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showRationale = false
                                launchPermissionRequest()
                            }) { Text(stringResource(R.string.continue_label)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRationale = false }) {
                                Text(stringResource(R.string.not_now))
                            }
                        },
                    )
                }
            }
        }
    }

    private fun syncListening() {
        if (!::conversation.isInitialized || !::ai.isInitialized || !::voice.isInitialized) return
        val revision = when (ai.state.value.model.provider) {
            CloudProvider.DeepSeek -> ai.keyStatus.value.revision
            CloudProvider.Groq -> ai.groqKeyStatus.value.revision
            null -> 0
        }
        conversation.bindBackend("${ai.state.value.model.id}:$revision", ai)
        conversation.updateAvailability(
            voice, ai,
            screenReady && foreground && windowFocused && !dialogOpen && !ai.keySetup.value.isOpen &&
                hasMicrophonePermission() && ai.pipelineReady,
        )
    }

    private fun showPermissionProblem() {
        conversation.showProblem(
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this, Manifest.permission.RECORD_AUDIO,
                )
            ) SpeechProblem.PermissionDenied else SpeechProblem.PermissionSettings,
        )
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        conversation.refreshMicrophonePermission(hasMicrophonePermission())
        syncListening()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        if (hasFocus && ::conversation.isInitialized) {
            conversation.refreshMicrophonePermission(hasMicrophonePermission())
        }
        syncListening()
    }

    override fun onPause() {
        foreground = false
        syncListening()
        super.onPause()
    }

    override fun onStop() {
        conversation.updateAvailability(voice, ai, false)
        voice.cancel()
        if (isChangingConfigurations) ai.pauseKeyValidation() else ai.dismissKeySetup()
        super.onStop()
    }

    override fun onDestroy() {
        conversation.updateAvailability(voice, ai, false)
        voice.close()
        super.onDestroy()
    }

    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
}

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.superbot.sati.conversation.ConversationViewModel
import com.superbot.sati.conversation.SpeechProblem
import com.superbot.sati.speech.AndroidVoiceSession
import com.superbot.sati.ui.ConversationScreen
import com.superbot.sati.ui.theme.SatiTheme

class MainActivity : ComponentActivity() {
    private lateinit var conversation: ConversationViewModel
    private lateinit var voice: AndroidVoiceSession
    private var foreground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conversation = ViewModelProvider(this)[ConversationViewModel::class.java]
        voice = AndroidVoiceSession(this)
        enableEdgeToEdge()
        setContent {
            SatiTheme {
                val state by conversation.state.collectAsStateWithLifecycle()
                var showRationale by rememberSaveable { mutableStateOf(false) }
                val microphonePermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        conversation.refreshMicrophonePermission(true)
                        if (foreground) conversation.startConversation(voice)
                    } else {
                        conversation.showProblem(
                            if (ActivityCompat.shouldShowRequestPermissionRationale(
                                    this, Manifest.permission.RECORD_AUDIO,
                                )
                            ) SpeechProblem.PermissionDenied else SpeechProblem.PermissionSettings,
                        )
                    }
                }

                ConversationScreen(
                    state = state,
                    onTalk = {
                        when {
                            hasMicrophonePermission() -> conversation.startConversation(voice)
                            ActivityCompat.shouldShowRequestPermissionRationale(
                                this, Manifest.permission.RECORD_AUDIO,
                            ) -> showRationale = true
                            else -> microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onCancel = conversation::cancelConversation,
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
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
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

    override fun onStart() {
        super.onStart()
        foreground = true
        conversation.refreshMicrophonePermission(hasMicrophonePermission())
    }

    override fun onStop() {
        foreground = false
        conversation.cancelConversation()
        voice.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        voice.close()
        super.onDestroy()
    }

    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
}

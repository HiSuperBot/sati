package com.superbot.sati.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.superbot.sati.conversation.ListeningCallbacks
import com.superbot.sati.conversation.SpeechProblem
import com.superbot.sati.conversation.VoiceSession
import java.util.Locale

/** Main-thread owner of speech engines. Each turn has its own callback generation. */
class AndroidVoiceSession(context: Context) : VoiceSession {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingInitialization: (() -> Unit)? = null
    private var initializationError: ((SpeechProblem) -> Unit)? = null
    private var generation = 0L
    private var initializationGeneration = 0L
    private var closed = false
    private var timeout: Runnable? = null
    private var initializationTimeout: Runnable? = null

    override fun listen(callbacks: ListeningCallbacks) {
        cancel()
        val turn = generation
        if (closed) return callbacks.onError(SpeechProblem.SpeechUnavailable)
        if (!hasMicrophonePermission()) {
            return callbacks.onError(SpeechProblem.PermissionDenied)
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return callbacks.onError(SpeechProblem.RecognitionUnavailable)
        }
        ensureSpeechReady(
            onReady = { if (turn == generation && !closed) beginRecognition(turn, callbacks) },
            onError = { if (turn == generation && !closed) callbacks.onError(it) },
        )
    }

    private fun beginRecognition(turn: Long, callbacks: ListeningCallbacks) {
        if (!hasMicrophonePermission()) {
            callbacks.onError(SpeechProblem.PermissionDenied)
            return
        }
        try {
            val engine = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = engine
            engine.setRecognitionListener(object : RecognitionListener {
                private fun isCurrent() = !closed && turn == generation && recognizer === engine

                override fun onReadyForSpeech(params: Bundle?) {
                    if (isCurrent()) callbacks.onReady()
                }

                override fun onEndOfSpeech() {
                    if (isCurrent()) callbacks.onEnd()
                }

                override fun onResults(results: Bundle?) {
                    if (!isCurrent()) return
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    clearTimeout()
                    releaseRecognizer()
                    callbacks.onResult(text)
                }

                override fun onError(error: Int) {
                    if (!isCurrent()) return
                    clearTimeout()
                    releaseRecognizer()
                    callbacks.onError(when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechProblem.NoSpeech
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechProblem.Network
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechProblem.PermissionDenied
                        else -> SpeechProblem.RecognitionFailed
                    })
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            setTimeout(20_000L) {
                if (turn == generation) {
                    releaseRecognizer()
                    callbacks.onError(SpeechProblem.NoSpeech)
                }
            }
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            })
        } catch (_: SecurityException) {
            clearTimeout()
            releaseRecognizer()
            callbacks.onError(SpeechProblem.PermissionDenied)
        } catch (_: RuntimeException) {
            clearTimeout()
            releaseRecognizer()
            callbacks.onError(SpeechProblem.RecognitionFailed)
        }
    }

    private fun ensureSpeechReady(onReady: () -> Unit, onError: (SpeechProblem) -> Unit) {
        if (ttsReady) return onReady()
        pendingInitialization = onReady
        initializationError = onError
        if (tts != null) return
        val initialization = ++initializationGeneration
        val deadline = Runnable {
            if (initialization == initializationGeneration) {
                finishInitialization(SpeechProblem.SpeechUnavailable)
            }
        }
        initializationTimeout = deadline
        handler.postDelayed(deadline, 10_000L)
        try {
            tts = TextToSpeech(context) { status ->
                // Post even if already on main: the constructor must first assign tts.
                handler.post {
                    if (closed || initialization != initializationGeneration) return@post
                    val problem = try {
                        when {
                            status != TextToSpeech.SUCCESS -> SpeechProblem.SpeechUnavailable
                            tts?.setLanguage(Locale.US) in setOf(
                                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED,
                            ) -> SpeechProblem.EnglishUnavailable
                            else -> null
                        }
                    } catch (_: RuntimeException) {
                        SpeechProblem.SpeechUnavailable
                    }
                    finishInitialization(problem)
                }
            }
        } catch (_: RuntimeException) {
            finishInitialization(SpeechProblem.SpeechUnavailable)
        }
    }

    private fun finishInitialization(problem: SpeechProblem?) {
        initializationGeneration++
        initializationTimeout?.let(handler::removeCallbacks)
        initializationTimeout = null
        val ready = pendingInitialization
        val failed = initializationError
        pendingInitialization = null
        initializationError = null
        ttsReady = problem == null
        if (problem == null) {
            tts?.setSpeechRate(0.85f)
            ready?.invoke()
        } else {
            releaseTts()
            failed?.invoke(problem)
        }
    }

    override fun speak(text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit) {
        val engine = tts
        val turn = generation
        if (!ttsReady || engine == null || closed) {
            onError(SpeechProblem.SpeechUnavailable)
            return
        }
        // The recognizer is destroyed before any reply can reach the speaker.
        releaseRecognizer()
        val utteranceId = "sati-$turn"
        fun complete(problem: SpeechProblem?) {
            handler.post {
                if (closed || turn != generation) return@post
                clearTimeout()
                // Invalidate duplicate onDone/onError callbacks for this utterance.
                generation++
                if (problem == null) onDone() else onError(problem)
            }
        }
        try {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) {
                    if (id == utteranceId) complete(null)
                }
                @Deprecated("Required by UtteranceProgressListener")
                override fun onError(id: String?) {
                    if (id == utteranceId) complete(SpeechProblem.PlaybackFailed)
                }
                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) complete(SpeechProblem.PlaybackFailed)
                }
            })
            setTimeout(30_000L) { complete(SpeechProblem.PlaybackFailed) }
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.ERROR) {
                complete(SpeechProblem.PlaybackFailed)
            }
        } catch (_: RuntimeException) {
            complete(SpeechProblem.PlaybackFailed)
        }
    }

    override fun cancel() {
        generation++
        clearTimeout()
        pendingInitialization = null
        initializationError = null
        releaseRecognizer()
        runCatching { tts?.stop() }
    }

    fun close() {
        closed = true
        cancel()
        initializationGeneration++
        initializationTimeout?.let(handler::removeCallbacks)
        initializationTimeout = null
        releaseTts()
    }

    private fun hasMicrophonePermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun releaseRecognizer() {
        val engine = recognizer
        recognizer = null
        runCatching { engine?.cancel() }
        runCatching { engine?.destroy() }
    }

    private fun releaseTts() {
        val engine = tts
        tts = null
        ttsReady = false
        runCatching { engine?.shutdown() }
    }

    private fun setTimeout(delay: Long, block: () -> Unit) {
        clearTimeout()
        timeout = Runnable(block).also { handler.postDelayed(it, delay) }
    }

    private fun clearTimeout() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }
}

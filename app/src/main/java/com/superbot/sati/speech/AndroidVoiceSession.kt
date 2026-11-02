package com.superbot.sati.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.ContextCompat
import com.superbot.sati.conversation.ListeningCallbacks
import com.superbot.sati.conversation.SpeechProblem
import com.superbot.sati.conversation.VoiceSession
import java.util.Locale

/** Uses only Android's on-device recognizer and installed voices that require no network. */
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
        if (!hasMicrophonePermission()) return callbacks.onError(SpeechProblem.PermissionDenied)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            Log.w("SatiSpeech", "No on-device recognition service; API=${Build.VERSION.SDK_INT}")
            return callbacks.onError(SpeechProblem.RecognitionUnavailable)
        }
        ensureSpeechReady(
            onReady = { if (turn == generation && !closed) beginRecognition(turn, callbacks) },
            onError = { if (turn == generation && !closed) callbacks.onError(it) },
        )
    }

    private fun beginRecognition(turn: Long, callbacks: ListeningCallbacks) {
        if (!hasMicrophonePermission()) return callbacks.onError(SpeechProblem.PermissionDenied)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return callbacks.onError(SpeechProblem.RecognitionUnavailable)
        }
        try {
            // EXTRA_PREFER_OFFLINE alone is only a hint. This factory enforces the local service.
            val engine = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = engine
            fun isCurrent() = !closed && turn == generation && recognizer === engine
            fun fail(problem: SpeechProblem) {
                if (!isCurrent()) return
                clearTimeout()
                releaseRecognizer()
                callbacks.onError(problem)
            }
            val intent = recognitionIntent()
            fun startListening() {
                if (!isCurrent()) return
                if (!hasMicrophonePermission()) return fail(SpeechProblem.PermissionDenied)
                try {
                    setTimeout(20_000L) { fail(SpeechProblem.NoSpeech) }
                    engine.startListening(intent)
                } catch (_: SecurityException) {
                    fail(SpeechProblem.PermissionDenied)
                } catch (_: RuntimeException) {
                    fail(SpeechProblem.RecognitionFailed)
                }
            }
            var downloadRequested = false
            fun downloadModel() {
                if (!isCurrent() || downloadRequested) return
                downloadRequested = true
                callbacks.onPreparing()
                setTimeout(180_000L) { fail(SpeechProblem.SpeechDownloadPending) }
                try {
                    if (Build.VERSION.SDK_INT >= 34) {
                        engine.triggerModelDownload(intent, ContextCompat.getMainExecutor(context),
                            object : ModelDownloadListener {
                                override fun onProgress(completedPercent: Int) {
                                    if (isCurrent()) callbacks.onPreparing()
                                }
                                override fun onSuccess() {
                                    if (!isCurrent()) return
                                    Log.i("SatiSpeech", "English on-device speech model ready")
                                    startListening()
                                }
                                override fun onScheduled() {
                                    fail(SpeechProblem.SpeechDownloadPending)
                                }
                                override fun onError(error: Int) {
                                    Log.w("SatiSpeech", "Speech model download failed: code=$error")
                                    fail(SpeechProblem.SpeechDownloadFailed)
                                }
                            },
                        )
                    } else if (Build.VERSION.SDK_INT >= 33) {
                        engine.triggerModelDownload(intent)
                        fail(SpeechProblem.SpeechDownloadPending)
                    } else {
                        fail(SpeechProblem.RecognitionUnavailable)
                    }
                } catch (_: SecurityException) {
                    fail(SpeechProblem.PermissionDenied)
                } catch (_: RuntimeException) {
                    fail(SpeechProblem.SpeechDownloadFailed)
                }
            }
            engine.setRecognitionListener(object : RecognitionListener {
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
                    Log.w("SatiSpeech", "On-device recognition failed: code=$error")
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE && !downloadRequested) {
                        downloadModel()
                        return
                    }
                    fail(when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechProblem.NoSpeech
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechProblem.RecognitionUnavailable
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
            if (Build.VERSION.SDK_INT >= 33) {
                setTimeout(10_000L) { fail(SpeechProblem.RecognitionFailed) }
                engine.checkRecognitionSupport(intent, ContextCompat.getMainExecutor(context),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            if (!isCurrent()) return
                            val installed = support.installedOnDeviceLanguages.any {
                                it.equals("en-US", ignoreCase = true)
                            }
                            Log.i("SatiSpeech", "English on-device speech installed=$installed")
                            if (installed) startListening() else downloadModel()
                        }
                        override fun onError(error: Int) {
                            if (!isCurrent()) return
                            // Older services may implement recognition but not support queries.
                            Log.w("SatiSpeech", "Speech support query failed: code=$error")
                            startListening()
                        }
                    },
                )
            } else {
                startListening()
            }
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

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
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
                handler.post {
                    if (closed || initialization != initializationGeneration) return@post
                    val problem = try {
                        val engine = tts
                        val voice = engine?.voices?.let { OfflineSpeech.voice(it, Locale.US) }
                        when {
                            status != TextToSpeech.SUCCESS || engine == null ->
                                SpeechProblem.SpeechUnavailable
                            voice == null -> SpeechProblem.EnglishUnavailable
                            engine.setVoice(voice) == TextToSpeech.ERROR -> SpeechProblem.EnglishUnavailable
                            engine.setSpeechRate(0.85f) == TextToSpeech.ERROR -> SpeechProblem.SpeechUnavailable
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
        if (problem == null) ready?.invoke()
        else {
            releaseTts()
            failed?.invoke(problem)
        }
    }

    override fun speak(text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit) {
        val turn = generation
        if (closed) return onError(SpeechProblem.SpeechUnavailable)
        releaseRecognizer()
        ensureSpeechReady(
            onReady = {
                if (turn == generation && !closed) playReply(turn, text, onDone, onError)
            },
            onError = { if (turn == generation && !closed) onError(it) },
        )
    }

    private fun playReply(
        turn: Long,
        text: String,
        onDone: () -> Unit,
        onError: (SpeechProblem) -> Unit,
    ) {
        val engine = tts ?: return onError(SpeechProblem.SpeechUnavailable)
        val prefix = "sati-$turn-"
        fun complete(problem: SpeechProblem?) {
            handler.post {
                if (closed || turn != generation) return@post
                clearTimeout()
                generation++
                if (problem == null) onDone() else onError(problem)
            }
        }
        try {
            val segments = OfflineSpeech.segments(text)
            if (segments.isEmpty()) return onError(SpeechProblem.PlaybackFailed)
            val voices = engine.voices.orEmpty()
            // Validate every segment before enqueueing any audio; never fall back to a network voice.
            val utterances = segments.map { (locale, content) ->
                val voice = OfflineSpeech.voice(voices, locale)
                    ?: return onError(
                        if (locale.language == "ko") SpeechProblem.KoreanUnavailable
                        else SpeechProblem.EnglishUnavailable,
                    )
                voice to content
            }
            val lastId = prefix + utterances.lastIndex
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) {
                    if (id == lastId) complete(null)
                }
                @Deprecated("Required by UtteranceProgressListener")
                override fun onError(id: String?) {
                    if (id?.startsWith(prefix) == true) complete(SpeechProblem.PlaybackFailed)
                }
                override fun onError(id: String?, errorCode: Int) {
                    if (id?.startsWith(prefix) == true) complete(SpeechProblem.PlaybackFailed)
                }
            })
            setTimeout(45_000L) { complete(SpeechProblem.PlaybackFailed) }
            utterances.forEachIndexed { index, (voice, content) ->
                if (engine.setVoice(voice) == TextToSpeech.ERROR ||
                    engine.speak(
                        content,
                        if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                        null,
                        prefix + index,
                    ) == TextToSpeech.ERROR
                ) {
                    complete(SpeechProblem.PlaybackFailed)
                    return
                }
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

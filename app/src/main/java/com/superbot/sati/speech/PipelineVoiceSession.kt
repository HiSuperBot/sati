package com.superbot.sati.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import com.superbot.sati.ai.*
import com.superbot.sati.conversation.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface SpeechInput {
    fun listen(callbacks: ListeningCallbacks, retry: Boolean = false)
    fun cancel()
    fun close()
}

internal interface SpeechOutput {
    fun speak(text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit,
        onStageError: (StageFailure) -> Unit)
    fun cancel()
    fun close()
}

/** Each input/output has its own lifecycle; choosing cloud speech never requires Android TTS data. */
internal class PipelineVoiceSession(
    context: Context,
    groq: GroqClient,
    private val config: () -> PipelineConfig,
    key: () -> String?,
) : VoiceSession {
    private val androidInput = AndroidInput(context)
    private val androidOutput = AndroidOutput(context)
    private val cloudInput = GroqInput(context.applicationContext, groq, { config().stt }, key)
    private val cloudOutput = GroqOutput(context.applicationContext, groq, { config().voice }, key)
    val supportsAndroidInput get() = androidInput.available

    override fun listen(callbacks: ListeningCallbacks) = input().listen(callbacks)
    override fun retryListen(callbacks: ListeningCallbacks) = input().listen(callbacks, retry = true)
    private fun input(): SpeechInput = if (config().stt.isCloud) cloudInput else androidInput
    override fun speak(text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit) =
        speakWithFailure(text, onDone, onError) { onError(SpeechProblem.PlaybackFailed) }
    override fun speakWithFailure(text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit,
        onStageError: (StageFailure) -> Unit) =
        (if (config().tts.isCloud) cloudOutput else androidOutput).speak(text, onDone, onError, onStageError)
    override fun remainingSpeech(original: String) =
        if (config().tts.isCloud) cloudOutput.remainingText(original) else original
    override fun cancel() {
        androidInput.cancel(); androidOutput.cancel(); cloudInput.cancel(); cloudOutput.cancel()
    }
    fun close() {
        androidInput.close(); androidOutput.close(); cloudInput.close(); cloudOutput.close()
    }
}

private class AndroidInput(private val context: Context) : SpeechInput {
    private val session = AndroidVoiceSession(context)
    val available get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    override fun listen(callbacks: ListeningCallbacks, retry: Boolean) = session.listen(callbacks)
    override fun cancel() = session.cancel()
    override fun close() = session.close()
}

private class AndroidOutput(context: Context) : SpeechOutput {
    private val session = AndroidVoiceSession(context)
    override fun speak(text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit,
        onStageError: (StageFailure) -> Unit) = session.speak(text, onDone, onError)
    override fun cancel() = session.cancel()
    override fun close() = session.close()
}

private class GroqInput(
    private val context: Context, private val client: GroqClient,
    private val model: () -> SttModel, private val key: () -> String?,
) : SpeechInput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var generation = 0L
    @Volatile private var recorder: AudioRecord? = null
    private var pendingAudio: ByteArray? = null

    override fun listen(callbacks: ListeningCallbacks, retry: Boolean) {
        val retained = if (retry) pendingAudio else null
        cancel()
        pendingAudio = retained
        val turn = generation
        val selected = model()
        job = scope.launch {
            try {
                val credential = key() ?: throw StageException(StageFailure(
                    PipelineStage.Stt, CloudProvider.Groq, selected.id, ReplyFailure.Authentication,
                ))
                val audio = pendingAudio ?: capture(callbacks)
                ensureActive()
                if (turn != generation) return@launch
                if (audio == null) {
                    callbacks.onError(SpeechProblem.NoSpeech)
                    return@launch
                }
                pendingAudio = audio
                callbacks.onEnd()
                val text = client.transcribe(credential, selected, audio)
                ensureActive()
                if (turn == generation) {
                    pendingAudio = null
                    callbacks.onResult(text)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: StageException) {
                if (turn == generation) callbacks.onStageError(e.failure)
            } catch (_: SecurityException) {
                if (turn == generation) callbacks.onError(SpeechProblem.PermissionDenied)
            } catch (_: LinkageError) {
                if (turn == generation) callbacks.onError(SpeechProblem.RecognitionUnavailable)
            } catch (_: Exception) {
                if (turn == generation) callbacks.onError(SpeechProblem.RecognitionFailed)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun capture(callbacks: ListeningCallbacks): ByteArray? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) throw SecurityException()
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val engine = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 2, 6_400),
        )
        recorder = engine
        try {
            check(engine.state == AudioRecord.STATE_INITIALIZED)
            VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320, Mode.NORMAL).use { vad ->
                val utterance = UtteranceBuffer()
                engine.startRecording()
                check(engine.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                withContext(Dispatchers.Main.immediate) { callbacks.onReady() }
                while (true) {
                    ensureActive()
                    val frame = ShortArray(320)
                    var read = 0
                    while (read < frame.size) {
                        ensureActive()
                        val count = engine.read(frame, read, frame.size - read, AudioRecord.READ_BLOCKING)
                        if (count <= 0) throw IOException("Recording stopped")
                        read += count
                    }
                    val wav = utterance.add(frame, vad.isSpeech(frame))
                    if (wav != null) return@withContext wav
                    if (utterance.timedOut) return@withContext null
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        } finally {
            runCatching { engine.stop() }
            engine.release()
            if (recorder === engine) recorder = null
        }
    }

    override fun cancel() {
        generation++
        job?.cancel()
        job = null
        pendingAudio = null
        runCatching { recorder?.stop() }
    }
    override fun close() { cancel(); scope.cancel() }
}

private class GroqOutput(
    private val context: Context, private val client: GroqClient,
    private val voice: () -> OrpheusVoice, private val key: () -> String?,
) : SpeechOutput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var generation = 0L
    private var text: String? = null
    private var chunks = emptyList<String>()
    private var nextChunk = 0
    private var player: MediaPlayer? = null

    fun remainingText(original: String) =
        if (text != null) chunks.drop(nextChunk).joinToString(" ") else original

    override fun speak(text: String, onDone: () -> Unit, onError: (SpeechProblem) -> Unit,
        onStageError: (StageFailure) -> Unit) {
        // Failed chunks remain pending; successful playback advances the index exactly once.
        if (this.text != text) {
            this.text = text
            chunks = splitSpeech(text)
            nextChunk = 0
        }
        job?.cancel()
        val turn = ++generation
        val selectedVoice = voice()
        job = scope.launch {
            try {
                val credential = key() ?: throw StageException(StageFailure(
                    PipelineStage.Tts, CloudProvider.Groq, TtsModel.Orpheus.id, ReplyFailure.Authentication,
                ))
                while (nextChunk < chunks.size) {
                    val bytes = client.synthesize(credential, chunks[nextChunk], selectedVoice)
                    ensureActive()
                    play(bytes)
                    ensureActive()
                    if (turn != generation) return@launch
                    nextChunk++
                }
                this@GroqOutput.text = null
                chunks = emptyList()
                nextChunk = 0
                if (turn == generation) onDone()
            } catch (e: CancellationException) { throw e }
            catch (e: StageException) { if (turn == generation) onStageError(e.failure) }
            catch (_: Exception) { if (turn == generation) onError(SpeechProblem.PlaybackFailed) }
        }
    }

    private suspend fun play(bytes: ByteArray) {
        var file: File? = null
        var playback: MediaPlayer? = null
        try {
            val audioFile = withContext(Dispatchers.IO) {
                File.createTempFile("sati-speech-", ".wav", context.cacheDir).also {
                    file = it
                    it.writeBytes(finalizeWav(bytes))
                }
            }
            withTimeout(45_000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val engine = MediaPlayer()
                    playback = engine
                    player = engine
                    continuation.invokeOnCancellation { runCatching { engine.release() } }
                    try {
                        engine.setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        engine.setOnCompletionListener {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        engine.setOnErrorListener { _, _, _ ->
                            if (continuation.isActive) continuation.resumeWithException(IOException("Playback failed"))
                            true
                        }
                        engine.setOnPreparedListener { if (continuation.isActive) it.start() }
                        engine.setDataSource(audioFile.absolutePath)
                        engine.prepareAsync()
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("Playback failed"))
                    }
                }
            }
        } finally {
            val engine = playback
            runCatching { engine?.release() }
            if (player === engine) player = null
            file?.delete()
        }
    }

    override fun cancel() {
        generation++
        job?.cancel()
        job = null
        runCatching { player?.release() }
        player = null
        text = null
        chunks = emptyList()
        nextChunk = 0
    }
    override fun close() { cancel(); scope.cancel() }
}

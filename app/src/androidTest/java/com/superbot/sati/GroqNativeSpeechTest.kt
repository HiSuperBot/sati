package com.superbot.sati

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.superbot.sati.speech.finalizeWav
import com.superbot.sati.speech.pcmWav
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import com.superbot.sati.speech.UtteranceBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroqNativeSpeechTest {
    @Test
    fun finalizedStreamingWavPreparesAndPlaysOnTheDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = pcmWav(listOf(ShortArray(4_800) {
            (kotlin.math.sin(it * 2.0 * Math.PI * 440 / 24_000) * 2_000).toInt().toShort()
        }))
        ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4, -1).putInt(24, 24_000).putInt(28, 48_000).putInt(40, -1)
        val file = File.createTempFile("wav-playback-test-", ".wav", context.cacheDir)
        val player = MediaPlayer()
        val complete = CountDownLatch(1)
        val failed = AtomicBoolean()
        try {
            file.writeBytes(finalizeWav(original))
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setOnCompletionListener { complete.countDown() }
            player.setOnErrorListener { _, _, _ ->
                failed.set(true)
                complete.countDown()
                true
            }
            player.setDataSource(file.absolutePath)
            player.prepare()
            assertTrue(player.duration > 0)
            player.setVolume(0f, 0f)
            player.start()
            assertTrue("Playback did not complete", complete.await(5, TimeUnit.SECONDS))
            assertFalse("Android rejected finalized WAV", failed.get())
        } finally {
            player.release()
            file.delete()
        }
    }

    @Test
    fun bundledWebRtcVadLoadsOnTheDeviceAndSilenceNeverCreatesAnUpload() {
        VadWebRTC(SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_320, Mode.NORMAL).use { vad ->
            val buffer = UtteranceBuffer()
            repeat(1_000) {
                val frame = ShortArray(320)
                assertFalse(vad.isSpeech(frame))
                assertNull(buffer.add(frame, false))
            }
            assertTrue(buffer.timedOut)
        }
    }
}

package com.superbot.sati.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class SpeechBuffersTest {
    @Test
    fun silenceDoesNotCreateAnyUploadAndTimesOutLocally() {
        val capture = UtteranceBuffer()
        repeat(1_000) { assertNull(capture.add(ShortArray(320), false)) }
        assertTrue(capture.timedOut)
    }

    @Test
    fun shortNoiseBurstsAreDiscarded() {
        val capture = UtteranceBuffer()
        repeat(4) { assertNull(capture.add(ShortArray(320) { 100 }, true)) }
        repeat(60) { assertNull(capture.add(ShortArray(320), false)) }
    }

    @Test
    fun realSpeechIncludesPrerollAndEndsAfterOnePointTwoSecondsOfSilence() {
        val capture = UtteranceBuffer()
        repeat(15) { assertNull(capture.add(ShortArray(320), false)) }
        repeat(10) { assertNull(capture.add(ShortArray(320) { 200 }, true)) }
        repeat(59) { assertNull(capture.add(ShortArray(320), false)) }
        val wave = capture.add(ShortArray(320), false)!!
        val header = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", wave.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", wave.copyOfRange(8, 12).decodeToString())
        assertEquals(16_000, header.getInt(24))
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(wave.size - 44, header.getInt(40))
        assertEquals(200, header.getShort(44 + 14 * 640).toInt())
    }

    @Test
    fun aLongUtteranceIsBoundedToThirtySeconds() {
        val capture = UtteranceBuffer()
        repeat(1_499) { assertNull(capture.add(ShortArray(320) { 100 }, true)) }
        assertEquals(44 + 1_500 * 640, capture.add(ShortArray(320) { 100 }, true)!!.size)
    }

    @Test
    fun speechChunksPreserveEveryWordAndRespectTheProviderLimit() {
        val text = ("Hello friend. Let us talk about your favorite animal today! ").repeat(12).trim()
        val chunks = splitSpeech(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.codePointCount(0, it.length) <= 200 && it.isNotBlank() })
        assertEquals(text, chunks.joinToString(" "))
        assertEquals(emptyList<String>(), splitSpeech(" "))
    }

    @Test
    fun aLongWordAndSupplementaryCharactersNeverBreakTheLimitOrSurrogatePairs() {
        val text = "x".repeat(250) + " " + "😀".repeat(220)
        val chunks = splitSpeech(text)
        assertTrue(chunks.all { it.codePointCount(0, it.length) <= 200 })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        assertTrue(chunks.none { it.isNotEmpty() && Character.isHighSurrogate(it.last()) })
    }
}

package com.superbot.sati.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class WavAudioTest {
    private fun streamWave(riffLength: Int = -1, dataLength: Int = -1) =
        pcmWav(listOf(ShortArray(320) { (it - 160).toShort() })).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(4, riffLength).putInt(40, dataLength)
        }

    @Test
    fun unknownStreamingLengthsBecomeActualLengthsWithoutChangingSamplesOrTheInput() {
        val source = streamWave()
        val samples = source.copyOfRange(44, source.size)
        val result = finalizeWav(source)
        val sizes = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(result.size - 8, sizes.getInt(4))
        assertEquals(result.size - 44, sizes.getInt(40))
        assertEquals(-1, ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        assertArrayEquals(samples, result.copyOfRange(44, result.size))
        assertArrayEquals(source.copyOfRange(8, 40), result.copyOfRange(8, 40))
    }

    @Test
    fun finiteWavIsUnchangedAndRepeatedFinalizationIsSafe() {
        val source = pcmWav(listOf(ShortArray(320)))
        assertArrayEquals(source, finalizeWav(source))
        val finalized = finalizeWav(streamWave())
        assertArrayEquals(finalized, finalizeWav(finalized))
    }

    @Test
    fun fixesTheRiffLengthWhenOnlyTheContainerLengthIsUnknown() {
        val source = streamWave(dataLength = 640)
        val result = finalizeWav(source)
        assertEquals(result.size - 8, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        assertEquals(640, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }

    @Test
    fun zeroLengthStreamingPlaceholdersAreFinalized() {
        val source = streamWave(riffLength = 0, dataLength = 0)
        val result = finalizeWav(source)
        assertEquals(640, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }

    @Test
    fun findsTheDataChunkAfterOddSizedPaddedMetadata() {
        val source = streamWave()
        val result = ByteBuffer.allocate(source.size + 10).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(source, 0, 36)
            put("JUNK".toByteArray()); putInt(1); put(7); put(0)
            put(source, 36, source.size - 36)
        }.array()
        val fixed = finalizeWav(result)
        assertEquals(640, ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN).getInt(50))
        assertArrayEquals(source.copyOfRange(44, source.size), fixed.copyOfRange(54, fixed.size))
    }

    @Test
    fun doesNotTreatTruncatedOrMalformedAudioAsAStreamingPlaceholder() {
        val valid = pcmWav(listOf(ShortArray(320)))
        val bad = listOf(
            ByteArray(44),
            valid.copyOf(valid.size - 20),
            streamWave(dataLength = 641),
            streamWave().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, -1) },
            streamWave().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(32, 0) },
            streamWave().copyOf(44),
        )
        bad.forEach {
            try { finalizeWav(it); fail("Malformed WAV must fail") }
            catch (_: IllegalArgumentException) { }
        }
    }
}

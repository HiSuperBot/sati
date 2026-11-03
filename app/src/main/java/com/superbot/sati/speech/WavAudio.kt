package com.superbot.sati.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming WAV writers can leave RIFF/data lengths at UINT32_MAX. Android's
 * extractor rejects that header even after the complete HTTP body has arrived.
 * Finalize only the container lengths; never alter audio samples or the format.
 */
internal fun finalizeWav(audio: ByteArray): ByteArray {
    require(audio.size in 44..8 * 1024 * 1024)
    val wav = audio.copyOf()
    val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    fun sizeAt(offset: Int) = header.getInt(offset).toLong() and 0xffff_ffffL
    fun isChunk(offset: Int, name: String) =
        name.indices.all { wav[offset + it] == name[it].code.toByte() }
    require(isChunk(0, "RIFF") && isChunk(8, "WAVE"))
    val declaredRiff = sizeAt(4)
    require(declaredRiff == 0L || declaredRiff == 0xffff_ffffL || declaredRiff <= wav.size - 8L)

    var offset = 12
    var alignment = 0
    var hasAudio = false
    while (offset < wav.size) {
        require(wav.size - offset >= 8)
        val payload = offset + 8
        val available = wav.size - payload
        val declared = sizeAt(offset + 4)
        val data = isChunk(offset, "data")
        val streaming = data && (declared == 0xffff_ffffL || declared == 0L)
        val length = if (streaming) available.toLong() else declared
        require(length <= available.toLong())
        if (isChunk(offset, "fmt ")) {
            require(length >= 16)
            alignment = header.getShort(payload + 12).toInt() and 0xffff
            require(alignment in 1..64)
        } else if (data) {
            require(!hasAudio && alignment > 0 && length > 0 && length % alignment == 0L)
            hasAudio = true
            if (streaming) header.putInt(offset + 4, length.toInt())
        }
        val next = payload.toLong() + length + (length and 1L)
        require(next <= wav.size.toLong())
        offset = next.toInt()
    }
    require(hasAudio)
    header.putInt(4, wav.size - 8)
    return wav
}

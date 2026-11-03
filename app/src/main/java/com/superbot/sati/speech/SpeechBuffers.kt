package com.superbot.sati.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/** 20 ms / 320-sample frames; retain a short pre-roll and require real speech before upload. */
internal class UtteranceBuffer {
    private val preRoll = ArrayDeque<ShortArray>()
    private val frames = mutableListOf<ShortArray>()
    private var spokenFrames = 0
    private var silentFrames = 0
    private var elapsedFrames = 0
    val timedOut get() = elapsedFrames >= 1_000 && frames.isEmpty()

    fun add(frame: ShortArray, speech: Boolean): ByteArray? {
        require(frame.size == 320)
        elapsedFrames++
        if (frames.isEmpty()) {
            preRoll.addLast(frame.copyOf())
            if (preRoll.size > 15) preRoll.removeFirst()
            if (!speech) return null
            frames.addAll(preRoll)
            preRoll.clear()
        } else frames += frame.copyOf()
        if (speech) {
            spokenFrames++
            silentFrames = 0
        } else silentFrames++
        if (silentFrames < 60 && frames.size < 1_500) return null
        val result = if (spokenFrames >= 8) pcmWav(frames) else null
        frames.clear()
        spokenFrames = 0
        silentFrames = 0
        return result
    }
}

internal fun pcmWav(frames: List<ShortArray>): ByteArray {
    val size = frames.sumOf { it.size } * 2
    return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000)
        putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        frames.forEach { frame -> frame.forEach { putShort(it) } }
    }.array()
}

/** Split without truncating text or splitting surrogate pairs; prefer sentence and word boundaries. */
internal fun splitSpeech(text: String, limit: Int = 200): List<String> {
    require(limit > 0)
    var rest = text.trim()
    return buildList {
        while (rest.isNotEmpty()) {
            if (rest.codePointCount(0, rest.length) <= limit) {
                add(rest)
                break
            }
            val end = rest.offsetByCodePoints(0, limit)
            val prefix = rest.substring(0, end)
            val sentence = Regex("[.!?](?:\\s|$)").findAll(prefix).lastOrNull()?.range?.last
            val word = prefix.indexOfLast { it.isWhitespace() }
            val cut = when {
                sentence != null && sentence >= end / 2 -> sentence + 1
                word > 0 -> word
                else -> end
            }
            add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trimStart()
        }
    }
}

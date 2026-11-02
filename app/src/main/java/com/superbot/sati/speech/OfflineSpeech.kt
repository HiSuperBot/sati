package com.superbot.sati.speech

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

object OfflineSpeech {
    fun voice(voices: Set<Voice>, locale: Locale): Voice? = voices
        .filter {
            it.locale.language == locale.language &&
                !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        }
        .sortedWith(
            compareByDescending<Voice> { it.locale == locale }
                .thenByDescending { it.quality }.thenBy { it.name },
        )
        .firstOrNull()

    /** Keep punctuation with its sentence, switching voices only when the written language changes. */
    fun segments(text: String): List<Pair<Locale, String>> {
        val result = mutableListOf<Pair<Locale, String>>()
        val part = StringBuilder()
        var locale = Locale.US
        text.forEach { character ->
            val next = when {
                character in '\uAC00'..'\uD7AF' ||
                    character in '\u1100'..'\u11FF' ||
                    character in '\u3130'..'\u318F' -> Locale.KOREA
                character in 'a'..'z' || character in 'A'..'Z' -> Locale.US
                else -> locale
            }
            if (next != locale && part.isNotBlank()) {
                result += locale to part.toString()
                part.clear()
            }
            locale = next
            part.append(character)
        }
        if (part.isNotBlank()) result += locale to part.toString()
        return result
    }
}

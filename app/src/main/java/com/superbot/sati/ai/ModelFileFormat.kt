package com.superbot.sati.ai

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

object ModelFileFormat {
    /** Reject downloads of error pages and truncated headers before invoking native code. */
    fun hasValidHeader(input: InputStream): Boolean {
        val bytes = ByteArray(8)
        return try {
            DataInputStream(input).readFully(bytes)
            bytes.contentEquals("LITERTLM".toByteArray(Charsets.US_ASCII))
        } catch (_: EOFException) {
            false
        }
    }
}

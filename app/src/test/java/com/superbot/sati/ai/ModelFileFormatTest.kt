package com.superbot.sati.ai

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFileFormatTest {
    @Test
    fun acceptsTheContainerMagicEvenWhenReadsArriveInSmallChunks() {
        val input = object : FilterInputStream(ByteArrayInputStream("LITERTLMdata".toByteArray())) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 2))
        }
        assertTrue(ModelFileFormat.hasValidHeader(input))
    }

    @Test
    fun rejectsTruncatedAndEmptyFiles() {
        assertFalse(ModelFileFormat.hasValidHeader(ByteArrayInputStream(byteArrayOf())))
        assertFalse(ModelFileFormat.hasValidHeader(ByteArrayInputStream("LITERT".toByteArray())))
    }

    @Test
    fun rejectsHtmlErrorPagesRenamedAsModels() {
        assertFalse(ModelFileFormat.hasValidHeader(ByteArrayInputStream("<!DOCTYPE html>".toByteArray())))
    }
}

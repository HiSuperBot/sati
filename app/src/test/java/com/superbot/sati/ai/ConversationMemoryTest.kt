package com.superbot.sati.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryTest {
    @Test
    fun longConversationKeepsTheIntroductionAndRecentTurns() {
        val memory = ConversationMemory()
        memory.remember("Hello", "Hi!")
        memory.remember("My name is Mina", "Nice to meet you, Mina!")
        repeat(20) { memory.remember("Question $it", "Answer $it") }
        val snapshot = memory.snapshot()
        assertEquals(6, snapshot.size)
        assertEquals("My name is Mina", snapshot[1].user)
        assertEquals("Question 16", snapshot[2].user)
        assertEquals("Question 19", snapshot.last().user)
    }

    @Test
    fun newConversationRemovesPersonalContext() {
        val memory = ConversationMemory()
        memory.remember("My name is Mina", "Hello Mina!")
        val previousSnapshot = memory.snapshot()
        memory.clear()
        assertTrue(memory.snapshot().isEmpty())
        assertEquals(1, previousSnapshot.size)
    }
}

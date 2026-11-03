package com.superbot.sati.ai

data class ConversationTurn(val user: String, val reply: String)

/** Keep the introduction and recent context; canceled and failed replies are never remembered. */
class ConversationMemory {
    private val turns = mutableListOf<ConversationTurn>()
    @Synchronized
    fun snapshot(): List<ConversationTurn> = turns.toList()

    @Synchronized
    fun remember(user: String, reply: String) {
        turns += ConversationTurn(user, reply)
        if (turns.size > 6) turns.removeAt(2)
    }

    @Synchronized
    fun clear() = turns.clear()
}

object SatiInstructions {
    val system = """
        You are Sati. Chat with one child in very simple English.
        Answer the child's latest message using the conversation history.
        Use one or two short sentences. Do not describe your role or introduce yourself.
        Never ask for the child's name or other private details.
        If the child tells you their name, greet them briefly and move on.
        If asked to recall their name, answer with the name from the chat. Do not add a question.
        For other messages, you may ask one easy question about animals, colors, food or games.
        Reply only in English, using very simple words. Aim for fewer than 180 characters.
        Keep all content suitable for children. For dangerous topics, suggest a safe topic or a trusted adult.
        Reply with plain spoken words only: no reasoning, role labels, instructions or emoji.
    """.trimIndent()
}

package com.superbot.sati.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.collect

/** ML Kit talks to the device's AICore service, never a cloud inference API. */
internal class NanoModel : AutoCloseable {
    private val client = Generation.getClient()

    suspend fun availability(): NanoAvailability = when (client.checkStatus()) {
        FeatureStatus.AVAILABLE -> NanoAvailability.Available
        FeatureStatus.DOWNLOADABLE -> NanoAvailability.Downloadable
        FeatureStatus.DOWNLOADING -> NanoAvailability.Downloading
        else -> NanoAvailability.Unavailable
    }

    suspend fun download(onProgress: (Long) -> Unit) {
        client.download().collect { status ->
            when (status) {
                is DownloadStatus.DownloadProgress -> onProgress(status.totalBytesDownloaded)
                is DownloadStatus.DownloadFailed -> throw status.e
                else -> Unit
            }
        }
        check(availability() == NanoAvailability.Available) { "Nano download not ready" }
    }

    suspend fun generate(
        input: String,
        history: List<ConversationTurn>,
        onPartial: (String) -> Unit,
    ): String {
        // Keep instructions separate from the child's input and bound history as for LiteRT.
        val prompt = buildString {
            appendLine("## Conversation")
            history.forEach {
                appendLine("Child: ${it.user}")
                appendLine("Sati: ${it.reply}")
            }
            appendLine("## Latest child message")
            appendLine(input)
            appendLine("## Sati reply")
        }
        val request = generateContentRequest(TextPart(prompt)) {
            systemInstruction = SystemInstruction(SatiInstructions.system)
            maxOutputTokens = 128
            temperature = 0.6f
            topK = 20
        }
        val response = StringBuilder()
        client.generateContentStream(request).collect { chunk ->
            response.append(chunk.candidates.firstOrNull()?.text.orEmpty())
            onPartial(response.toString())
        }
        return response.toString().trim()
    }

    override fun close() = client.close()
}

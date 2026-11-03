package com.superbot.sati.ai

enum class CloudProvider(val label: String, val keyUrl: String, val limitsUrl: String) {
    DeepSeek("DeepSeek", "https://platform.deepseek.com", "https://platform.deepseek.com"),
    Groq("Groq", "https://console.groq.com/keys", "https://console.groq.com/settings/limits"),
}

enum class PipelineStage(val label: String) { Stt("STT"), Chat("Model"), Tts("TTS") }

enum class SttModel(val id: String, val label: String, val apiModel: String? = null) {
    Android("android", "Android on-device"),
    WhisperTurbo("whisper-large-v3-turbo", "Groq Whisper Large V3 Turbo", "whisper-large-v3-turbo"),
    Whisper("whisper-large-v3", "Groq Whisper Large V3", "whisper-large-v3");
    val isCloud get() = apiModel != null
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: Android
    }
}

enum class TtsModel(val id: String, val label: String) {
    Android("android", "Android on-device"),
    Orpheus("canopylabs/orpheus-v1-english", "Groq Orpheus English");
    val isCloud get() = this == Orpheus
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: Android
    }
}

enum class OrpheusVoice(val id: String, val label: String) {
    Troy("troy", "Troy"), Austin("austin", "Austin"), Daniel("daniel", "Daniel"),
    Autumn("autumn", "Autumn"), Diana("diana", "Diana"), Hannah("hannah", "Hannah");
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: Troy
    }
}

data class PipelineConfig(
    val stt: SttModel = SttModel.Android,
    val chat: AiModel = AiModel.Qwen,
    val tts: TtsModel = TtsModel.Android,
    val voice: OrpheusVoice = OrpheusVoice.Troy,
) {
    val usesGroq get() = stt.isCloud || tts.isCloud || chat.provider == CloudProvider.Groq
}

enum class FailureHint { AccessRequired, ModelUnavailable }

data class StageFailure(
    val stage: PipelineStage,
    val provider: CloudProvider,
    val modelId: String,
    val reason: ReplyFailure,
    val retryAtMillis: Long? = null,
    val hint: FailureHint? = null,
    val recordingExpired: Boolean = false,
) {
    fun remainingSeconds(now: Long) = retryAtMillis?.let {
        ((it - now).coerceAtLeast(0) + 999) / 1000
    } ?: 0
}

internal class StageException(val failure: StageFailure) :
    java.io.IOException("Pipeline failed: ${failure.stage}/${failure.provider}/${failure.reason}")

data class RateLimitSnapshot(
    val modelId: String,
    val observedAtMillis: Long,
    val limitRequests: Long? = null,
    val remainingRequests: Long? = null,
    val limitTokens: Long? = null,
    val remainingTokens: Long? = null,
    val resetRequestsAtMillis: Long? = null,
    val resetTokensAtMillis: Long? = null,
)

internal fun interface ApiKeyValidator {
    suspend fun validateKey(key: String)
}

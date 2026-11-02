package com.superbot.sati.ai

/** Stable IDs are persisted; display names are also the models' official names. */
enum class AiModel(val id: String, val displayName: String) {
    Qwen("qwen3-0.6b", "Qwen3 0.6B"),
    Gemma("gemma4-e2b", "Gemma 4 E2B"),
    GeminiNano("gemini-nano", "Gemini Nano"),
    DeepSeek("deepseek-flash", "DeepSeek Flash");

    val isCloud: Boolean get() = this == DeepSeek

    val downloadBytes: Long get() = spec?.bytes ?: 0L

    internal val spec: ModelSpec?
        get() = when (this) {
            Qwen -> SatiModel.spec
            Gemma -> ModelSpec(
                fileName = "gemma4-e2b-6e5c4f1.litertlm",
                url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
                    "6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm",
                bytes = 2_588_147_712L,
                sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            )
            GeminiNano, DeepSeek -> null // Managed by AICore or accessed via the cloud API.
        }

    companion object {
        fun fromId(id: String?): AiModel = entries.firstOrNull { it.id == id } ?: Qwen
    }
}

enum class NanoAvailability {
    Checking, Available, Downloadable, Downloading, Unavailable, CheckFailed;

    val canSelect: Boolean
        get() = this == Available || this == Downloadable || this == Downloading
}

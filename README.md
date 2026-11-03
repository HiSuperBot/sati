# Sati

A child-friendly Android companion for practicing simple English through voice
conversation, with animated eyes.

Choose each stage independently:

- **STT · Listen:** Android on-device recognition or Groq Whisper.
- **Model · Conversation:** Qwen, Gemma, supported Gemini Nano, DeepSeek Flash,
  or Groq GPT-OSS 20B/120B.
- **TTS · Speak:** Android offline voice or Groq Orpheus English with selectable voices.

Sati listens automatically while open and ready, and listens again after replying.
Use Pause/Resume for a break. Chat has no typing field or Talk button.

Groq uses one securely stored API key across its three stages; DeepSeek uses a
separate key. Cloud stages require internet and may incur API charges. The UI
shows which audio/text leaves the device, reported quotas, and explicit recovery
choices when a stage hits a limit.

See [setup, privacy, and verification](docs/offline.md).

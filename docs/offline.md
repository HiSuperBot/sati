# Voice conversation with on-device and cloud models

Sati uses on-device speech recognition and installed offline text-to-speech
voices. Chat is voice-only. Choose an on-device language model for offline
replies, or explicitly select DeepSeek Flash for cloud replies. The only text
entry is the DeepSeek API-key setup field.

## Choose a model

Qwen is the default and starts setup automatically. Tap **Change model** to select
a different model. The selection survives app restarts. Switching starts a fresh
conversation, stops pending work, and keeps downloaded weights for offline reuse.
Only one LiteRT engine is loaded at a time.

| Model | Download | Runtime |
| --- | --- | --- |
| Qwen3 0.6B | 613,722,264 bytes (614 MB) | LiteRT-LM CPU |
| Gemma 4 E2B | 2,588,147,712 bytes (2.59 GB) | LiteRT-LM CPU |
| Gemini Nano | Managed by Android AICore | ML Kit Prompt API |
| DeepSeek Flash | No model download; API key and internet required | DeepSeek cloud API |

Gemma needs substantially more storage and memory. Compatibility and response
quality must be checked on target hardware.

Nano is selectable only when ML Kit reports **AVAILABLE**, **DOWNLOADABLE**, or
**DOWNLOADING**. Its status is checked on the device, rather than inferred from the
phone name or a subscription. Unsupported devices show an explanation; a failed
check offers **Check Nano again**. The connected Samsung phone reported Nano
**UNAVAILABLE** during validation. Qwen and Gemma remain available choices.

On supported devices, selecting Nano downloads required assets through AICore,
waits for **AVAILABLE**, and then enables conversation. Downloads have a bounded
wait and can be canceled from Sati; AICore may continue its shared download.
Nano inference is canceled when a turn ends or the app backgrounds. Its client is
closed when the ViewModel is destroyed. There is no cloud fallback.

## DeepSeek setup

1. Tap **Change model**, then **DeepSeek Flash**.
2. Paste your own DeepSeek API key in the masked field and tap **Connect**.
   Keys come from [DeepSeek Platform](https://platform.deepseek.com); API charges apply.
3. Sati validates the key using `GET https://api.deepseek.com/models` and confirms
   `deepseek-flash` is available. Setup never sends a paid chat completion.
4. After successful validation and secure storage, DeepSeek becomes selected.
   Tap **Talk** to begin. A saved key is reused after restarting the app.
5. Use **Change API key** to replace it, or **Remove API key** to delete it.
   Removing the key disables DeepSeek voice turns until setup succeeds.

Canceling setup or entering an invalid key preserves the previous model and
conversation. Failed replacements preserve the previous saved key. An unsaved
draft stays in ViewModel memory across rotation, is cleared when dismissed or
backgrounded, and never enters saved instance state. Validation is canceled on
rotation and can be retried.

The key is encrypted with AES-256-GCM using a non-exportable Android Keystore
key. Each save uses a fresh IV and an atomic write in
`noBackupFilesDir/credentials/deepseek.key`. Keys and ciphertext are excluded
from backups. Uninstalling Sati removes its credential. Missing Keystore keys or
corrupt ciphertext require reentry; diagnostics never contain the key.

DeepSeek receives recognized speech text, Sati’s system instructions, and the
bounded conversation history. Raw audio stays on-device. Only `delta.content`
is displayed; reasoning fields are ignored. TTS starts after a successful
complete stream, so truncated, failed, or canceled replies are never spoken.

Authentication, insufficient balance, rate limit, network, and service failures
have distinct messages. Authentication failure requires key setup again.
Generation requests are not retried automatically and have a 90-second total
timeout. Redirects are disabled. Cancel, backgrounding, model changes, and key
removal cancel network calls and ignore late callbacks. Local models never
send conversation text to DeepSeek, and cloud failures never silently switch
the selected model.

## First launch and speech setup

- Connect to the internet and launch Sati. The setup card shows download progress.
  **Cancel download** pauses app-managed downloads; **Retry setup** resumes them.
  Model selection remains available during setup.
- Cached LiteRT weights are verified before reuse and require no network.
  Uninstalling Sati or clearing app data removes those weights.
- Tap **Talk**, allow microphone access, and wait for **I’m listening…**.
  Conversation is voice-only; transcripts and replies remain visible.
- Microphone input needs Android 12+ with an on-device recognition service.
  Sati never falls back to a cloud recognizer.
- On Android 14+, Sati requests missing English speech data automatically. The
  speech service may show a download confirmation. This phone required an
  additional 62 MB English recognition model. Recording starts after successful
  setup while Sati stays in the foreground; if setup backgrounds the app, return
  and tap **Talk**.
- Android 13 can schedule that download; retry after it finishes. Android 12
  needs English speech data installed through device speech settings.
- Install offline English and Korean TTS voices through device settings.
  A reply remains readable if its voice is unavailable.
- Canceling, rotating, or backgrounding Sati stops recording and playback.
  Late setup or generation callbacks cannot start another recording.

The app minimum SDK is 26 for ML Kit Prompt API. Microphone recognition has the
additional Android 12+ requirement above.

## Model integrity and network policy

Both downloadable LiteRT models are pinned to immutable revisions:

- [Qwen3-0.6B.litertlm](https://huggingface.co/litert-community/Qwen3-0.6B/blob/e6687177e00897731e7716267eebd4eba0dde7c2/Qwen3-0.6B.litertlm)
  - Revision: `e6687177e00897731e7716267eebd4eba0dde7c2`
  - SHA-256: `c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108`
- [gemma-4-E2B-it.litertlm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm)
  - Revision: `6e5c4f1e395deb959c494953478fa5cec4b8008f`
  - SHA-256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`

The downloader issues HTTPS GETs for catalogue models and approved Hugging Face
CDNs. Redirects to other domains, cleartext HTTP, credentials, and unexpected
ports are rejected. No conversation text, audio, or identifiers are passed to
the downloader. DeepSeek uses a separate HTTPS client only when explicitly
selected or when the user submits a key for validation.

Android grants INTERNET permission app-wide. Application code restricts its
download client to model downloads. The DeepSeek client uses only the fixed
`https://api.deepseek.com/` endpoint for key validation and selected cloud chat.
AICore separately manages Nano assets and
availability/configuration checks; Android’s on-device recognition service
manages English speech-model downloads.

LiteRT weights live in app-private `noBackupFilesDir/models`, outside the APK
and backups. Each model has its own final and `.part` file. Size, container
header, and SHA-256 must match before promotion and inference. Interrupted
transfers resume without overwriting other models. Corrupt files are replaced.
Cancellation is checked per read; stalled reads have a 20-second timeout.

A development copy may exist in Git-ignored `local-models/` on the workstation.
It is not required to install or run the APK.

## Conversation lifecycle

Initialization, inference, cancellation, and release of LiteRT objects use one
background owner thread. History stays in RAM, survives rotation, and clears on
**Start over**, model switch, key replacement/removal, or process termination.
All reply backends receive the
first two and four latest completed turns. Canceled replies are not remembered.
Input is limited to 240 characters and output to 128 tokens.

Nano uses ML Kit Prompt API 1.0.0-beta4, checks readiness before inference, and
streams responses with Sati’s system instructions. Inference runs in the
foreground and is subject to AICore’s per-app limits. A failure offers retry
rather than silently changing the selected model.

## Verify

1. Finish AI and speech setup, then disable Wi-Fi and cellular data.
2. Relaunch and check that the selected downloaded model loads offline.
3. Tap **Talk** and say **My name is Mina**, then **What is my name?**
4. Ask for brief Korean help and continue in English.
5. Cancel, rotate, and background during recording, generation, and playback.
6. Switch between downloaded models offline; verify history resets.
7. Check Nano availability and use it on a supported physical device.
8. Select DeepSeek and connect with a key entered only in the app. Verify a
   spoken reply, name recall, restart with the saved key, key replacement/removal,
   loss of connectivity, and cancellation. Never put real keys in tests or logs.

Development checks (use Android Studio or a terminal configured with its JDK):

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Device tests require an unlocked phone. Their install/uninstall cycle may clear
app-private model files; allow setup to complete again afterward.

Tests cover voice-only controls, speech setup cancellation, model selection,
Nano availability gating, model-specific progress, bounded history, stale
callbacks, integrity checks, resuming downloads, separate model caches, offline
reuse, and network policy. DeepSeek tests use MockWebServer and test-only keys
for validation, payloads, streaming, cancellation, errors, and redirect rejection.
Credential tests verify encrypted persistence, fresh IVs, removal, and corruption.

References:
[LiteRT-LM Android](https://developers.google.com/edge/litert-lm/android),
[ML Kit Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started),
[on-device speech setup](https://developer.android.com/reference/android/speech/SpeechRecognizer#triggerModelDownload(android.content.Intent,%20java.util.concurrent.Executor,%20android.speech.ModelDownloadListener)).

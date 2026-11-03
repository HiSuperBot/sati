# Voice conversation with on-device and cloud models

Sati is an English, voice-only companion. Select STT (listening), a conversation
model, and TTS (speaking) independently. Each stage can run on-device or use its
explicitly selected cloud provider. The only text entry is API-key setup.

## Choose the three stages

- **STT · Listen**: Android on-device recognition, Groq Whisper Large V3 Turbo,
  or Groq Whisper Large V3.
- **Model · Conversation**: Qwen, Gemma, supported Gemini Nano, DeepSeek Flash,
  Groq GPT-OSS 20B, or Groq GPT-OSS 120B.
- **TTS · Speak**: Android's installed offline English voice, or Groq Orpheus
  English with Troy, Austin, Daniel, Autumn, Diana, or Hannah. Troy is the default.

Selections apply individually and survive app restarts. Existing installations
keep their conversation model and start with Android STT/TTS. Changing STT or
TTS preserves history; changing the conversation model starts a new chat.
Opening a chooser suspends listening. A selection requiring a missing key opens
masked setup first; canceling or failing validation keeps the previous selection.

## Choose a conversation model

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
| Groq GPT-OSS 20B / 120B | No model download; shared Groq key and internet required | Groq cloud API |

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

## Groq connection, speech, and limits

Choose a Groq option in any of the three stages and enter your own key from
[Groq Console](https://console.groq.com/keys). One encrypted Groq key is shared by
all three stages; DeepSeek has a separate credential. Key validation only lists
models and does not generate paid speech or a chat completion. Model access or
terms acceptance can still be required by the provider at actual use.

Groq STT captures 16kHz mono 16-bit PCM with AudioRecord and bundled WebRTC VAD
2.0.10. It includes a 300 ms pre-roll, requires at least 160 ms of detected speech,
ends after 1.2 seconds of silence, and caps clips at 30 seconds. Silence times out
locally without an upload. Speech is uploaded as WAV to
`https://api.groq.com/openai/v1/audio/transcriptions` with language `en`.
No Android recognition service or installed TTS voice is needed for Groq STT.

Groq chat streams only answer content from `chat/completions`; reasoning is
excluded. It uses low reasoning effort and a 1024-token completion budget because
GPT-OSS includes internal reasoning in that budget. Replies are instructed to
use one or two simple English sentences, preferably fewer than 180 characters.

Groq TTS uses `audio/speech`, `canopylabs/orpheus-v1-english`, and WAV output.
Orpheus is a preview model. Text longer than its 200-character request limit is
split at sentence/word boundaries, synthesized, and played sequentially.
Successful chunks advance once; a failed chunk remains pending for explicit retry.
Temporary playback files live in cache and are deleted after playback/cancellation.

A Groq error identifies its stage, provider, and model. On HTTP 429, the loop
stops and offers **Try again**, another stage selection, a device alternative,
and Groq Console. No automatic retry or provider switch occurs. A reported
`Retry-After` controls a countdown; exhausted quota reset headers are used when
available. Missing retry times remain unknown instead of being invented.
Timers update the UI only and never submit a request.

Quota cards show the last server-reported daily remaining requests and per-minute
remaining tokens with the observation time. Limits are organization-wide and can
be consumed outside Sati; the app does not hard-code a free daily allowance or
claim to know unreported audio quotas. A new app process starts with unknown usage.

Retry resumes only the failed stage: STT resubmits the buffered recording, chat
reuses the recognized input, and TTS speaks the unplayed reply without asking the
conversation model again. Retry recordings stay in memory only while the app is
active; Pause, loss of focus, or backgrounding clears them and the UI asks the
user to speak again. Switching to Android STT also requires a new utterance.
Switching a failed TTS to Android uses the remaining reply. Switching the
conversation model starts fresh history; Qwen may need its normal setup first.

Missing/invalid keys, account access or spending problems, missing models,
network failures, and malformed responses have separate recovery messages.
Removing the Groq key disables every selected Groq stage. Key replacement/removal
never affects the separate DeepSeek credential.

Privacy follows the selection: Groq STT sends recorded audio; Groq/DeepSeek chat
sends recognized text and bounded history; Groq TTS sends reply text. Android
STT/TTS stay on-device. The screen explains the current combination.

## DeepSeek setup

1. Tap **Change model**, then **DeepSeek Flash**.
2. Paste your own DeepSeek API key in the masked field and tap **Connect**.
   Keys come from [DeepSeek Platform](https://platform.deepseek.com); API charges apply.
3. Sati validates the key using `GET https://api.deepseek.com/models` and confirms
   `deepseek-flash` is available. Setup never sends a paid chat completion.
4. After successful validation and secure storage, DeepSeek becomes selected.
   Sati begins listening when ready. A saved key is reused after restarting the app.
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
bounded conversation history. DeepSeek itself never receives audio; a selected
Groq STT stage separately receives recordings. Only `delta.content`
is displayed; reasoning fields are ignored. TTS starts after a successful
complete stream, so truncated, failed, or canceled replies are never spoken.

Authentication, insufficient balance, rate limit, network, and service failures
have distinct messages. Authentication failure requires key setup again.
Generation requests are not retried automatically and have a 90-second total
timeout. Redirects are disabled. Pause, backgrounding, model changes, and key
removal cancel network calls and ignore late callbacks. Local models never
send conversation text to DeepSeek, and cloud failures never silently switch
the selected model.

## First launch and speech setup

- Connect to the internet and launch Sati. The setup card shows download progress.
  **Cancel download** pauses app-managed downloads; **Retry setup** resumes them.
  Model selection remains available during setup.
- Cached LiteRT weights are verified before reuse and require no network.
  Uninstalling Sati or clearing app data removes those weights.
- Allow microphone access when requested, and wait for **I’m listening…**.
  Sati starts listening automatically when the selected model is ready.
  Conversation is voice-only; transcripts and replies remain visible.
- Android STT needs Android 12+ with an on-device recognition service.
  Groq STT uses AudioRecord on the app's supported Android versions. There is no
  automatic fallback between them.
- On Android 14+, Sati requests missing English speech data automatically. The
  speech service may show a download confirmation. This phone required an
  additional 62 MB English recognition model. Recording starts after successful
  setup while Sati stays in the foreground; if setup backgrounds the app, return
  to Sati; listening resumes automatically.
- Android 13 can schedule that download; retry after it finishes. Android 12
  needs English speech data installed through device speech settings.
- For Android TTS, install an offline English voice through device settings.
  A reply remains readable if its voice is unavailable.
- **Pause** stops recording, generation, playback, and pending listening restarts.
  **Resume** enables automatic listening again. Pause survives rotation and
  backgrounding; a fresh app launch starts enabled.
- Sati stops while backgrounded, unfocused, or showing model/key setup dialogs.
  Returning resumes listening when ready, unless paused. Rotation stops the old
  session and resumes with the new screen. Late callbacks are ignored.

The app minimum SDK is 26 for ML Kit Prompt API. Microphone recognition has the
additional Android 12+ requirement only when Android STT is selected.

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
selected or when the user submits a key for validation. Groq has its own fixed
HTTPS client for its selected stages; redirects and automatic retries are disabled.

Android grants INTERNET permission app-wide. Application code restricts its
download client to model downloads. The DeepSeek client uses only the fixed
`https://api.deepseek.com/` endpoint for key validation and selected cloud chat.
Groq uses only `https://api.groq.com/openai/v1/` for its selected stages and key
validation. Groq ciphertext is stored separately in
`noBackupFilesDir/credentials/groq.key` under a separate Keystore alias.
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

While the conversation screen is active, Sati automatically repeats listening,
generating, and speaking. The microphone stays off during generation and playback;
listening resumes 500 ms after speech completes. Silence or empty recognition
results restart listening after one second without an AI request or error.
Other failures require the displayed recovery action; AI requests are never
retried automatically. Permission denial never causes repeated automatic prompts.

**Change model** and **Start over** remain usable while listening. Opening the
model chooser suspends the microphone. Canceling preserves history; switching
models resets it. Starting over clears history and listens again unless paused.
There is no background microphone service or wake word.

Initialization, inference, cancellation, and release of LiteRT objects use one
background owner thread. History stays in RAM, survives rotation, and clears on
**Start over**, model switch, key replacement/removal, or process termination.
All reply backends receive the
first two and four latest completed turns. Canceled replies are not remembered.
Input is limited to 240 characters. Local and DeepSeek replies have a 128-token
output budget; GPT-OSS uses the budget described above.

Nano uses ML Kit Prompt API 1.0.0-beta4, checks readiness before inference, and
streams responses with Sati’s system instructions. Inference runs in the
foreground and is subject to AICore’s per-app limits. A failure offers retry
rather than silently changing the selected model.

## Verify

1. Finish AI and speech setup, then disable Wi-Fi and cellular data.
2. Relaunch and check that the selected downloaded model loads offline.
3. Without pressing a Talk button, say **My name is Mina**, wait for Sati’s reply,
   then say **What is my name?** when listening resumes.
4. Check short, simple English replies and the selected TTS voice.
5. Pause, resume, rotate, open setup dialogs, and background during recording,
   generation, and playback. Check silence retries without sending AI requests.
6. Switch between downloaded models offline; verify history resets.
7. Check Nano availability and use it on a supported physical device.
8. Try independent Android/Groq STT and TTS combinations and both Groq chat models.
   Verify shared key reuse, stage-specific limits, countdowns, device recovery,
   long-reply speech splitting, and cancellation.
9. Select DeepSeek and connect with a key entered only in the app. Verify a
   spoken reply, name recall, restart with the saved key, key replacement/removal,
   loss of connectivity, and cancellation. Never put real keys in tests or logs.

Development checks (use Android Studio or a terminal configured with its JDK):

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

Device tests require an unlocked phone. Keep the leave-APKs-installed flag above
to preserve app data after the run; uninstalling or clearing data removes models
and credentials. Tests use isolated random credential files/aliases, never real keys.

Tests cover voice-only controls, speech setup cancellation, model selection,
Nano availability gating, model-specific progress, bounded history, stale
callbacks, integrity checks, resuming downloads, separate model caches, offline
reuse, and network policy. DeepSeek tests use MockWebServer and test-only keys
for validation, payloads, streaming, cancellation, errors, and redirect rejection.
Credential tests verify encrypted persistence, fresh IVs, removal, corruption, and
provider isolation. Groq tests cover multipart uploads, WAV decoding, answer-only
streams, per-stage failures and cancellation, quota parsing, replay without
regeneration, VAD framing, and no upload for silence. Device tests also load the
native VAD and exercise the three selectors and recovery UI.

References:
[LiteRT-LM Android](https://developers.google.com/edge/litert-lm/android),
[ML Kit Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started),
[on-device speech setup](https://developer.android.com/reference/android/speech/SpeechRecognizer#triggerModelDownload(android.content.Intent,%20java.util.concurrent.Executor,%20android.speech.ModelDownloadListener)).

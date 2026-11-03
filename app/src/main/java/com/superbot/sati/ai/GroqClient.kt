package com.superbot.sati.ai

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Direct BYOK requests only. No redirects, logging, disk cache, or automatic retries. */
internal class GroqClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).build(),
    private val baseUrl: HttpUrl = "https://api.groq.com/openai/v1/".toHttpUrl(),
    private val onQuota: (RateLimitSnapshot) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : ApiKeyValidator {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun validateKey(key: String) {
        request(key, "models", PipelineStage.Chat, "models", null) {
            val data = json.parseToJsonElement(it.body.readBounded(256 * 1024).decodeToString())
                .jsonObject["data"]?.jsonArray ?: error("Invalid model list")
            check(data.isNotEmpty())
        }
    }

    suspend fun transcribe(key: String, model: SttModel, wav: ByteArray): String {
        val id = checkNotNull(model.apiModel)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", id).addFormDataPart("language", "en")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        return request(key, "audio/transcriptions", PipelineStage.Stt, id, body) {
            json.parseToJsonElement(it.body.readBounded(256 * 1024).decodeToString())
                .jsonObject["text"]?.jsonPrimitive?.contentOrNull
                ?: error("Invalid transcription")
        }
    }

    suspend fun synthesize(key: String, text: String, voice: OrpheusVoice): ByteArray {
        require(text.isNotBlank() && text.codePointCount(0, text.length) <= 200)
        val body = buildJsonObject {
            put("model", TtsModel.Orpheus.id)
            put("input", text)
            put("voice", voice.id)
            put("response_format", "wav")
        }.toString().toRequestBody("application/json".toMediaType())
        return request(key, "audio/speech", PipelineStage.Tts, TtsModel.Orpheus.id, body) {
            it.body.readBounded(8 * 1024 * 1024).also { audio ->
                check(audio.size > 44 && audio.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                    audio.copyOfRange(8, 12).decodeToString() == "WAVE")
            }
        }
    }

    fun stream(key: String, model: AiModel, input: String, history: List<ConversationTurn>): Flow<String> =
        callbackFlow {
            require(model.provider == CloudProvider.Groq)
            val messages = buildList {
                add(message("system", SatiInstructions.system))
                history.forEach {
                    add(message("user", it.user))
                    add(message("assistant", it.reply))
                }
                add(message("user", input))
            }
            val body = buildJsonObject {
                put("model", model.id)
                put("messages", JsonArray(messages))
                put("stream", true)
                put("reasoning_effort", "low")
                put("include_reasoning", false)
                put("max_completion_tokens", 1024)
                put("temperature", 0.6)
            }.toString().toRequestBody("application/json".toMediaType())
            val call = client.newCall(buildRequest(key, "chat/completions", body))
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    close(failure(PipelineStage.Chat, model.id, ReplyFailure.Network))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            checkResponse(it, PipelineStage.Chat, model.id)
                            var size = 0
                            val complete = readEvents(it.body.charStream().buffered()) { event ->
                                if (event == "[DONE]") return@readEvents false
                                val chunk = json.parseToJsonElement(event).jsonObject
                                check("error" !in chunk)
                                val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                                    ?: return@readEvents true
                                val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                check(finish == null || finish in setOf("stop", "length"))
                                val delta = choice["delta"]?.jsonObject?.get("content")
                                    ?.jsonPrimitive?.contentOrNull.orEmpty()
                                if (delta.isNotEmpty()) {
                                    size += delta.length
                                    check(size <= 16 * 1024)
                                    if (trySendBlocking(delta).isFailure) throw IOException("Canceled")
                                }
                                true
                            }
                            check(complete && size > 0)
                        }
                        close()
                    } catch (e: StageException) { close(e) }
                    catch (_: IOException) { close(failure(PipelineStage.Chat, model.id, ReplyFailure.Network)) }
                    catch (_: Exception) { close(failure(PipelineStage.Chat, model.id, ReplyFailure.InvalidResponse)) }
                }
            })
            awaitClose { call.cancel() }
        }

    private suspend fun <T> request(
        key: String, path: String, stage: PipelineStage, modelId: String, body: RequestBody?,
        consume: (Response) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(buildRequest(key, path, body))
        if (path == "models") call.timeout().timeout(15, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(failure(stage, modelId, ReplyFailure.Network))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        checkResponse(it, stage, modelId)
                        consume(it)
                    }
                    continuation.resume(value)
                } catch (e: StageException) { continuation.resumeWithException(e) }
                catch (_: IOException) {
                    continuation.resumeWithException(failure(stage, modelId, ReplyFailure.Network))
                } catch (_: Exception) {
                    continuation.resumeWithException(failure(stage, modelId, ReplyFailure.InvalidResponse))
                }
            }
        })
    }

    private fun buildRequest(key: String, path: String, body: RequestBody?): Request {
        require(validApiKey(key))
        return Request.Builder().url(checkNotNull(baseUrl.resolve(path)))
            .header("Authorization", "Bearer $key").header("Accept", "application/json, audio/wav, text/event-stream")
            .apply { if (body != null) post(body) }.build()
    }

    private fun checkResponse(response: Response, stage: PipelineStage, modelId: String) {
        val now = clock()
        val quota = rateSnapshot(response.headers, modelId, now)
        if (modelId != "models") onQuota(quota)
        if (response.isSuccessful) return
        // Only a bounded, known error code is interpreted. Server prose is never displayed or logged.
        val code = runCatching {
            json.parseToJsonElement(response.body.readBounded(8192).decodeToString())
                .jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val hint = when {
            code == "model_terms_required" || response.code == 403 -> FailureHint.AccessRequired
            code in setOf("model_not_found", "model_decommissioned") || response.code == 404 ->
                FailureHint.ModelUnavailable
            else -> null
        }
        val reason = when (response.code) {
            401 -> ReplyFailure.Authentication
            402 -> ReplyFailure.Balance
            429 -> ReplyFailure.RateLimit
            in 500..599 -> ReplyFailure.Service
            else -> if (hint != null) ReplyFailure.Service else ReplyFailure.InvalidResponse
        }
        val retryAt = if (response.code == 429) retryDeadline(response.headers, quota, now) else null
        throw StageException(StageFailure(stage, CloudProvider.Groq, modelId, reason, retryAt, hint))
    }

    private fun failure(stage: PipelineStage, modelId: String, reason: ReplyFailure) =
        StageException(StageFailure(stage, CloudProvider.Groq, modelId, reason))

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role); put("content", content)
    }
}

internal fun rateSnapshot(headers: Headers, modelId: String, now: Long) = RateLimitSnapshot(
    modelId, now,
    headers["x-ratelimit-limit-requests"]?.toLongOrNull()?.takeIf { it >= 0 },
    headers["x-ratelimit-remaining-requests"]?.toLongOrNull()?.takeIf { it >= 0 },
    headers["x-ratelimit-limit-tokens"]?.toLongOrNull()?.takeIf { it >= 0 },
    headers["x-ratelimit-remaining-tokens"]?.toLongOrNull()?.takeIf { it >= 0 },
    parseReset(headers["x-ratelimit-reset-requests"], now),
    parseReset(headers["x-ratelimit-reset-tokens"], now),
)

internal fun parseReset(value: String?, now: Long): Long? {
    if (value == null) return null
    val parts = Regex("(\\d+(?:\\.\\d+)?)(ms|d|h|m|s)").findAll(value).toList()
    if (parts.isEmpty() || parts.joinToString("") { it.value } != value) return null
    val millis = parts.sumOf {
        it.groupValues[1].toDouble() * when (it.groupValues[2]) {
            "d" -> 86_400_000; "h" -> 3_600_000; "m" -> 60_000; "s" -> 1_000; else -> 1
        }
    }
    return if (millis.isFinite() && millis in 0.0..(365.0 * 86_400_000)) now + millis.toLong() else null
}

internal fun retryDeadline(headers: Headers, quota: RateLimitSnapshot, now: Long): Long? {
    headers["retry-after"]?.let { raw ->
        raw.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..31_536_000.0 }
            ?.let { return now + (it * 1000).toLong() }
        runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it.coerceAtLeast(now) }
    }
    return listOfNotNull(
        quota.resetRequestsAtMillis?.takeIf { quota.remainingRequests == 0L },
        quota.resetTokensAtMillis?.takeIf { quota.remainingTokens == 0L },
    ).maxOrNull()
}

private fun ResponseBody.readBounded(limit: Long): ByteArray {
    if (source().request(limit + 1)) error("Response too large")
    return bytes()
}

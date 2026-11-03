package com.superbot.sati.ai

import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal interface DeepSeekService : ApiKeyValidator {
    fun stream(key: String, input: String, history: List<ConversationTurn>): Flow<String>
}

/** Fixed production endpoint, no redirects, logging, disk cache, or generation retries. */
internal class DeepSeekClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).build(),
    private val baseUrl: HttpUrl = "https://api.deepseek.com/".toHttpUrl(),
) : DeepSeekService {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun validateKey(key: String) {
        requestFlow(request(key, "models"), validation = true) { response, emit ->
            val text = response.body.source().readUtf8Bounded(256 * 1024)
            val available = json.parseToJsonElement(text).jsonObject["data"]?.jsonArray
                ?.any { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == MODEL } == true
            if (!available) throw ReplyException(ReplyFailure.Service)
            emit("valid")
        }.first()
    }

    override fun stream(key: String, input: String, history: List<ConversationTurn>): Flow<String> {
        val messages = buildList {
            add(message("system", SatiInstructions.system))
            history.forEach {
                add(message("user", it.user))
                add(message("assistant", it.reply))
            }
            add(message("user", input))
        }
        val body = buildJsonObject {
            put("model", MODEL)
            put("messages", JsonArray(messages))
            put("stream", true)
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("max_tokens", 128)
            put("temperature", 0.6)
        }.toString().toRequestBody("application/json".toMediaType())
        return requestFlow(request(key, "chat/completions").newBuilder().post(body).build()) {
                response, emit ->
            var receivedContent = false
            var totalCharacters = 0
            val completed = readEvents(response.body.charStream().buffered()) { event ->
                if (event == "[DONE]") return@readEvents false
                val chunk = json.parseToJsonElement(event).jsonObject
                if ("error" in chunk) throw ReplyException(ReplyFailure.Service)
                val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return@readEvents true // Usage-only and empty choices are valid.
                val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                if (finish != null && finish !in setOf("stop", "length")) {
                    throw ReplyException(ReplyFailure.Generation)
                }
                val content = choice["delta"]?.jsonObject?.get("content")
                    ?.jsonPrimitive?.contentOrNull.orEmpty()
                if (content.isNotEmpty()) {
                    totalCharacters += content.length
                    if (totalCharacters > 16 * 1024) throw ReplyException(ReplyFailure.InvalidResponse)
                    receivedContent = true
                    emit(content)
                }
                true
            }
            if (!completed || !receivedContent) throw ReplyException(ReplyFailure.InvalidResponse)
        }
    }

    private fun request(key: String, path: String): Request {
        require(validApiKey(key))
        return Request.Builder().url(checkNotNull(baseUrl.resolve(path)))
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json, text/event-stream").build()
    }

    private fun requestFlow(
        request: Request,
        validation: Boolean = false,
        consume: (Response, (String) -> Unit) -> Unit,
    ): Flow<String> = callbackFlow {
        val call = client.newCall(request)
        if (validation) call.timeout().timeout(15, TimeUnit.SECONDS)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(ReplyException(ReplyFailure.Network))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw ReplyException(httpFailure(it.code))
                        consume(it) { value ->
                            if (trySendBlocking(value).isFailure) throw IOException("Canceled")
                        }
                    }
                    close()
                } catch (failure: ReplyException) {
                    close(failure)
                } catch (_: IOException) {
                    close(ReplyException(ReplyFailure.Network))
                } catch (_: Exception) {
                    close(ReplyException(ReplyFailure.InvalidResponse))
                }
            }
        })
        // Closing/canceling the collecting coroutine also interrupts blocked network reads.
        awaitClose { call.cancel() }
    }

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    companion object {
        const val MODEL = "deepseek-flash"
        internal fun httpFailure(code: Int): ReplyFailure = when (code) {
            401, 403 -> ReplyFailure.Authentication
            402 -> ReplyFailure.Balance
            429 -> ReplyFailure.RateLimit
            in 500..599 -> ReplyFailure.Service
            else -> ReplyFailure.InvalidResponse
        }
    }
}

/** SSE comments/keepalives, multiple data lines, and arbitrary network chunk boundaries. */
internal fun readEvents(reader: BufferedReader, onEvent: (String) -> Boolean): Boolean {
    val data = StringBuilder()
    while (true) {
        // Bound each line before allocating an unbounded String.
        val line = StringBuilder()
        while (true) {
            val character = reader.read()
            if (character == -1) return false // A truncated stream must never be spoken.
            if (character == '\n'.code) break
            line.append(character.toChar())
            if (line.length > 64 * 1024) throw ReplyException(ReplyFailure.InvalidResponse)
        }
        val value = line.toString().removeSuffix("\r")
        if (value.isEmpty()) {
            if (data.isNotEmpty()) {
                if (!onEvent(data.toString())) return true
                data.setLength(0)
            }
        } else if (value.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(value.removePrefix("data:").removePrefix(" "))
            if (data.length > 64 * 1024) throw ReplyException(ReplyFailure.InvalidResponse)
        }
    }
}

private fun okio.BufferedSource.readUtf8Bounded(limit: Long): String {
    if (request(limit + 1)) throw ReplyException(ReplyFailure.InvalidResponse)
    return readUtf8()
}

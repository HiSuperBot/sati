package com.superbot.sati.ai

import com.superbot.sati.speech.pcmWav
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GroqClientTest {
    @get:Rule val server = MockWebServer()
    private val now = 1_000_000L
    private fun service(quotas: MutableList<RateLimitSnapshot> = mutableListOf()) =
        GroqClient(baseUrl = server.url("/openai/v1/"), onQuota = { quotas += it }, clock = { now })
    private fun event(content: String) =
        "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\n"

    @Test
    fun validatesTheSharedKeyWithoutCallingAnyBillableEndpoint() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"whisper-large-v3-turbo\"}]}"))
        service().validateKey("test-key")
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/openai/v1/models", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun uploadsEnglishWavWithTheSelectedWhisperModelAndCapturesQuotaHeaders() = runBlocking {
        val quotas = mutableListOf<RateLimitSnapshot>()
        server.enqueue(MockResponse().setBody("{\"text\":\"Hello Mina\"}")
            .setHeader("x-ratelimit-limit-requests", "2000")
            .setHeader("x-ratelimit-remaining-requests", "1999"))
        val wave = pcmWav(listOf(ShortArray(320) { 100 }))
        assertEquals("Hello Mina", service(quotas).transcribe("test-key", SttModel.WhisperTurbo, wave))
        val request = server.takeRequest()
        assertEquals("/openai/v1/audio/transcriptions", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("whisper-large-v3-turbo"))
        assertTrue(body.contains("name=\"language\"\r\n\r\nen"))
        assertTrue(body.contains("speech.wav"))
        assertTrue(body.contains("RIFF"))
        assertEquals(1999L, quotas.single().remainingRequests)
        assertNull(quotas.single().remainingTokens)
    }

    @Test
    fun speechUsesTheSelectedVoiceAndAcceptsOnlyBoundedWavAudio() = runBlocking {
        val wave = pcmWav(listOf(ShortArray(320)))
        server.enqueue(MockResponse().setBody(Buffer().write(wave)))
        assertArrayEquals(wave, service().synthesize("test-key", "Hello!", OrpheusVoice.Daniel))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("canopylabs/orpheus-v1-english", body["model"]!!.jsonPrimitive.content)
        assertEquals("daniel", body["voice"]!!.jsonPrimitive.content)
        assertEquals("wav", body["response_format"]!!.jsonPrimitive.content)
        server.enqueue(MockResponse().setBody("not audio"))
        try {
            service().synthesize("test-key", "Hi", OrpheusVoice.Troy)
            fail("Non-WAV response")
        } catch (e: StageException) {
            assertEquals(PipelineStage.Tts, e.failure.stage)
            assertEquals(ReplyFailure.InvalidResponse, e.failure.reason)
        }
        try {
            service().synthesize("test-key", "x".repeat(201), OrpheusVoice.Troy)
            fail("Oversized speech")
        } catch (_: IllegalArgumentException) { }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun chatStreamsOnlyAnswerTextWithHistoryAndReasoningExcluded() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"reasoning\":\"private reasoning\"}}]}\n\n" +
                event("Hi") + event(" Mina!") + "data: [DONE]\n\n"))
        assertEquals(listOf("Hi", " Mina!"), service().stream(
            "test-key", AiModel.Groq20, "Hello", listOf(ConversationTurn("My name is Mina", "Hi."))).toList())
        val request = server.takeRequest()
        assertEquals("/openai/v1/chat/completions", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("openai/gpt-oss-20b", body["model"]!!.jsonPrimitive.content)
        assertEquals("false", body["include_reasoning"]!!.jsonPrimitive.content)
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(listOf("system", "user", "assistant", "user"),
            body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test
    fun rateLimitsIdentifyTheFailedStageAndNeverRetryAutomatically() = runBlocking {
        for (stage in PipelineStage.entries) {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "12")
                .setBody("{\"error\":{\"message\":\"test-key private server text\"}}"))
            try {
                when (stage) {
                    PipelineStage.Stt -> service().transcribe("test-key", SttModel.Whisper, byteArrayOf(1))
                    PipelineStage.Chat -> service().stream("test-key", AiModel.Groq120, "Hi", emptyList()).toList()
                    PipelineStage.Tts -> service().synthesize("test-key", "Hi", OrpheusVoice.Troy)
                }
                fail("Expected rate limit")
            } catch (e: StageException) {
                assertEquals(stage, e.failure.stage)
                assertEquals(CloudProvider.Groq, e.failure.provider)
                assertEquals(ReplyFailure.RateLimit, e.failure.reason)
                assertEquals(now + 12_000, e.failure.retryAtMillis)
                assertFalse(e.message!!.contains("test-key"))
                assertFalse(e.message!!.contains("private server"))
            }
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun distinguishesAuthenticationAccountAccessAndModelAvailability() = runBlocking {
        val cases = listOf(
            Triple(401, "", ReplyFailure.Authentication),
            Triple(402, "", ReplyFailure.Balance),
            Triple(403, "model_terms_required", ReplyFailure.Service),
            Triple(404, "model_not_found", ReplyFailure.Service),
            Triple(503, "", ReplyFailure.Service),
        )
        for ((status, code, expected) in cases) {
            server.enqueue(MockResponse().setResponseCode(status)
                .setBody("{\"error\":{\"code\":\"$code\"}}"))
            try {
                service().validateKey("test-key")
                fail("Expected API failure")
            } catch (e: StageException) {
                assertEquals(expected, e.failure.reason)
                if (status == 403) assertEquals(FailureHint.AccessRequired, e.failure.hint)
                if (status == 404) assertEquals(FailureHint.ModelUnavailable, e.failure.hint)
            }
        }
    }

    @Test
    fun redirectsMalformedJsonAndTruncatedStreamsAreRejected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere")))
        try { service().validateKey("test-key"); fail("Redirect") }
        catch (e: StageException) { assertEquals(ReplyFailure.InvalidResponse, e.failure.reason) }
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("broken"))
        try { service().transcribe("test-key", SttModel.Whisper, byteArrayOf(1)); fail("Malformed") }
        catch (e: StageException) { assertEquals(ReplyFailure.InvalidResponse, e.failure.reason) }
        for (body in listOf(event("unfinished"), "data: [DONE]\n\n", "data: invalid\n\n")) {
            server.enqueue(MockResponse().setBody(body))
            try { service().stream("test-key", AiModel.Groq20, "Hi", emptyList()).toList(); fail("Incomplete") }
            catch (e: StageException) { assertEquals(ReplyFailure.InvalidResponse, e.failure.reason) }
        }
    }

    @Test
    fun cancellationInterruptsAnAudioRequestWithoutAnAutomaticRetry() = runBlocking {
        val failed = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: java.io.IOException) { failed.countDown() }
        }).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = GroqClient(client = http, baseUrl = server.url("/"))
        val job = async { client.transcribe("test-key", SttModel.Whisper, byteArrayOf(1)) }
        yield()
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun quotaParserUsesOnlyReportedDimensionsAndTheLongestExhaustedReset() {
        val headers = Headers.Builder()
            .add("x-ratelimit-remaining-requests", "0")
            .add("x-ratelimit-remaining-tokens", "0")
            .add("x-ratelimit-reset-requests", "1h2m3.5s")
            .add("x-ratelimit-reset-tokens", "7.66s").build()
        val quota = rateSnapshot(headers, "model", now)
        assertEquals(now + 3_723_500, quota.resetRequestsAtMillis)
        assertEquals(now + 7_660, quota.resetTokensAtMillis)
        assertEquals(quota.resetRequestsAtMillis, retryDeadline(headers, quota, now))
        assertNull(rateSnapshot(Headers.Builder().build(), "model", now).remainingRequests)
        assertNull(parseReset("unknown", now))
        assertNull(parseReset("-10s", now))
        assertNull(parseReset("1h garbage", now))
        assertNull(retryDeadline(Headers.Builder().build(),
            RateLimitSnapshot("model", now), now))
    }
}

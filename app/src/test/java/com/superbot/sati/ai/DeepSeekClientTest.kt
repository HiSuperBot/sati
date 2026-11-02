package com.superbot.sati.ai

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DeepSeekClientTest {
    @get:Rule val server = MockWebServer()
    private fun client() = DeepSeekClient(baseUrl = server.url("/"))
    private fun event(content: String) =
        "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"},\"finish_reason\":null}]}\n\n"
    private fun response(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream").setBody(body)

    @Test
    fun validatesWithModelsEndpointAndNeverGeneratesAReply() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"deepseek-flash\"}]}"))
        client().validateKey("test-key")
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/models", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun streamsOnlyAnswerTextWithSystemInstructionsAndBoundedHistory() = runBlocking {
        server.enqueue(response(
            ": keepalive\n\ndata: {\"choices\":[{\"delta\":{\"reasoning_content\":\"secret thinking\"}}]}\n\n" +
                event("Hi") + event(" Mina!") +
                "data: {\"choices\":[],\"usage\":{}}\n\ndata: [DONE]\n\n",
        ))
        val output = client().stream("test-key", "Hello", listOf(ConversationTurn("My name is Mina", "Hi Mina."))).toList()
        assertEquals(listOf("Hi", " Mina!"), output)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/chat/completions", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("128", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "user"),
            messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(SatiInstructions.system, messages.first()["content"]!!.jsonPrimitive.content)
        assertEquals("Hello", messages.last()["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun keyValidationRejectsMissingFlashAndMalformedData() = runBlocking {
        for (body in listOf("{\"data\":[{\"id\":\"other-model\"}]}", "invalid json")) {
            server.enqueue(MockResponse().setBody(body))
            try {
                client().validateKey("test-key")
                fail("Invalid validation response")
            } catch (error: ReplyException) {
                assertTrue(error.failure in setOf(ReplyFailure.Service, ReplyFailure.InvalidResponse))
                assertFalse(error.message.orEmpty().contains("test-key"))
            }
        }
    }

    @Test
    fun mapsApiErrorsWithoutRetryingOrExposingServerMessages() = runBlocking {
        val cases = mapOf(401 to ReplyFailure.Authentication, 402 to ReplyFailure.Balance,
            429 to ReplyFailure.RateLimit, 503 to ReplyFailure.Service)
        for ((code, expected) in cases) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("private provider error"))
            try {
                client().stream("test-key", "Hello", emptyList()).toList()
                fail("Expected HTTP failure")
            } catch (error: ReplyException) {
                assertEquals(expected, error.failure)
                assertFalse(error.message.orEmpty().contains("private"))
            }
        }
        assertEquals(cases.size, server.requestCount)
    }

    @Test
    fun truncatedEmptyAndMalformedStreamsNeverCompleteAsSuccessfulReplies() = runBlocking {
        for (body in listOf(event("unfinished"), "data: [DONE]\n\n", "data: broken\n\ndata: [DONE]\n\n")) {
            server.enqueue(response(body))
            try {
                client().stream("test-key", "Hello", emptyList()).toList()
                fail("Incomplete reply must fail")
            } catch (error: ReplyException) {
                assertEquals(ReplyFailure.InvalidResponse, error.failure)
            }
        }
    }

    @Test
    fun neverFollowsRedirectsWithTheApiKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")))
        try {
            client().validateKey("test-key")
            fail("Redirect must fail")
        } catch (error: ReplyException) {
            assertEquals(ReplyFailure.InvalidResponse, error.failure)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun cancellationInterruptsAStalledNetworkCall() = runBlocking {
        val failed = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: java.io.IOException) { failed.countDown() }
        }).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val service = DeepSeekClient(http, server.url("/"))
        val job = async { service.stream("test-key", "Hello", emptyList()).toList() }
        // Let the flow enqueue its request before blocking for the server.
        kotlinx.coroutines.yield()
        assertNotNull(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            server.takeRequest(5, TimeUnit.SECONDS)
        })
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals(1, server.requestCount)
    }
}

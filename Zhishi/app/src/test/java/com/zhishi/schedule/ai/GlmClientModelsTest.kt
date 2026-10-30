package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.GlmConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GlmClientModelsTest {
    private val draft = GlmConfig(baseUrl = "https://example.test/v1", apiKey = "  draft-key  ", model = "", visionModel = "")

    @Test
    fun `lists models using draft credentials without requiring a selected model`() = runBlocking {
        val fixture = Fixture("""{"object":"list","data":[{"id":"text-pro","name":"Text Pro"},{"id":"vision-pro"},{"id":"text-pro"},{"id":"  text-lite  "},{"id":null,"name":"Not a callable id"},{"name":"Display name without id"}]}""")
        val models = fixture.client.listModels(draft)
        assertEquals(listOf("text-lite", "text-pro", "vision-pro"), models.map { it.id })
        assertEquals("Text Pro", models.first { it.id == "text-pro" }.displayName)
        assertTrue(models.all { it.supportsImageInput == null })
        val request = fixture.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://example.test/v1/models", request.url.toString())
        assertEquals("Bearer draft-key", request.header("Authorization"))
        assertNull(request.body)
    }

    @Test
    fun `normalizes root trailing slash and full API endpoints while preserving query`() = runBlocking {
        val variants = mapOf(
            "https://example.test" to "https://example.test/models",
            " https://example.test/v1/ " to "https://example.test/v1/models",
            "https://example.test/v1/models" to "https://example.test/v1/models",
            "https://example.test/v1/chat/completions/" to "https://example.test/v1/models",
            "https://example.test/api/paas/v4" to "https://example.test/api/paas/v4/models",
            "https://example.test/v1/chat/completions?region=cn#ignored" to "https://example.test/v1/models?region=cn"
        )
        variants.forEach { (base, expected) ->
            val fixture = Fixture("""{"data":[]}""")
            fixture.client.listModels(draft.copy(baseUrl = base))
            assertEquals(expected, fixture.requests.single().url.toString())
        }
    }

    @Test
    fun `image capability comes from metadata rather than names and display name never replaces id`() = runBlocking {
        val fixture = Fixture("""{"data":[
            {"id":"text-only","name":"Friendly name","input_modalities":["text"]},
            {"id":"multimodal","input_modalities":["text","image"]},
            {"id":"name-contains-vision"},
            {"id":"empty-capability","input_modalities":[]}
        ]}""")
        val models = fixture.client.listModels(draft).associateBy { it.id }
        assertEquals(false, models.getValue("text-only").supportsImageInput)
        assertEquals("Friendly name", models.getValue("text-only").displayName)
        assertEquals(true, models.getValue("multimodal").supportsImageInput)
        assertNull(models.getValue("name-contains-vision").supportsImageInput)
        assertNull(models.getValue("empty-capability").supportsImageInput)
    }

    @Test
    fun `accepts string lists and models wrappers while ignoring malformed entries`() = runBlocking {
        val strings = Fixture("""["a", "a", "  b ", "", null, false, 42]""").client.listModels(draft)
        assertEquals(listOf("a", "b"), strings.map { it.id })
        val wrapped = Fixture("""{"models":[{"id":"a"},{"model":"b"},{"name":"c"},{},null]}""").client.listModels(draft)
        assertEquals(listOf("a", "b", "c"), wrapped.map { it.id })
        assertTrue(Fixture("""{"data":[]}""").client.listModels(draft).isEmpty())
    }

    @Test
    fun `invalid payloads and unsupported list endpoints never produce fabricated choices`() = runBlocking {
        listOf("<html>login</html>", "{}", """{"data":{}}""", """{"data":[{},null,12]}""").forEach { body ->
            val failure = runCatching { Fixture(body).client.listModels(draft) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains("模型"))
        }
        val expected = mapOf(401 to "API Key", 403 to "无权", 404 to "未提供", 405 to "未提供", 501 to "未提供", 429 to "过于频繁", 500 to "HTTP 500")
        expected.forEach { (status, message) ->
            val failure = runCatching { Fixture("private server detail draft-key", status).client.listModels(draft) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains(message))
            assertFalse(failure.message!!.contains("draft-key"))
        }
    }

    @Test
    fun `missing credentials and malformed URL fail without sending a request`() = runBlocking {
        val fixture = Fixture("""{"data":[]}""")
        listOf(draft.copy(apiKey = " "), draft.copy(baseUrl = ""), draft.copy(baseUrl = "not a URL")).forEach {
            assertTrue(runCatching { fixture.client.listModels(it) }.isFailure)
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `cancelling stale model discovery cancels its underlying HTTP call`() = runBlocking {
        val started = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val client = GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call())
            check(release.await(5, TimeUnit.SECONDS)) { "Test did not release model request" }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("""{"data":[{"id":"stale"}]}""".toResponseBody()).build()
        }.build())
        try {
            val request = async { client.listModels(draft) }
            val call = withTimeout(5_000) { started.await() }
            request.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
        }
    }

    private class Fixture(body: String, status: Int = 200) {
        val requests = mutableListOf<Request>()
        val client = GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(body.toResponseBody()).build()
        }.build())
    }
}

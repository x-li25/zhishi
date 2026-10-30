package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.ChatMessage
import com.zhishi.schedule.domain.ChatRole
import com.zhishi.schedule.domain.GlmConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GlmClientCancellationTest {
    private val config = GlmConfig(baseUrl = "https://example.test", apiKey = "test")
    private val history = listOf(ChatMessage(1, ChatRole.USER, "请查询日程"))
    private val now = Instant.parse("2026-10-24T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val reply = """{"choices":[{"message":{"content":"{\"reply\":\"迟到的回复\",\"actions\":[]}"}}]}"""

    @Test
    fun `cancelling generation stops HTTP immediately without waiting for server response`() = runBlocking {
        val started = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val client = GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call())
            check(release.await(5, TimeUnit.SECONDS))
            response(chain.request(), reply.toResponseBody())
        }.build())
        try {
            val request = async { client.chat(history, config, now, zone) }
            val call = withTimeout(3_000) { started.await() }
            withTimeout(3_000) { request.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertTrue(request.isCancelled)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `cancellation also cancels HTTP while response body is being read and closes late body`() = runBlocking {
        val bodyStarted = CompletableDeferred<Call>()
        val bodyClosed = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val client = GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            val source = object : Source {
                private val data = Buffer().writeUtf8(reply)
                override fun read(sink: Buffer, byteCount: Long): Long {
                    bodyStarted.complete(chain.call())
                    check(release.await(5, TimeUnit.SECONDS))
                    return data.read(sink, byteCount)
                }
                override fun timeout() = Timeout.NONE
                override fun close() { bodyClosed.complete(Unit) }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
                override fun source() = source
            }
            response(chain.request(), body)
        }.build())
        try {
            val request = async { client.chat(history, config, now, zone) }
            val call = withTimeout(3_000) { bodyStarted.await() }
            withTimeout(3_000) { request.cancelAndJoin() }
            assertTrue(call.isCanceled())
            release.countDown()
            withTimeout(3_000) { bodyClosed.await() }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `stopping during calendar read prevents next model round`() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val payload = """{"choices":[{"message":{"content":"{\"calendar_requests\":[{\"week\":6}]}"}}]}"""
        val client = GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            response(chain.request(), payload.toResponseBody())
        }.build())
        val request = async {
            client.chat(history, config, now, zone, readCalendar = { _, _ ->
                reading.complete(Unit)
                awaitCancellation()
            })
        }
        withTimeout(3_000) { reading.await() }
        withTimeout(3_000) { request.cancelAndJoin() }
        assertEquals(1, calls.get())
    }

    @Test
    fun `stopping during lazy image loading prevents uploading original picture`() = runBlocking {
        val loading = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val payload = """{"choices":[{"message":{"content":"{\"image_request\":{\"reason\":\"行列关系不明\"}}"}}]}"""
        val client = GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            response(chain.request(), payload.toResponseBody())
        }.build())
        val request = async {
            client.chat(history, config, now, zone, imageText = "日期 活动",
                loadImageDataUrl = { loading.complete(Unit); awaitCancellation() })
        }
        withTimeout(3_000) { loading.await() }
        withTimeout(3_000) { request.cancelAndJoin() }
        assertEquals(1, calls.get())
    }

    private fun response(request: okhttp3.Request, body: ResponseBody) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
}

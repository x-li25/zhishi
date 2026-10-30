package com.zhishi.schedule.data

import android.webkit.CookieManager
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CampusHttpTransportTest {
    @Test fun `homework POST allows only metadata list endpoints and refuses submission and redirects`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("POST", chain.request().method)
            assertEquals("webvpn.tsinghua.edu.cn", chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                .header("Location", "https://evil.org/").body("".toResponseBody()).build()
        }.build()
        val transport = CampusHttpTransport(CookieManager.getInstance(), "fixture-agent", client)
        assertTrue(runCatching { transport.postForm("${CampusUrls.LEARN}/b/wlxt/kczy/zy/student/zyListWj?_csrf=fixture", mapOf("aoData" to "[]")) }.isFailure)
        assertEquals(1, calls)
        assertTrue(runCatching { transport.postForm("${CampusUrls.LEARN}/b/wlxt/kczy/zy/student/tjzy", mapOf("aoData" to "[]")) }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun `clearing or switching school login invalidates an existing HTTP reader`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("fixture".toResponseBody()).build()
        }.build()
        val transport = CampusHttpTransport(CookieManager.getInstance(), "fixture-agent", client)
        CampusSession.invalidate()
        assertTrue(runCatching { transport.get(CampusUrls.LEARN_HOME) }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun `campus cookies are only sent to validated VPN and never follow external redirects`() = runBlocking {
        val cookies = CookieManager.getInstance()
        cookies.setCookie(CampusUrls.VPN, "session=private-fixture; Secure; Path=/")
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                .header("Location", "https://evil.org/collect").body("".toResponseBody()).build()
        }.build()
        val transport = CampusHttpTransport(cookies, "same-browser-agent", client)
        assertTrue(runCatching { transport.get(CampusUrls.COOKIE) }.isFailure)
        assertEquals(1, requests.size)
        assertEquals("webvpn.tsinghua.edu.cn", requests.single().url.host)
        assertTrue(requests.single().header("Cookie").orEmpty().contains("session=private-fixture"))
        assertEquals("same-browser-agent", requests.single().header("User-Agent"))
        assertNull(requests.single().header("Authorization"))
        assertTrue(runCatching { transport.get("https://evil.org/") }.isFailure)
        assertEquals(1, requests.size)
    }

    @Test fun `stopping while reading response body cancels underlying request`() = runBlocking {
        val started = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
                override fun source() = object : Source {
                    override fun timeout() = Timeout.NONE
                    override fun close() {}
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        started.complete(chain.call())
                        check(release.await(5, TimeUnit.SECONDS))
                        throw IOException("fixture cancelled")
                    }
                }.buffer()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        val transport = CampusHttpTransport(CookieManager.getInstance(), "fixture-agent", client)
        val job = launch { transport.get(CampusUrls.COOKIE); fail("cancelled read must not complete") }
        try {
            val call = withTimeout(5000) { started.await() }
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally { release.countDown(); job.cancel() }
    }

    @Test fun `GBK school responses decode correctly without changing actual times`() = runBlocking {
        val body = "概率论 09:50–12:15".toByteArray(charset("GB18030"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        assertEquals("概率论 09:50–12:15", CampusHttpTransport(CookieManager.getInstance(), "fixture-agent", client).get(CampusUrls.COOKIE))
    }
}

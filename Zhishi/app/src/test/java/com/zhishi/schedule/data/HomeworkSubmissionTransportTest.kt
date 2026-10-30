package com.zhishi.schedule.data

import android.webkit.CookieManager
import com.zhishi.schedule.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeworkSubmissionTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val endpoint = "${CampusUrls.LEARN}/b/wlxt/kczy/zy/student/tjzy?_csrf=fixture-token"

    @Test fun `submission multipart contains reviewed text file and preservation flag without AI credentials`() = runBlocking {
        val file = temporary.newFile("report.pdf").apply { writeText("fixture-file-content") }
        var sent: Request? = null
        var content = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            sent = request
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            content = buffer.readUtf8()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"result":"success"}""".toResponseBody()).build()
        }.build()
        val payload = HomeworkSubmissionPayload("student-assignment", "<p>正文</p>", HomeworkUpload(file, "report.pdf", "application/pdf", file.length()))
        assertEquals("""{"result":"success"}""", CampusHttpTransport(CookieManager.getInstance(), "fixture-agent", client).submitHomework(endpoint, payload))
        val request = requireNotNull(sent)
        assertEquals("POST", request.method)
        assertEquals("webvpn.tsinghua.edu.cn", request.url.host)
        assertNull(request.header("Authorization")); assertTrue(request.body!!.isOneShot())
        val boundary = request.body!!.contentType()!!.parameter("boundary")!!
        val parts = content.split("--$boundary").mapNotNull { part ->
            Regex("name=\"([^\"]+)\"").find(part)?.groupValues?.get(1)?.let { name ->
                name to part.substringAfter("\r\n\r\n").removeSuffix("\r\n")
            }
        }.toMap()
        assertEquals("student-assignment", parts["xszyid"])
        assertEquals("<p>正文</p>", parts["zynr"])
        assertEquals("0", parts["isDeleted"])
        assertEquals("fixture-file-content", parts["fileupload"])
        assertTrue(content.contains("filename=\"report.pdf\""))
    }

    @Test fun `submission never follows redirects or automatically retries an uncertain network failure`() = runBlocking {
        listOf(false, true).forEach { redirect ->
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                if (!redirect) throw IOException("fixture connection interrupted")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                    .header("Location", "https://evil.org/collect").body("".toResponseBody()).build()
            }.build()
            val transport = CampusHttpTransport(CookieManager.getInstance(), "fixture-agent", client)
            val error = runCatching { transport.submitHomework(endpoint, HomeworkSubmissionPayload("a", "正文", null)) }.exceptionOrNull()
            assertTrue(error is HomeworkSubmissionUncertain)
            assertEquals(1, calls)
        }
    }

    @Test fun `foreign and non submission destinations are rejected before any document leaves device`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { calls++; error("must not connect") }.build()
        val transport = CampusHttpTransport(CookieManager.getInstance(), "fixture-agent", client)
        listOf(endpoint.replace("webvpn.tsinghua.edu.cn", "evil.org"), endpoint.replace("/tjzy", "/delete"), endpoint.substringBefore('?')).forEach {
            assertTrue(runCatching { transport.submitHomework(it, HomeworkSubmissionPayload("a", "text", null)) }.isFailure)
        }
        assertEquals(0, calls)
    }
}

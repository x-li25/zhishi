package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GlmClientHomeworkTest {
    private val now = Instant.parse("2026-10-28T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val config = GlmConfig(baseUrl = "https://example.test", apiKey = "test")
    private val answer = """{"reply":"概率论作业未提交","actions":[],"questions":[]}"""
    private fun report(query: HomeworkQuery) = HomeworkSnapshot("2026-1", "秋季", now, query, listOf(
        CourseHomework("1", "c", "概率论", "第一次作业", HomeworkStatus.UNSUBMITTED, Instant.parse("2026-10-29T15:59:00Z"), onlineSubmission = true)), true)

    @Test fun `homework tool reads current school data without calendar access or executing proposed action`() = runBlocking {
        val fixture = Fixture("""{"homework_requests":[{"status":"unsubmitted","course":"概率论"}],"actions":[{"type":"delete_event","event_id":10}]}""", answer)
        var reads = 0
        val result = fixture.client.chat(listOf(ChatMessage(1, ChatRole.USER, "哪些作业没交")), config, now, zone,
            readCalendar = { _, _ -> fail("homework query should not read calendar"); emptyList() },
            readHomework = { query -> reads++; assertEquals("概率论", query.course); report(query) })
        assertEquals(1, reads); assertTrue(result.events.isEmpty())
        val text = fixture.requests.last().toString()
        assertTrue(text.contains("homework_results")); assertTrue(text.contains("第一次作业"))
        assertFalse(text.contains("submittedContent")); assertFalse(text.contains("Cookie"))
    }

    @Test fun `personal completion query forces fresh lookup if model tries to answer directly`() = runBlocking {
        val fixture = Fixture("""{"reply":"都交了","actions":[]}""", answer)
        var reads = 0
        fixture.client.chat(listOf(ChatMessage(1, ChatRole.USER, "我的作业完成情况")), config, now, zone,
            readHomework = { query -> reads++; report(query) })
        assertEquals(1, reads); assertEquals(2, fixture.requests.size)
        assertTrue(fixture.requests.last().toString().contains("homework_results"))
    }

    @Test fun `unrelated conversation does not query school records`() = runBlocking {
        val fixture = Fixture("""{"reply":"你好","actions":[]}""")
        var reads = 0
        fixture.client.chat(listOf(ChatMessage(1, ChatRole.USER, "你好")), config, now, zone,
            readHomework = { query -> reads++; report(query) })
        assertEquals(0, reads); assertEquals(1, fixture.requests.size)
    }

    @Test fun `failed homework lookup stays unknown with warning and no calendar mutation`() = runBlocking {
        val fixture = Fixture("""{"homework_requests":[{}]}""", """{"reply":"暂时无法读取，请重新登录","actions":[]}""")
        val result = fixture.client.chat(listOf(ChatMessage(1, ChatRole.USER, "看看作业")), config, now, zone,
            readHomework = { error("secret school response must never be sent to AI") })
        assertTrue(result.events.isEmpty()); assertTrue(result.warnings.isNotEmpty())
        assertFalse(fixture.requests.last().toString().contains("secret school response"))
    }

    private class Fixture(vararg val replies: String) {
        val requests = mutableListOf<JsonObject>()
        val client = GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val payload = Json.parseToJsonElement(replies[minOf(requests.lastIndex, replies.lastIndex)])
            val body = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                put("message", buildJsonObject { put("content", payload.toString()) })
            }) }) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toString().toResponseBody()).build()
        }.build())
    }
}

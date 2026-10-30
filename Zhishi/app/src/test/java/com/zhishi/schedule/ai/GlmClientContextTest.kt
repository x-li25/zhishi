package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GlmClientContextTest {
    private val now = Instant.parse("2026-10-24T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val config = GlmConfig(baseUrl = "https://example.test", apiKey = "test")
    private val answer = """{"reply":"收到","actions":[],"questions":[]}"""

    @Test fun `long OCR history is reduced without sending both raw and analyzed versions`() = runBlocking {
        val history = (1L..30L).map { id ->
            if (id % 2 == 1L) ChatMessage(id, ChatRole.USER, "通知$id", imageAnalysis = "第二项：周五14点数学课$id",
                imageText = "旧OCR独有标记$id" + "无关长原文".repeat(2500))
            else ChatMessage(id, ChatRole.ASSISTANT, "已识别，请确认")
        } + ChatMessage(31, ChatRole.USER, "旧图第二项安排在几点？")
        val fixture = Fixture(answer)
        fixture.client.chat(history, config, now, zone)
        val request = fixture.requests.single().toString()
        val legacyHistoryCharacters = history.takeLast(20).sumOf { it.text.length + (it.imageText?.length ?: 0) + (it.imageAnalysis?.length ?: 0) }
        assertFalse(request.contains("旧OCR独有标记"))
        assertTrue(request.contains("周五14点数学课"))
        assertTrue(request.length < legacyHistoryCharacters / 5)
        assertTrue(fixture.requests.single()["messages"]!!.jsonArray.size <= 8)
        println("Compression fixture: legacy history chars=$legacyHistoryCharacters, new full request chars=${request.length}")
    }

    @Test fun `request can fetch an omitted exact old message and defer destructive actions until fresh calendar data`() = runBlocking {
        val fixture = Fixture("""{"context_requests":[{"kind":"history","message_id":1,"field":"text"}],"actions":[{"type":"delete_event","event_id":42}]}""", answer)
        val history = listOf(ChatMessage(1, ChatRole.USER, "具体内容：" + "内容".repeat(2000) + "原文尾部保留")) +
            (2L..9L).map { ChatMessage(it, ChatRole.USER, "插入话题$it") } + ChatMessage(10, ChatRole.USER, "之前那段具体写了什么")
        var calendarReads = 0
        val result = fixture.client.chat(history, config, now, zone, readCalendar = { _, _ -> calendarReads++; emptyList() })
        assertTrue(result.events.isEmpty())
        assertEquals(0, calendarReads)
        val data = fixture.lastData()["context_results"]!!.jsonArray.single().jsonObject
        assertEquals(1L, data["message_id"]!!.jsonPrimitive.long)
        assertTrue(data["text"]!!.jsonPrimitive.content.contains("具体内容"))
        assertFalse(data["complete"]!!.jsonPrimitive.boolean)
        assertTrue(data.containsKey("next_offset"))
        assertEquals(20, result.totalTokens)
    }

    @Test fun `academic-only lookup resolves locally without reading calendar or weather`() = runBlocking {
        val fixture = Fixture("""{"context_requests":[{"kind":"academic","week":7,"weekday":3,"period":2}]}""", answer)
        var reads = 0
        fixture.client.chat(listOf(ChatMessage(1, ChatRole.USER, "第七周周三第二大节是什么时候")), config, now, zone,
            readCalendar = { _, _ -> reads++; emptyList() })
        assertEquals(0, reads)
        val data = fixture.lastData()["context_results"]!!.jsonArray.single().jsonObject
        assertEquals("2026-10-28T09:50+08:00", data["start"]!!.jsonPrimitive.content)
        assertEquals("2026-10-28T12:15+08:00", data["end"]!!.jsonPrimitive.content)
    }

    @Test fun `history lookup round limit is bounded and ignores actions alongside unfinished reads`() = runBlocking {
        val request = """{"context_requests":[{"kind":"history","message_id":1}],"actions":[{"type":"delete_event","event_id":999}]}"""
        val fixture = Fixture(request, request, request)
        val result = fixture.client.chat(listOf(ChatMessage(1, ChatRole.USER, "你好")), config, now, zone)
        assertEquals(3, fixture.requests.size)
        assertTrue(result.summary.contains("上限"))
        assertTrue(result.events.isEmpty())
    }

    @Test fun `new picture attaches to correct user when a memory message precedes recent history`() = runBlocking {
        val history = (1L..9L).map { ChatMessage(it, ChatRole.USER, "此前对话$it") } +
            ChatMessage(10, ChatRole.USER, "只安排新图里的会议", imageFileName = "new-image.jpg")
        val fixture = Fixture("""{"reply":"缺日期","image_analysis":"图中9点会议，日期待确认","actions":[]}""")
        val url = "data:image/jpeg;base64,aW1hZ2U="
        fixture.client.chat(history, config, now, zone, imageDataUrl = url)
        val messages = fixture.requests.single()["messages"]!!.jsonArray
        val pictures = messages.filter { it.jsonObject["content"] is JsonArray }
        assertEquals(1, pictures.size)
        val content = pictures.single().jsonObject["content"]!!.jsonArray
        assertTrue(content.first().jsonObject["text"]!!.jsonPrimitive.content.contains("只安排新图里的会议"))
        assertEquals(url, content.last().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun `current long OCR is replaced by one fact summary for later verification without another picture upload`() = runBlocking {
        val fixture = Fixture(
            """{"image_analysis":"仅一场会议：2026-11-10 09:00至10:00","calendar_requests":[{"start_date":"2026-11-10","end_date":"2026-11-10"}]}""", answer)
        val raw = "仅第一轮OCR标记" + "无关说明".repeat(4000)
        fixture.client.chat(listOf(ChatMessage(1, ChatRole.USER, "按图安排会议")), config, now, zone, imageText = raw,
            loadImageDataUrl = { error("不得上传图片") }, readCalendar = { _, _ -> emptyList() })
        assertTrue(fixture.requests.first().toString().contains("仅第一轮OCR标记"))
        val second = fixture.requests.last().toString()
        assertFalse(second.contains("仅第一轮OCR标记"))
        assertEquals(1, "仅一场会议".toRegex().findAll(second).count())
        assertFalse(second.contains("image_url"))
    }

    @Test fun `archived preference is provided with source while original already gone returns explicit incomplete`() = runBlocking {
        val memory = ConversationMemory().merge(listOf(ChatMessage(1, ChatRole.USER, "记住每周三晚上合唱团", timestamp = now.minusSeconds(86400).toEpochMilli())))
        val fixture = Fixture("""{"context_requests":[{"kind":"history","message_id":1}]}""", answer)
        val result = fixture.client.chat(listOf(ChatMessage(200, ChatRole.USER, "之前说的合唱团呢？")), config, now, zone, memory = memory)
        assertTrue(fixture.requests.first().toString().contains("每周三晚上合唱团"))
        val data = fixture.lastData()["context_results"]!!.jsonArray.single().jsonObject
        assertFalse(data["complete"]!!.jsonPrimitive.boolean)
        assertTrue(data.containsKey("error"))
        assertTrue(result.warnings.any { it.contains("只有简要记忆") })
    }

    private class Fixture(vararg payloads: String) {
        val requests = mutableListOf<JsonObject>()
        val client = GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            check(requests.size <= payloads.size) { "Unexpected extra model request" }
            val response = buildJsonObject {
                put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject {
                    put("content", payloads[requests.lastIndex])
                }) }) })
                put("usage", buildJsonObject { put("total_tokens", 10) })
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody()).build()
        }.build())
        fun lastData() = Json.parseToJsonElement(requests.last()["messages"]!!.jsonArray.last()
            .jsonObject["content"]!!.jsonPrimitive.content).jsonObject
    }
}

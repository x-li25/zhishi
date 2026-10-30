package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.ChatMessage
import com.zhishi.schedule.domain.ChatRole
import com.zhishi.schedule.domain.GlmConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GlmClientImageTest {
    private val config = GlmConfig(baseUrl = "https://example.test", apiKey = "test", model = "glm-5.3-flashx")
    private val image = "data:image/jpeg;base64,aW1hZ2U="
    private val now = Instant.parse("2026-10-24T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val create = """{"type":"create_event","title":"会议","start":"2026-11-10T09:00:00+08:00","end":"2026-11-10T10:00:00+08:00","location":"六教A414"}"""

    @Test
    fun `vision sees image once then calendar verification uses text facts without reupload`() = runBlocking {
        val fixture = Fixture(
            """{"image_analysis":"仅一场会议：11月10日09:00至10:00，六教A414；年份按用户要求为2026年","calendar_requests":[{"start_date":"2026-11-10","end_date":"2026-11-10"}]}""",
            """{"reply":"请确认会议安排","actions":[$create],"questions":[]}"""
        )
        val history = listOf(ChatMessage(1, ChatRole.USER, "这些活动是2026年的"),
            ChatMessage(2, ChatRole.USER, "只安排图片中的会议", imageFileName = "local-image.jpg"))
        val dates = mutableListOf<Pair<LocalDate, LocalDate>>()
        val result = fixture.client.chat(history, config, now, zone, imageDataUrl = image,
            readCalendar = { start, end -> dates += start to end; emptyList() })
        assertEquals(listOf(LocalDate.parse("2026-11-10") to LocalDate.parse("2026-11-10")), dates)
        assertEquals("六教A414", result.events.single().location)
        assertEquals(30, result.totalTokens)
        assertTrue(result.imageAnalysis!!.contains("六教A414"))
        assertEquals(2, fixture.requests.size) // 无独立OCR调用。
        fixture.requests.forEachIndexed { index, request ->
            assertEquals("glm-5.3-flashx", request["model"]!!.jsonPrimitive.content)
            assertEquals(1.0, request["temperature"]!!.jsonPrimitive.double, 0.0)
            val messages = request["messages"]!!.jsonArray
            val systemPrompt = messages.first().jsonObject["content"]!!.jsonPrimitive.content
            assertTrue(systemPrompt.contains("第1大节：08:00–09:35"))
            assertTrue(systemPrompt.contains("第2大节：09:50–12:15"))
            assertTrue(systemPrompt.contains("第6大节：19:20–20:55"))
            assertTrue(systemPrompt.contains("已知清华大节编号时，直接使用上述固定作息时刻"))
            assertTrue(messages[1].jsonObject["content"]!!.jsonPrimitive.content.contains("2026年"))
            if (index == 0) {
                val content = messages[2].jsonObject["content"]!!.jsonArray
                assertTrue(content.first().jsonObject["text"]!!.jsonPrimitive.content.contains("只安排图片中的会议"))
                assertEquals(image, content.last().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            } else {
                assertFalse(request.toString().contains("image_url"))
                assertTrue(request.toString().contains("六教A414"))
            }
            assertFalse(request.toString().contains("只执行OCR文字识别"))
            assertFalse(request.toString().contains("local-image.jpg"))
        }
    }

    @Test
    fun `only current user message gets image while previous picture meaning stays in history`() = runBlocking {
        val fixture = Fixture("""{"reply":"还需要第二张课表的节次时刻","image_analysis":"第二张图有两节课，但未标注节次时刻","actions":[],"questions":[]}""")
        val history = listOf(
            ChatMessage(1, ChatRole.USER, "第一张通知", imageFileName = "old.jpg", imageAnalysis = "第一张图：周二9点会议"),
            ChatMessage(2, ChatRole.ASSISTANT, "第一张已识别"),
            ChatMessage(3, ChatRole.USER, "再看看这张课表", imageFileName = "new.jpg")
        )
        val result = fixture.client.chat(history, config, now, zone, imageDataUrl = image)
        val messages = fixture.requests.single()["messages"]!!.jsonArray
        assertTrue(messages[1].jsonObject["content"]!!.jsonPrimitive.content.contains("周二9点会议"))
        assertEquals(1, messages.count { it.jsonObject["content"] is JsonArray })
        assertTrue(result.events.isEmpty())
        assertTrue(result.imageAnalysis!!.contains("未标注节次时刻"))
    }

    @Test
    fun `semantic followup reuses image analysis with text model without uploading picture again`() = runBlocking {
        val fixture = Fixture("""{"reply":"第二项在周五","actions":[],"questions":[]}""")
        val history = listOf(
            ChatMessage(1, ChatRole.USER, "请安排", imageFileName = "photo.jpg", imageAnalysis = "第二项：周五14点数学课"),
            ChatMessage(2, ChatRole.ASSISTANT, "已识别两项活动，请确认"),
            ChatMessage(3, ChatRole.USER, "第二项是周五吗")
        )
        fixture.client.chat(history, config.copy(visionModel = "custom-image-model"), now, zone)
        val request = fixture.requests.single()
        assertEquals("glm-5.3-flashx", request["model"]!!.jsonPrimitive.content)
        assertTrue(request.toString().contains("第二项：周五14点数学课"))
        assertFalse(request.toString().contains("image_url"))
        assertFalse(request.toString().contains("base64"))
    }

    @Test
    fun `explicit image model overrides chat model and blank override follows current selection`() = runBlocking {
        val fixture = Fixture("""{"reply":"图中缺少日期","image_analysis":"会议14点，日期未写明","actions":[]}""")
        fixture.client.chat(history(), config.copy(visionModel = "custom-image-model"), now, zone, imageDataUrl = image)
        assertEquals("custom-image-model", fixture.requests.single()["model"]!!.jsonPrimitive.content)
        assertEquals("glm-5.3-flashx", config.imageModel)
        assertEquals("new-model", config.copy(model = "new-model").imageModel)
        assertEquals("glm-5.3-flashx", config.copy(visionModel = "  ").imageModel)
    }

    @Test
    fun `omitted calendar query is recovered using image facts without retaining original pixels`() = runBlocking {
        val fixture = Fixture(
            """{"reply":"准备安排会议","actions":[$create]}""",
            """{"reply":"已核对当天日历，请确认","image_analysis":"11月10日9点会议，地点六教A414","actions":[$create]}"""
        )
        var reads = 0
        val result = fixture.client.chat(history(), config, now, zone, imageDataUrl = image,
            readCalendar = { _, _ -> reads++; emptyList() })
        assertEquals(1, reads)
        assertEquals(1, result.events.size)
        assertTrue(result.imageAnalysis!!.contains("11月10日"))
        assertEquals(image, fixture.requests.first()["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.last().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertFalse(fixture.requests.last().toString().contains("image_url"))
        assertTrue(fixture.requests.last().toString().contains("proposed_events"))
    }

    @Test
    fun `image without schedule information returns no actions or unnecessary calendar query`() = runBlocking {
        val fixture = Fixture("""{"reply":"这张图片中没有可识别的日程信息","image_analysis":"普通风景照片，没有活动日期或时间","actions":[]}""")
        var reads = 0
        val result = fixture.client.chat(history(), config, now, zone, imageDataUrl = image,
            readCalendar = { _, _ -> reads++; emptyList() })
        assertEquals(0, reads)
        assertTrue(result.events.isEmpty())
        assertTrue(result.imageAnalysis!!.contains("风景照片"))
    }

    @Test
    fun `fallback retains actual reply and unresolved questions if model omits image analysis`() = runBlocking {
        val fixture = Fixture("""{"reply":"课表有数学和物理两节课","questions":["请提供节次对应的时刻"],"actions":[]}""")
        val result = fixture.client.chat(history(), config, now, zone, imageDataUrl = image)
        assertTrue(result.imageAnalysis!!.contains("数学和物理"))
        assertTrue(result.imageAnalysis.contains("节次对应的时刻"))
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `legacy OCR history remains usable for followup after upgrade`() = runBlocking {
        val fixture = Fixture("""{"reply":"图片原文写的是周二","actions":[]}""")
        val messages = listOf(ChatMessage(1, ChatRole.USER, "请安排", imageFileName = "old.jpg", imageText = "周二9点会议"),
            ChatMessage(2, ChatRole.USER, "之前那张图写的是星期几"))
        fixture.client.chat(messages, config, now, zone)
        assertTrue(fixture.requests.single().toString().contains("周二9点会议"))
        assertFalse(fixture.requests.single().toString().contains("image_url"))
    }

    @Test
    fun `invalid image and missing user message fail before making a request`() = runBlocking {
        val fixture = Fixture("{}")
        assertTrue(runCatching { fixture.client.chat(history(), config, now, zone, imageDataUrl = "file://photo.jpg") }.isFailure)
        assertTrue(runCatching { fixture.client.chat(listOf(ChatMessage(1, ChatRole.ASSISTANT, "hello")), config, now, zone, imageDataUrl = image) }.isFailure)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `unsupported image model and overly long analysis produce recoverable errors`() = runBlocking {
        val unsupported = Fixture("{}", status = 400)
        val failure = runCatching { unsupported.client.chat(history(), config, now, zone, imageDataUrl = image) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.contains("支持图片输入"))
        val tooLong = Fixture(buildJsonObject { put("reply", "请确认"); put("image_analysis", "字".repeat(30_001)) }.toString())
        assertTrue(runCatching { tooLong.client.chat(history(), config, now, zone, imageDataUrl = image) }.isFailure)
    }

    @Test
    fun `FlashX connection test uses supported temperature and enough output budget for thinking`() = runBlocking {
        val fixture = Fixture("OK")
        assertEquals("OK", fixture.client.testConnection(config))
        val request = fixture.requests.single()
        assertEquals(1.0, request["temperature"]!!.jsonPrimitive.double, 0.0)
        assertTrue(request["max_tokens"]!!.jsonPrimitive.int >= 1024)
        assertFalse(request.toString().contains("disabled"))
    }

    @Test
    fun `sufficient OCR text schedules with text model and never loads or uploads original image`() = runBlocking {
        val ocr = "2026年11月10日 09:00–10:00 会议 六教A414"
        val fixture = Fixture(
            """{"image_analysis":"11月10日9点会议，六教A414","calendar_requests":[{"start_date":"2026-11-10","end_date":"2026-11-10"}]}""",
            """{"reply":"请确认会议安排","image_request":null,"actions":[$create]}"""
        )
        var loads = 0
        val dates = mutableListOf<Pair<LocalDate, LocalDate>>()
        val result = fixture.client.chat(history(), config.copy(visionModel = "custom-vision"), now, zone,
            imageText = ocr, loadImageDataUrl = { loads++; image },
            readCalendar = { start, end -> dates += start to end; emptyList() })
        assertEquals(0, loads)
        assertEquals(1, result.events.size)
        assertEquals(ocr, result.imageText)
        assertEquals(30, result.totalTokens)
        assertEquals(listOf(LocalDate.parse("2026-11-10") to LocalDate.parse("2026-11-10")), dates)
        fixture.requests.forEach {
            assertEquals(config.model, it["model"]!!.jsonPrimitive.content)
            assertFalse(it.toString().contains("image_url"))
            assertFalse(it.toString().contains("base64"))
            assertTrue(it.toString().contains("image_ocr_text"))
        }
    }

    @Test
    fun `ambiguous OCR triggers one lazy image request then returns to text model with corrected facts`() = runBlocking {
        val fixture = Fixture(
            """{"image_request":{"reason":"日期和活动分属不同列，文字不能确认对应关系"},"actions":[$create],"calendar_requests":[{"week":1}]}""",
            """{"image_analysis":"已核对表格：会议在2026年11月10日09:00–10:00，六教A414","calendar_requests":[{"start_date":"2026-11-10","end_date":"2026-11-10"}]}""",
            """{"reply":"请确认","actions":[$create]}"""
        )
        var loads = 0
        val dates = mutableListOf<LocalDate>()
        val progress = mutableListOf<String>()
        val result = fixture.client.chat(history(), config.copy(visionModel = "custom-vision"), now, zone,
            imageText = "通知 日期 活动 11月10日 会议 其他活动",
            loadImageDataUrl = { loads++; image }, onProgress = { progress += it },
            readCalendar = { start, _ -> dates += start; emptyList() })
        assertEquals(1, loads)
        assertEquals(listOf(LocalDate.parse("2026-11-10")), dates)
        assertEquals(1, result.events.size)
        assertEquals(60, result.totalTokens)
        assertEquals(listOf(config.model, "custom-vision", config.model),
            fixture.requests.map { it["model"]!!.jsonPrimitive.content })
        assertFalse(fixture.requests.first().toString().contains("image_url"))
        val visionContent = fixture.requests[1]["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals(image, visionContent.last().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertFalse(fixture.requests.last().toString().contains("image_url"))
        assertTrue(fixture.requests.last().toString().contains("已核对表格"))
        assertTrue(progress.any { it.contains("补看原图") })
        assertTrue(progress.any { it.contains("读取") })
    }

    @Test
    fun `empty local OCR bypasses unhelpful text round and inspects original once`() = runBlocking {
        val fixture = Fixture(
            """{"image_analysis":"会议在11月10日09:00–10:00","calendar_requests":[{"start_date":"2026-11-10","end_date":"2026-11-10"}]}""",
            """{"reply":"请确认","actions":[$create]}"""
        )
        var loads = 0
        val result = fixture.client.chat(history(), config.copy(visionModel = "custom-vision"), now, zone,
            imageText = "", loadImageDataUrl = { loads++; image }, readCalendar = { _, _ -> emptyList() })
        assertEquals(1, loads)
        assertEquals(2, fixture.requests.size)
        assertEquals("custom-vision", fixture.requests.first()["model"]!!.jsonPrimitive.content)
        assertEquals(config.model, fixture.requests.last()["model"]!!.jsonPrimitive.content)
        assertFalse(fixture.requests.last().toString().contains("image_url"))
        assertEquals(1, result.events.size)
    }

    @Test
    fun `missing real world information asks user without uploading image`() = runBlocking {
        val fixture = Fixture("""{"reply":"通知未注明日期，请补充","questions":["会议是哪天？"],"actions":[]}""")
        var loads = 0
        val result = fixture.client.chat(history(), config, now, zone, imageText = "会议 09:00–10:00",
            loadImageDataUrl = { loads++; image })
        assertEquals(0, loads)
        assertEquals(listOf("会议是哪天？"), result.questions)
        assertTrue(result.events.isEmpty())
        assertEquals("会议 09:00–10:00", result.imageText)
    }

    @Test
    fun `repeated or malformed image request cannot loop or sneak in actions`() = runBlocking {
        listOf("""{"reason":"再看一次"}""", """{"reason":""}""").forEach { request ->
            val fixture = Fixture(
                """{"image_request":{"reason":"行列对应不清楚"}}""",
                """{"image_request":$request,"actions":[$create]}"""
            )
            var loads = 0
            var reads = 0
            val result = fixture.client.chat(history(), config, now, zone, imageText = "模糊课表",
                loadImageDataUrl = { loads++; image }, readCalendar = { _, _ -> reads++; emptyList() })
            assertEquals(1, loads)
            assertEquals(0, reads)
            assertEquals(2, fixture.requests.size)
            assertTrue(result.events.isEmpty())
            assertTrue(result.summary.contains("补充"))
        }
    }

    @Test
    fun `image fallback does not consume calendar read rounds and image is never reuploaded`() = runBlocking {
        val query = """{"image_analysis":"会议在11月10日","calendar_requests":[{"start_date":"2026-11-10","end_date":"2026-11-10"}]}"""
        val fixture = Fixture("""{"image_request":{"reason":"文字次序混乱"}}""", query, query, query, query)
        var loads = 0
        var reads = 0
        val result = fixture.client.chat(history(), config, now, zone, imageText = "会议 日期 时间",
            loadImageDataUrl = { loads++; image }, readCalendar = { _, _ -> reads++; emptyList() })
        assertEquals(5, fixture.requests.size)
        assertEquals(1, loads)
        assertEquals(1, reads) // 相同范围走本次提问内的缓存。
        assertEquals(1, fixture.requests.count { it.toString().contains("image_url") })
        assertTrue(result.events.isEmpty())
        assertTrue(result.summary.contains("上限"))
        assertEquals(150, result.totalTokens)
    }

    @Test
    fun `visual query without extracted facts fails rather than reuploading image or inventing details`() = runBlocking {
        val fixture = Fixture("""{"calendar_requests":[{"start_date":"2026-11-10","end_date":"2026-11-10"}]}""")
        var reads = 0
        val failure = runCatching {
            fixture.client.chat(history(), config, now, zone, imageText = "", loadImageDataUrl = { image },
                readCalendar = { _, _ -> reads++; emptyList() })
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(0, reads)
        assertEquals(1, fixture.requests.size)
    }

    private fun history() = listOf(ChatMessage(1, ChatRole.USER, "请按图片安排日程", imageFileName = "photo.jpg"))

    private class Fixture(vararg payloads: String, private val status: Int = 200) {
        val requests = mutableListOf<JsonObject>()
        val client = GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val index = requests.lastIndex
            check(index < payloads.size) { "Unexpected extra model call" }
            val body = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject { put("message", buildJsonObject { put("content", payloads[index]) }) })
                })
                put("usage", buildJsonObject { put("total_tokens", 10 * (index + 1)) })
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(body.toString().toResponseBody()).build()
        }.build())
    }
}

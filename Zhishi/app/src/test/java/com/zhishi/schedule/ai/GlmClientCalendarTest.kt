package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.ChatMessage
import com.zhishi.schedule.domain.ChatRole
import com.zhishi.schedule.domain.GlmConfig
import com.zhishi.schedule.domain.ParsedActionType
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

/** 用拦截器模拟模型，验证读取闭环；不访问网络或真实日历。 */
class GlmClientCalendarTest {
    private val now = Instant.parse("2026-10-24T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val config = GlmConfig(baseUrl = "https://example.test", apiKey = "test")

    @Test
    fun `reads requested month before answering and totals usage across calls`() = runBlocking {
        val fixture = Fixture(
            """{"calendar_requests":[{"start_date":"2026-11-01","end_date":"2026-11-30"}]}""",
            """{"reply":"11月有一次会议","actions":[],"questions":[]}"""
        )
        val calls = mutableListOf<Pair<LocalDate, LocalDate>>()
        val result = fixture.client.chat(history("下个月有什么安排？"), config, now, zone,
            readCalendar = { start, end -> calls += start to end; listOf(event()) })
        assertEquals(listOf(LocalDate.parse("2026-11-01") to LocalDate.parse("2026-11-30")), calls)
        assertEquals(30, result.totalTokens)
        assertEquals("11月有一次会议", result.summary)
        assertTrue(fixture.lastCalendarResult()["complete"]!!.jsonPrimitive.boolean)
        assertEquals("会议", fixture.lastCalendarResult()["events"]!!.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `followup preserves dates from history and supports source plus destination reads`() = runBlocking {
        val fixture = Fixture(
            """{"calendar_requests":[{"start_date":"2026-11-10","end_date":"2026-11-10"},{"start_date":"2026-12-01","end_date":"2026-12-01"}]}""",
            """{"reply":"可以移到12月1日","actions":[{"type":"update_event","event_id":42,"start":"2026-12-01T09:00:00+08:00","end":"2026-12-01T10:00:00+08:00"}],"questions":[]}"""
        )
        val history = listOf(
            ChatMessage(1, ChatRole.USER, "11月10日的会议", timestamp = now.minusSeconds(86_400).toEpochMilli()),
            ChatMessage(2, ChatRole.ASSISTANT, "会议在上午9点"),
            ChatMessage(3, ChatRole.USER, "把它移到12月1日")
        )
        val calls = mutableListOf<LocalDate>()
        val result = fixture.client.chat(history, config, now, zone, readCalendar = { start, _ ->
            calls += start
            if (start == LocalDate.parse("2026-11-10")) listOf(event()) else emptyList()
        })
        assertEquals(listOf(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-12-01")), calls)
        assertEquals(ParsedActionType.UPDATE, result.events.single().action)
        assertEquals(42L, result.events.single().targetEventId)
        assertEquals(event().startMillis, result.events.single().originalStartMillis)
        val firstUser = fixture.requests.first()["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(firstUser.contains("2026-10-23T12:00+08:00"))
        assertTrue(firstUser.contains("11月10日的会议"))
    }

    @Test
    fun `small talk makes one call without reading calendars`() = runBlocking {
        val fixture = Fixture("""{"reply":"你好","actions":[],"questions":[]}""")
        var reads = 0
        val result = fixture.client.chat(history("你好"), config, now, zone,
            readCalendar = { _, _ -> reads++; emptyList() })
        assertEquals(0, reads)
        assertEquals(1, fixture.requests.size)
        assertEquals(10, result.totalTokens)
    }

    @Test
    fun `invalid range can be corrected in next model round without a broad fallback`() = runBlocking {
        val fixture = Fixture(
            """{"calendar_requests":[{"start_date":"2026-02-29","end_date":"2026-02-29"}]}""",
            """{"calendar_requests":[{"start_date":"2026-02-28","end_date":"2026-02-28"}]}""",
            """{"reply":"2月28日没有安排","actions":[],"questions":[]}"""
        )
        val calls = mutableListOf<LocalDate>()
        val result = fixture.client.chat(history("2月底有什么安排"), config, now, zone,
            readCalendar = { start, _ -> calls += start; emptyList() })
        assertEquals(listOf(LocalDate.parse("2026-02-28")), calls)
        assertEquals(60, result.totalTokens)
        val secondMessages = fixture.requests[1]["messages"]!!.jsonArray
        assertTrue(secondMessages.last().jsonObject["content"]!!.jsonPrimitive.content.contains("error"))
    }

    @Test
    fun `query response actions are deferred until calendar results arrive`() = runBlocking {
        val fixture = Fixture(
            """{"calendar_requests":[{"start_date":"2026-11-10","end_date":"2026-11-10"}],"actions":[{"type":"delete_event","event_id":999}]}""",
            """{"reply":"请确认删除会议","actions":[{"type":"delete_event","event_id":42}],"questions":[]}"""
        )
        val result = fixture.client.chat(history("删除11月10日的会议"), config, now, zone,
            readCalendar = { _, _ -> listOf(event()) })
        assertEquals(42L, result.events.single().targetEventId)
        assertEquals(ParsedActionType.DELETE, result.events.single().action)
    }

    @Test
    fun `repeated queries stop at round limit and never execute speculative actions`() = runBlocking {
        val query = """{"calendar_requests":[{"start_date":"2026-11-10","end_date":"2026-11-10"}],"actions":[{"type":"delete_event","event_id":42}]}"""
        val fixture = Fixture(query, query, query, query)
        var reads = 0
        val result = fixture.client.chat(history("查一下会议"), config, now, zone,
            readCalendar = { _, _ -> reads++; listOf(event()) })
        assertEquals(4, fixture.requests.size)
        assertEquals(1, reads)
        assertTrue(result.events.isEmpty())
        assertTrue(result.summary.contains("上限"))
        assertEquals(100, result.totalTokens)
    }

    @Test
    fun `provider failure aborts instead of telling model calendar is empty`() = runBlocking {
        val fixture = Fixture("""{"calendar_requests":[{"start_date":"2026-11-10","end_date":"2026-11-10"}]}""")
        val failure = runCatching {
            fixture.client.chat(history("有安排吗"), config, now, zone,
                readCalendar = { _, _ -> throw IOException("calendar unavailable") })
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun `unread ids in history cannot be used for destructive operations`() = runBlocking {
        val fixture = Fixture("""{"reply":"删除会议","actions":[{"type":"delete_event","event_id":42}]}""")
        val result = fixture.client.chat(history("删除之前的会议id42"), config, now, zone)
        assertTrue(result.events.isEmpty())
        assertTrue(result.warnings.single().contains("找不到"))
    }

    @Test
    fun `omitted query is recovered before accepting creation and conflict reply`() = runBlocking {
        val create = """{"type":"create_event","title":"跑步","start":"2026-11-10T09:00:00+08:00","end":"2026-11-10T10:00:00+08:00"}"""
        val fixture = Fixture(
            """{"reply":"可以安排","actions":[$create]}""",
            """{"reply":"与会议冲突，请确认","actions":[$create]}"""
        )
        val calls = mutableListOf<Pair<LocalDate, LocalDate>>()
        val result = fixture.client.chat(history("11月10日9点安排跑步"), config, now, zone,
            readCalendar = { start, end -> calls += start to end; listOf(event()) })
        assertEquals(listOf(LocalDate.parse("2026-11-10") to LocalDate.parse("2026-11-10")), calls)
        assertEquals("与会议冲突，请确认", result.summary)
        assertEquals(1, result.events.size)
        assertEquals(30, result.totalTokens)
    }

    @Test
    fun `creation without calendar access cannot bypass conflict validation`() = runBlocking {
        val fixture = Fixture("""{"reply":"安排好了","actions":[{"type":"create_event","title":"跑步","start":"2026-11-10T09:00:00+08:00","end":"2026-11-10T10:00:00+08:00"}]}""")
        val result = fixture.client.chat(history("11月10日9点安排跑步"), config, now, zone)
        assertTrue(result.events.isEmpty())
        assertTrue(result.warnings.single().contains("无法检查冲突"))
    }

    @Test
    fun `academic weekday request resolves locally before accepting event creation`() = runBlocking {
        val fixture = Fixture(
            """{"calendar_requests":[{"week":7,"weekday":3}]}""",
            """{"reply":"第七周周三即10月28日，待确认","actions":[{"type":"create_event","title":"复习数学","start":"2026-10-28T19:00:00+08:00","end":"2026-10-28T20:00:00+08:00"}]}"""
        )
        val calls = mutableListOf<Pair<LocalDate, LocalDate>>()
        val result = fixture.client.chat(history("第七周周三19点复习数学一小时"), config, now, zone,
            readCalendar = { start, end -> calls += start to end; emptyList() })
        assertEquals(listOf(LocalDate.parse("2026-10-28") to LocalDate.parse("2026-10-28")), calls)
        assertEquals("2026-10-28", fixture.lastCalendarResult()["start_date"]!!.jsonPrimitive.content)
        assertEquals(Instant.parse("2026-10-28T11:00:00Z").toEpochMilli(), result.events.single().startMillis)
        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun `compact prompt provides requested week conversion and current week without unrelated full map`() = runBlocking {
        val fixture = Fixture("""{"reply":"第7周周三是10月28日","actions":[]}""")
        var reads = 0
        fixture.client.chat(history("第七周周三是哪天"), config, now, zone,
            scheduleContext = "页面查看周=2026-11-02至2026-11-08（第8周）",
            readCalendar = { _, _ -> reads++; emptyList() })
        val prompt = fixture.requests.single()["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(prompt.contains("今天 2026-10-24 对应：第6周"))
        assertTrue(prompt.contains("本机换算第7周：2026-10-26 至 2026-11-01"))
        assertTrue(prompt.contains("本机换算第7周周3=2026-10-28"))
        assertFalse(prompt.contains("第16周：2026-12-28 至 2027-01-03"))
        assertFalse(prompt.contains("第1周：2026-09-14 至 2026-09-20"))
        assertTrue(prompt.contains("单双周按教学周编号的奇偶"))
        assertTrue(prompt.contains("第1周周一=2026-09-14"))
        assertTrue(prompt.contains("第2大节：09:50–12:15"))
        assertTrue(prompt.contains("第5大节：17:05–18:40（原则上不排课，锻炼时间）"))
        assertTrue(prompt.contains("context_requests"))
        assertFalse(prompt.contains("只有上课节次而无具体时刻时仍需询问作息时间"))
        assertTrue(prompt.contains("不能称为第19周"))
        assertTrue(prompt.contains("页面查看周=2026-11-02"))
        assertFalse(prompt.contains("用户近7天完成情况"))
        assertFalse(prompt.contains("自评"))
        assertEquals(0, reads)
    }

    private fun history(text: String) = listOf(ChatMessage(1, ChatRole.USER, text))

    private fun event(): CalendarEvent {
        val start = Instant.parse("2026-11-10T01:00:00Z").toEpochMilli()
        return CalendarEvent(42, 1, "会议", start, start + 3_600_000, false)
    }

    private class Fixture(vararg payloads: String) {
        val requests = mutableListOf<JsonObject>()
        val client = GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val index = requests.lastIndex
            check(index < payloads.size) { "Unexpected extra model request" }
            val body = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject { put("message", buildJsonObject { put("content", payloads[index]) }) })
                })
                put("usage", buildJsonObject { put("total_tokens", 10 * (index + 1)) })
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toString().toResponseBody()).build()
        }.build())

        fun lastCalendarResult(): JsonObject {
            val messages = requests.last()["messages"]!!.jsonArray
            val data = messages.last().jsonObject["content"]!!.jsonPrimitive.content
            return Json.parseToJsonElement(data).jsonObject["calendar_results"]!!.jsonArray.single().jsonObject
        }
    }
}

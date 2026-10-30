package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class GlmClientWeatherTest {
    private val now = Instant.parse("2026-10-25T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val city = WeatherLocation("北京", 39.9, 116.4)
    private val config = GlmConfig(baseUrl = "https://example.test", apiKey = "test")
    private val history = listOf(ChatMessage(1, ChatRole.USER, "明天按天气安排跑步"))
    private val action = """{"reply":"北京明天18点无雨，待确认","actions":[{"type":"create_event","title":"跑步","start":"2026-10-26T18:00:00+08:00","end":"2026-10-26T19:00:00+08:00","weather_sensitive":true}]}"""
    private val query = """{"start_date":"2026-10-26","end_date":"2026-10-26"}"""

    @Test fun `calendar and forecast can be requested together before proposed action and tokens accumulate`() = runBlocking {
        val fixture = Fixture("""{"calendar_requests":[$query],"weather_requests":[$query],"actions":[{"type":"delete_event","event_id":999}]}""", action)
        var calendarReads = 0; var weatherReads = 0
        val result = fixture.client.chat(history, config, now, zone,
            readCalendar = { _, _ -> calendarReads++; emptyList() }, weatherLocation = city,
            readWeather = { start, _ -> weatherReads++; forecast(start) })
        assertEquals(1, calendarReads); assertEquals(1, weatherReads)
        assertEquals("跑步", result.events.single().title)
        assertEquals(20, result.totalTokens)
        assertTrue(fixture.lastData().containsKey("calendar_results"))
        assertTrue(fixture.lastData().containsKey("weather_results"))
        val prompt = fixture.requests.first()["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(prompt.contains("北京")); assertTrue(prompt.contains("截至time的前一小时"))
        assertTrue(prompt.contains("不授权自动修改"))
    }

    @Test fun `outdoor proposal missing both tools is checked against calendar then forecast`() = runBlocking {
        val fixture = Fixture(action, action, action)
        var weatherReads = 0
        val result = fixture.client.chat(history, config, now, zone, readCalendar = { _, _ -> emptyList() },
            weatherLocation = city, readWeather = { start, _ -> weatherReads++; forecast(start) })
        assertEquals(3, fixture.requests.size)
        assertEquals(1, weatherReads)
        assertEquals(1, result.events.size)
        assertTrue(fixture.lastData().containsKey("weather_results"))
    }

    @Test fun `indoor action does not request weather and missing location warns on outdoor actions`() = runBlocking {
        val indoor = action.replace("跑步", "室内跑步")
        val fixture = Fixture(indoor, indoor)
        var weatherReads = 0
        fixture.client.chat(history, config, now, zone, readCalendar = { _, _ -> emptyList() },
            weatherLocation = city, readWeather = { start, _ -> weatherReads++; forecast(start) })
        assertEquals(0, weatherReads)
        val unavailable = Fixture(action, action).client.chat(history, config, now, zone, readCalendar = { _, _ -> emptyList() })
        assertTrue(unavailable.warnings.any { it.contains("尚未选择天气城市") })
    }

    @Test fun `weather lookup failure is explicit unknown and does not imply an empty calendar`() = runBlocking {
        val fixture = Fixture("""{"weather_requests":[$query]}""", """{"reply":"天气查询失败，是否改为室内运动？","actions":[]}""")
        var calendarReads = 0
        val result = fixture.client.chat(history, config, now, zone,
            readCalendar = { _, _ -> calendarReads++; emptyList() }, weatherLocation = city,
            readWeather = { _, _ -> throw java.io.IOException("天气服务不可用") })
        assertTrue(result.events.isEmpty())
        assertEquals(0, calendarReads)
        assertFalse(fixture.lastData()["weather_results"]!!.jsonArray.single().jsonObject["weather_known"]!!.jsonPrimitive.boolean)
        assertTrue(result.warnings.any { it.contains("天气服务不可用") })
    }

    @Test fun `mixed indoor and outdoor proposals only read dates of the weather sensitive action`() = runBlocking {
        val payload = Json.parseToJsonElement(action).jsonObject
        val indoor = buildJsonObject {
            put("type", "create_event"); put("title", "会议")
            put("start", "2026-11-20T09:00:00+08:00"); put("end", "2026-11-20T10:00:00+08:00")
        }
        val mixed = JsonObject(payload + ("actions" to JsonArray(payload["actions"]!!.jsonArray + indoor))).toString()
        val fixture = Fixture(mixed, mixed, mixed)
        val dates = mutableListOf<LocalDate>()
        val result = fixture.client.chat(history, config, now, zone, readCalendar = { _, _ -> emptyList() },
            weatherLocation = city, readWeather = { start, _ -> dates += start; forecast(start) })
        assertEquals(listOf(LocalDate.parse("2026-10-26")), dates)
        assertEquals(2, result.events.size)
        assertFalse(result.events.last().weatherSensitive)
    }

    @Test fun `weather read rounds bounded independently and speculative actions ignored`() = runBlocking {
        val payload = """{"weather_requests":[$query],"actions":[]} """
        val fixture = Fixture(payload, payload, payload, payload)
        var reads = 0
        val result = fixture.client.chat(history, config, now, zone, weatherLocation = city,
            readWeather = { start, _ -> reads++; forecast(start) })
        assertEquals(1, reads)
        assertEquals(4, fixture.requests.size)
        assertTrue(result.summary.contains("上限"))
        assertTrue(result.events.isEmpty())
    }

    @Test fun `stop generation while reading forecast prevents a further model call`() = runBlocking {
        val fixture = Fixture("""{"weather_requests":[$query]}""")
        val started = CompletableDeferred<Unit>()
        val job = async { fixture.client.chat(history, config, now, zone, weatherLocation = city,
            readWeather = { _, _ -> started.complete(Unit); awaitCancellation() }) }
        withTimeout(2000) { started.await() }
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(1, fixture.requests.size)
    }

    private fun forecast(day: LocalDate) = WeatherForecast(city, zone, now, (0..24).map {
        WeatherHour(day.atStartOfDay(zone).toInstant().plusSeconds(it * 3600L), 18.0, 17.0, 0, 0.0, 1.0, 2.0, 0)
    })
    private class Fixture(vararg payloads: String) {
        val requests = mutableListOf<JsonObject>()
        val client = GlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            check(requests.size <= payloads.size) { "Unexpected model request" }
            val body = buildJsonObject {
                put("choices", buildJsonArray { add(buildJsonObject {
                    put("message", buildJsonObject { put("content", payloads[requests.lastIndex]) })
                }) })
                put("usage", buildJsonObject { put("total_tokens", 10) })
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toString().toResponseBody()).build()
        }.build())
        fun lastData() = Json.parseToJsonElement(requests.last()["messages"]!!.jsonArray.last()
            .jsonObject["content"]!!.jsonPrimitive.content).jsonObject
    }
}

package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.*

class WeatherReadSessionTest {
    private val now = Instant.parse("2026-10-25T04:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val city = WeatherLocation("北京", 39.9, 116.4)
    private val day = LocalDate.parse("2026-10-26")

    @Test fun `same question caches forecast and encodes true offset times with closing midnight`() = runBlocking {
        var calls = 0
        val session = WeatherReadSession(now, zone, city) { start, _ -> calls++; forecast(start) }
        val first = result(session.read(query(day)))
        session.read(query(day))
        assertEquals(1, calls)
        assertTrue(first["complete"]!!.jsonPrimitive.boolean)
        assertEquals(25, first["hours"]!!.jsonArray.size)
        assertEquals("2026-10-26T00:00+08:00", first["hours"]!!.jsonArray.first().jsonArray[0].jsonPrimitive.content)
        assertEquals("2026-10-27T00:00+08:00", first["hours"]!!.jsonArray.last().jsonArray[0].jsonPrimitive.content)
        assertTrue(first["interval_note"]!!.jsonPrimitive.content.contains("time-1小时"))
    }

    @Test fun `past beyond horizon invalid and oversized requests make no network calls`() = runBlocking {
        var calls = 0
        val session = WeatherReadSession(now, zone, city) { start, _ -> calls++; forecast(start) }
        listOf(query(day.minusDays(2)), query(day.plusDays(15)), query(day, day.plusDays(7)),
            JsonArray(List(4) { query(day).single() }),
            Json.parseToJsonElement("""[{"start_date":"2026-02-29","end_date":"2026-02-29"}]""").jsonArray
        ).forEach { assertFalse(result(session.read(it))["complete"]!!.jsonPrimitive.boolean) }
        assertEquals(0, calls)
    }

    @Test fun `different city or absent city cannot silently use selected location`() = runBlocking {
        var calls = 0
        val session = WeatherReadSession(now, zone, city) { start, _ -> calls++; forecast(start) }
        val request = JsonArray(listOf(JsonObject(query(day).single().jsonObject + ("city" to JsonPrimitive("上海")))))
        assertTrue(result(session.read(request))["error"]!!.jsonPrimitive.content.contains("城市"))
        val missing = WeatherReadSession(now, zone, null, null)
        assertTrue(result(missing.read(query(day)))["error"]!!.jsonPrimitive.content.contains("选择"))
        assertEquals(0, calls)
    }

    @Test fun `missing hour null rain and unknown code are incomplete rather than good weather`() = runBlocking {
        listOf(forecast(day).copy(hours = forecast(day).hours.dropLast(1)),
            forecast(day).copy(hours = forecast(day).hours.map { it.copy(precipitationMm = null, weatherCode = 999) })
        ).forEach { value ->
            val session = WeatherReadSession(now, zone, city) { _, _ -> value }
            val data = result(session.read(query(day)))
            assertFalse(data["complete"]!!.jsonPrimitive.boolean)
            assertTrue(session.warnings.any { it.contains("缺失") })
        }
    }

    @Test fun `stale mismatched and failed forecast returns explicit unknown`() = runBlocking {
        listOf(forecast(day).copy(fetchedAt = now.minusSeconds(3600)),
            forecast(day).copy(location = WeatherLocation("上海", 31.2, 121.5)),
            forecast(day).copy(zoneId = ZoneId.of("UTC"))
        ).forEach { value ->
            val session = WeatherReadSession(now, zone, city) { _, _ -> value }
            assertFalse(result(session.read(query(day)))["weather_known"]!!.jsonPrimitive.boolean)
        }
        val session = WeatherReadSession(now, zone, city) { _, _ -> throw IOException("断网") }
        assertTrue(result(session.read(query(day)))["error"]!!.jsonPrimitive.content.contains("断网"))
    }

    @Test fun `outdoor cross midnight actions read both days but indoor delete and attempted days do not`() = runBlocking {
        val session = WeatherReadSession(now, zone, city) { start, _ -> forecast(start) }
        val start = day.atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        val action = ParsedEvent("跑步", start, start + 3600_000)
        assertEquals(2, session.unreadActionRanges(listOf(action), false).size)
        assertTrue(session.unreadActionRanges(listOf(action.copy(title = "室内跑步")), true).isEmpty())
        assertTrue(session.unreadActionRanges(listOf(action.copy(title = "跑步机训练")), true).isEmpty())
        assertTrue(session.unreadActionRanges(listOf(action.copy(action = ParsedActionType.DELETE)), true).isEmpty())
        session.read(query(day, day.plusDays(1)))
        assertTrue(session.unreadActionRanges(listOf(action), false).isEmpty())
    }

    @Test fun `cancellation propagates without converting to unknown forecast`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val session = WeatherReadSession(now, zone, city) { _, _ -> started.complete(Unit); awaitCancellation() }
        val job = async { session.read(query(day)) }
        withTimeout(2000) { started.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(session.warnings.isEmpty())
    }

    private fun forecast(start: LocalDate) = WeatherForecast(city, zone, now,
        (0..24).map { hour -> WeatherHour(start.atStartOfDay(zone).toInstant().plusSeconds(hour * 3600L),
            18.0, 17.0, 10, 0.0, 2.0, 3.0, 0) })
    private fun query(start: LocalDate, end: LocalDate = start) = buildJsonArray {
        add(buildJsonObject { put("start_date", start.toString()); put("end_date", end.toString()) })
    }
    private fun result(data: JsonObject) = data["weather_results"]!!.jsonArray.first().jsonObject
}

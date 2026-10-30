package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.ParsedEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarReadSessionTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `reads exact requested dates including distant history leap month and year boundary`() = runBlocking {
        val ranges = listOf(
            "2026-10-25" to "2026-10-25",
            "2026-11-01" to "2026-11-30",
            "2024-02-01" to "2024-02-29",
            "2026-12-28" to "2027-01-03",
            "2025-05-01" to "2025-05-31",
            "2028-07-12" to "2028-07-12"
        )
        ranges.forEach { (start, end) ->
            val calls = mutableListOf<Pair<LocalDate, LocalDate>>()
            val session = CalendarReadSession(zone) { first, last -> calls += first to last; emptyList() }
            val result = session.read(requests(start to end)).results().single().jsonObject
            assertEquals(listOf(LocalDate.parse(start) to LocalDate.parse(end)), calls)
            assertEquals(start, result["start_date"]!!.jsonPrimitive.content)
            assertEquals(end, result["end_date"]!!.jsonPrimitive.content)
            assertTrue(result["complete"]!!.jsonPrimitive.boolean)
            assertEquals(0, result["total_events"]!!.jsonPrimitive.int)
        }
    }

    @Test
    fun `separate source and destination ranges do not load intervening dates`() = runBlocking {
        val calls = mutableListOf<Pair<LocalDate, LocalDate>>()
        val session = CalendarReadSession(zone) { start, end -> calls += start to end; emptyList() }
        session.read(requests("2026-10-26" to "2026-10-26", "2027-04-02" to "2027-04-02"))
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.first == it.second })
    }

    @Test
    fun `caches identical ranges and deduplicates instances without merging recurring dates`() = runBlocking {
        var calls = 0
        val first = event(1, "2026-10-24")
        val second = event(1, "2026-10-25")
        val session = CalendarReadSession(zone) { _, _ -> calls++; listOf(first, first, second) }
        repeat(2) { session.read(requests("2026-10-24" to "2026-10-25")) }
        session.read(requests("2026-10-25" to "2026-10-25"))
        assertEquals(2, calls)
        assertEquals(listOf(first, second), session.events)
    }

    @Test
    fun `filters by overlap and preserves UTC calendar dates for all day events`() = runBlocking {
        val midnight = LocalDate.parse("2026-10-24").atStartOfDay(zone).toInstant().toEpochMilli()
        val crossing = event(1, "2026-10-23").copy(startMillis = midnight - 3_600_000, endMillis = midnight + 3_600_000)
        val ended = crossing.copy(eventId = 2, endMillis = midnight)
        val nextDay = event(3, "2026-10-25")
        val allDay = event(4, "2026-10-24", allDay = true)
        val session = CalendarReadSession(zone) { _, _ -> listOf(crossing, ended, nextDay, allDay) }
        val result = session.read(requests("2026-10-24" to "2026-10-24")).results().single().jsonObject
        assertEquals(setOf(1L, 4L), session.events.map { it.eventId }.toSet())
        val serialized = result["events"]!!.jsonArray.last().jsonObject
        assertEquals("2026-10-24", serialized["start_date"]!!.jsonPrimitive.content)
        assertEquals("2026-10-25", serialized["end_date_exclusive"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid reversed and overly broad dates never reach calendar reader`() = runBlocking {
        var calls = 0
        val session = CalendarReadSession(zone) { _, _ -> calls++; emptyList() }
        listOf(
            "2026-02-29" to "2026-03-01",
            "2026-11-02" to "2026-11-01",
            "2026-01-01" to "2027-01-02",
            "garbage" to "2026-10-24"
        ).forEach { range ->
            val result = session.read(requests(range)).results().single().jsonObject
            assertNotNull(result["error"])
            assertFalse(result["complete"]!!.jsonPrimitive.boolean)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `366 day budget counts unique dates across all rounds`() = runBlocking {
        var calls = 0
        val session = CalendarReadSession(zone) { _, _ -> calls++; emptyList() }
        session.read(requests("2024-01-01" to "2024-12-31"))
        session.read(requests("2024-02-01" to "2024-02-29"))
        val overBudget = session.read(requests("2025-01-01" to "2025-01-01")).results().single().jsonObject
        assertEquals(2, calls)
        assertNotNull(overBudget["error"])
    }

    @Test
    fun `limits query count and marks truncated results incomplete`() = runBlocking {
        var calls = 0
        val session = CalendarReadSession(zone) { _, _ ->
            calls++
            (1L..201L).map { event(it, "2026-10-24") }
        }
        val result = session.read(requests(*Array(5) { "2026-10-24" to "2026-10-24" })).results()
        assertEquals(1, calls)
        assertEquals(5, result.size)
        val first = result.first().jsonObject
        assertEquals(201, first["total_events"]!!.jsonPrimitive.int)
        assertEquals(200, first["events"]!!.jsonArray.size)
        assertFalse(first["complete"]!!.jsonPrimitive.boolean)
        assertNotNull(result.last().jsonObject["error"])
        assertEquals(1, session.warnings.size)
    }

    @Test
    fun `unavailable reader and failed reads cannot be mistaken for empty calendars`() = runBlocking {
        val missing = CalendarReadSession(zone, null)
        assertNotNull(missing.read(requests("2026-10-24" to "2026-10-24")).results().single().jsonObject["error"])
        val failed = CalendarReadSession(zone) { _, _ -> throw SecurityException("permission revoked") }
        val error = runCatching { failed.read(requests("2026-10-24" to "2026-10-24")) }.exceptionOrNull()
        assertTrue(error is SecurityException)
        assertTrue(failed.events.isEmpty())
    }

    @Test
    fun `action coverage respects exclusive midnight end and requires complete results`() = runBlocking {
        val start = LocalDate.parse("2026-10-24").atStartOfDay(zone)
        val action = ParsedEvent("跨天前结束", start.toInstant().toEpochMilli(), start.plusDays(1).toInstant().toEpochMilli())
        val session = CalendarReadSession(zone) { _, _ -> emptyList() }
        assertFalse(session.coversAction(action))
        session.read(requests("2026-10-24" to "2026-10-24"))
        assertTrue(session.coversAction(action))
        assertFalse(session.coversAction(action.copy(endMillis = action.endMillis + 1)))
        val truncated = CalendarReadSession(zone) { _, _ -> (1L..201L).map { event(it, "2026-10-24") } }
        truncated.read(requests("2026-10-24" to "2026-10-24"))
        assertFalse(truncated.coversAction(action))
    }

    @Test
    fun `academic week requests read whole week specific weekday and inclusive week span`() = runBlocking {
        val calls = mutableListOf<Pair<LocalDate, LocalDate>>()
        val session = CalendarReadSession(zone) { start, end -> calls += start to end; emptyList() }
        val result = session.read(Json.parseToJsonElement("""[
            {"week":6}, {"week":7,"weekday":3}, {"start_week":8,"end_week":10}, {"week":16}
        ]""").jsonArray).results()
        assertEquals(listOf(
            "2026-10-19" to "2026-10-25",
            "2026-10-28" to "2026-10-28",
            "2026-11-02" to "2026-11-22",
            "2026-12-28" to "2027-01-03"
        ).map { (start, end) -> LocalDate.parse(start) to LocalDate.parse(end) }, calls)
        result.forEachIndexed { index, item ->
            val data = item.jsonObject
            assertTrue(data["complete"]!!.jsonPrimitive.boolean)
            assertEquals(calls[index].first.toString(), data["start_date"]!!.jsonPrimitive.content)
            assertEquals(calls[index].second.toString(), data["end_date"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `weekday Sunday resolves correctly and date aliases reuse cache`() = runBlocking {
        val calls = mutableListOf<Pair<LocalDate, LocalDate>>()
        val session = CalendarReadSession(zone) { start, end -> calls += start to end; emptyList() }
        session.read(Json.parseToJsonElement("""[
            {"week":6,"weekday":7},
            {"start_date":"2026-10-25","end_date":"2026-10-25"},
            {"week":6,"weekday":7,"start_date":"2026-10-25","end_date":"2026-10-25"}
        ]""").jsonArray).results().forEach {
            assertTrue(it.jsonObject["complete"]!!.jsonPrimitive.boolean)
        }
        assertEquals(listOf(LocalDate.parse("2026-10-25") to LocalDate.parse("2026-10-25")), calls)
    }

    @Test
    fun `invalid academic weeks and conflicting dates never query provider`() = runBlocking {
        var reads = 0
        val session = CalendarReadSession(zone) { _, _ -> reads++; emptyList() }
        val invalid = listOf(
            """{"week":0}""", """{"week":19}""", """{"week":7.5}""",
            """{"week":7,"weekday":0}""", """{"week":7,"weekday":8}""",
            """{"start_week":10,"end_week":8}""", """{"start_week":17,"end_week":19}""",
            """{"start_week":8}""", """{"end_week":10}""", """{"weekday":3}""",
            """{"week":7,"start_week":7,"end_week":8}""",
            """{"start_week":8,"end_week":10,"weekday":3}""",
            """{"week":7,"start_date":"2026-10-19","end_date":"2026-10-25"}""",
            """{"week":7,"start_date":"2026-10-26"}"""
        )
        invalid.forEach { request ->
            val data = session.read(Json.parseToJsonElement("[$request]").jsonArray).results().single().jsonObject
            assertTrue("Expected error for $request", "error" in data)
            assertFalse(data["complete"]!!.jsonPrimitive.boolean)
        }
        assertEquals(0, reads)
    }

    private fun event(id: Long, date: String, allDay: Boolean = false): CalendarEvent {
        val start = LocalDate.parse(date).atStartOfDay(if (allDay) ZoneOffset.UTC else zone)
        return CalendarEvent(id, 1, "日程$id", start.toInstant().toEpochMilli(),
            (if (allDay) start.plusDays(1) else start.plusHours(1)).toInstant().toEpochMilli(), allDay)
    }

    private fun requests(vararg ranges: Pair<String, String>): JsonArray = buildJsonArray {
        ranges.forEach { (start, end) -> add(buildJsonObject { put("start_date", start); put("end_date", end) }) }
    }

    private fun JsonObject.results() = getValue("calendar_results").jsonArray
}

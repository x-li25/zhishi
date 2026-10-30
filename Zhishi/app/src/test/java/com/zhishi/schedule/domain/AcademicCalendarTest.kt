package com.zhishi.schedule.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.serialization.json.*

class AcademicCalendarTest {
    @Test
    fun `october 24 is week six and new week starts on Monday`() {
        assertEquals(6, AcademicCalendar.weekNumber(LocalDate.parse("2026-10-24")))
        assertEquals(6, AcademicCalendar.weekNumber(LocalDate.parse("2026-10-25")))
        assertEquals(7, AcademicCalendar.weekNumber(LocalDate.parse("2026-10-26")))
        assertEquals(LocalDate.parse("2026-10-28"), AcademicCalendar.date(7, 3))
    }

    @Test
    fun `all eighteen teaching weeks cover seven days without gaps`() {
        val expectedStart = LocalDate.parse("2026-09-14")
        (1..18).forEach { n ->
            val week = AcademicCalendar.week(n)
            assertEquals(expectedStart.plusDays(7L * (n - 1)), week.start)
            assertEquals(DayOfWeek.MONDAY, week.start.dayOfWeek)
            assertEquals(DayOfWeek.SUNDAY, week.end.dayOfWeek)
            (1..7).forEach { day ->
                assertEquals(n, AcademicCalendar.weekNumber(AcademicCalendar.date(n, day)))
            }
        }
        assertEquals(LocalDate.parse("2027-01-17"), AcademicCalendar.lastSunday)
    }

    @Test
    fun `holidays and year boundary do not restart or pause teaching weeks`() {
        assertEquals(2, AcademicCalendar.weekNumber(LocalDate.parse("2026-09-25")))
        assertEquals(3, AcademicCalendar.weekNumber(LocalDate.parse("2026-10-01")))
        assertEquals(4, AcademicCalendar.weekNumber(LocalDate.parse("2026-10-07")))
        assertEquals(16, AcademicCalendar.weekNumber(LocalDate.parse("2027-01-01")))
        assertEquals(17, AcademicCalendar.weekNumber(LocalDate.parse("2027-01-04")))
    }

    @Test
    fun `outside configured term has no invented week number`() {
        listOf("2026-09-13", "2027-01-18", "2027-02-22", "2027-10-24").forEach {
            assertNull(AcademicCalendar.weekNumber(LocalDate.parse(it)))
        }
        assertEquals("夏季学期", AcademicCalendar.label(LocalDate.parse("2026-09-13")))
        assertEquals("寒假（本科生校历）", AcademicCalendar.label(LocalDate.parse("2027-02-21")))
        assertEquals("未配置校历", AcademicCalendar.label(LocalDate.parse("2027-02-22")))
        assertEquals("第18周", AcademicCalendar.label(LocalDate.parse("2027-01-17")))
    }

    @Test
    fun `invalid week and weekday cannot resolve to adjacent terms`() {
        listOf(-1, 0, 19, Int.MAX_VALUE).forEach { n ->
            assertTrue(runCatching { AcademicCalendar.week(n) }.exceptionOrNull() is IllegalArgumentException)
        }
        listOf(0, 8).forEach { day ->
            assertTrue(runCatching { AcademicCalendar.date(7, day) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `compact academic context expands only named weeks including Chinese ranges`() {
        val today = LocalDate.parse("2026-10-24")
        val compact = AcademicCalendar.aiContext(today, "安排第八到十周周三的课程")
        assertTrue(compact.contains("本机换算第8周：2026-11-02 至 2026-11-08"))
        assertTrue(compact.contains("本机换算第10周：2026-11-16 至 2026-11-22"))
        assertFalse(compact.contains("本机换算第1周："))
        assertTrue(compact.contains("09:50–12:15"))
        assertFalse(AcademicCalendar.aiContext(today, "2027年春季第七周").contains("本机换算第7周"))
        assertTrue(AcademicCalendar.aiContext(today, "给我完整校历周表").contains("本机换算第18周："))
    }

    @Test fun `local lookup rejects impossible date week period and unknown term without inventing mappings`() {
        listOf("""{"kind":"academic","week":19}""", """{"kind":"academic","period":7}""",
            """{"kind":"academic","weekday":3}""", """{"kind":"academic","date":"2026-02-29"}""",
            """{"kind":"academic","week":7,"date":"2026-11-20"}""",
            """{"kind":"academic","week":7,"term":"spring"}""").forEach { request ->
            assertTrue(runCatching { AcademicCalendar.resolve(Json.parseToJsonElement(request).jsonObject) }.isFailure)
        }
        val winter = AcademicCalendar.resolve(Json.parseToJsonElement("""{"kind":"academic","date":"2027-01-18"}""").jsonObject)
        assertEquals("寒假（本科生校历）", winter["academic_label"]!!.jsonPrimitive.content)
        assertNull(winter["week"])
    }
}

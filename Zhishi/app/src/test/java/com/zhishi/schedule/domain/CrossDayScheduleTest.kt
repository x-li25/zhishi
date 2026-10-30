package com.zhishi.schedule.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CrossDayScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val monday = LocalDate.of(2026, 10, 12)
    private val engine = ScheduleEngine()

    @Test
    fun `event starting before the week remains visible on Monday`() {
        val event = event(monday.minusDays(1), 23, monday, 9)
        val days = engine.buildDays(monday, monday.plusDays(6), zone, listOf(event))
        assertEquals(listOf(event), days.first().events)
        assertTrue(days.drop(1).all { it.events.isEmpty() })
        assertEquals(TimeRange(at(monday, 0), at(monday, 9)), event.rangeOn(monday, zone))
        assertEquals("1:${event.startMillis}", ChecklistKeys.of(days.first().events.single()))
    }

    @Test
    fun `event ending at midnight does not appear on the next day`() {
        val event = event(monday, 22, monday.plusDays(1), 0)
        val days = engine.buildDays(monday, monday.plusDays(1), zone, listOf(event))
        assertEquals(listOf(event), days[0].events)
        assertTrue(days[1].events.isEmpty())
        assertNull(event.rangeOn(monday.plusDays(1), zone))
    }

    @Test
    fun `multi-day event is clipped independently on each day`() {
        val event = event(monday, 22, monday.plusDays(2), 2)
        val days = engine.buildDays(monday, monday.plusDays(2), zone, listOf(event))
        assertTrue(days.all { it.events == listOf(event) })
        assertEquals(TimeRange(at(monday.plusDays(1), 0), at(monday.plusDays(2), 0)),
            event.rangeOn(monday.plusDays(1), zone))
    }

    @Test
    fun `all-day events use calendar UTC dates in a negative offset timezone`() {
        val utc = ZoneId.of("UTC")
        val event = CalendarEvent(1, 1, "全天", monday.atStartOfDay(utc).toInstant().toEpochMilli(),
            monday.plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli(), allDay = true)
        val days = engine.buildDays(monday.minusDays(1), monday, ZoneId.of("America/Los_Angeles"), listOf(event))
        assertTrue(days[0].events.isEmpty())
        assertEquals(listOf(event), days[1].events)
    }

    private fun event(startDate: LocalDate, startHour: Int, endDate: LocalDate, endHour: Int) =
        CalendarEvent(1, 1, "跨天日程", at(startDate, startHour), at(endDate, endHour), allDay = false)

    private fun at(date: LocalDate, hour: Int): Long =
        date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()
}

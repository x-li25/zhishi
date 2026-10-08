package com.zhishi.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 9)
    private val engine = ScheduleEngine()

    @Test
    fun `free time excludes busy events and buffers`() {
        val busyStart = at(10, 0)
        val busyEnd = at(11, 0)
        val events = listOf(
            CalendarEvent(
                eventId = 1,
                calendarId = 1,
                title = "meeting",
                startMillis = busyStart,
                endMillis = busyEnd,
                allDay = false
            )
        )

        val day = engine.buildDay(
            date = date,
            zoneId = zone,
            events = events,
            preferences = SchedulePreferences(
                dayStart = LocalTime.of(9, 0),
                dayEnd = LocalTime.of(12, 0),
                bufferMinutes = 10
            )
        )

        assertEquals(
            listOf(540L to 590L, 670L to 720L),
            day.freeRanges.map { it.startOffsetMinutes() to it.endOffsetMinutes() }
        )
    }

    @Test
    fun `fully busy day produces no free ranges`() {
        val events = listOf(
            CalendarEvent(
                eventId = 1,
                calendarId = 1,
                title = "整天会议",
                startMillis = at(7, 0),
                endMillis = at(23, 0),
                allDay = false
            )
        )

        val day = engine.buildDay(
            date = date,
            zoneId = zone,
            events = events,
            preferences = SchedulePreferences()
        )

        assertTrue(day.freeRanges.isEmpty())
    }

    @Test
    fun `all-day events do not consume free time`() {
        val events = listOf(
            CalendarEvent(
                eventId = 1,
                calendarId = 1,
                title = "生日",
                startMillis = at(0, 0),
                endMillis = at(23, 59),
                allDay = true
            )
        )

        val day = engine.buildDay(
            date = date,
            zoneId = zone,
            events = events,
            preferences = SchedulePreferences(
                dayStart = LocalTime.of(9, 0),
                dayEnd = LocalTime.of(12, 0)
            )
        )

        assertEquals(1, day.freeRanges.size)
        assertEquals(180L, day.freeRanges.first().durationMinutes)
    }

    @Test
    fun `overlapping events merge into one busy block`() {
        val events = listOf(
            CalendarEvent(1, 1, "a", at(9, 0), at(10, 30), allDay = false),
            CalendarEvent(2, 1, "b", at(10, 0), at(11, 0), allDay = false)
        )

        val day = engine.buildDay(
            date = date,
            zoneId = zone,
            events = events,
            preferences = SchedulePreferences(
                dayStart = LocalTime.of(9, 0),
                dayEnd = LocalTime.of(12, 0),
                bufferMinutes = 0
            )
        )

        assertEquals(
            listOf(660L to 720L),
            day.freeRanges.map { it.startOffsetMinutes() to it.endOffsetMinutes() }
        )
    }

    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()

    private fun TimeRange.startOffsetMinutes(): Long =
        (startMillis - at(0, 0)) / 60_000L

    private fun TimeRange.endOffsetMinutes(): Long =
        (endMillis - at(0, 0)) / 60_000L
}

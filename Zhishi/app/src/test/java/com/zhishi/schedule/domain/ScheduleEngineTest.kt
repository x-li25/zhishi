package com.zhishi.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `meal suggestion is inside lunch window`() {
        val day = engine.buildDay(
            date = date,
            zoneId = zone,
            events = emptyList(),
            preferences = SchedulePreferences(
                dayStart = LocalTime.of(8, 0),
                dayEnd = LocalTime.of(20, 0),
                lunchStart = LocalTime.of(11, 30),
                lunchEnd = LocalTime.of(13, 30)
            )
        )

        val lunch = day.suggestions.first {
            it.type == SuggestionType.MEAL && it.range.startOffsetMinutes() == 690L
        }
        assertEquals(690L, lunch.range.startOffsetMinutes())
        assertEquals(45L, lunch.range.durationMinutes)
    }

    @Test
    fun `study suggestion is capped at ninety minutes`() {
        val day = engine.buildDay(
            date = date,
            zoneId = zone,
            events = emptyList(),
            preferences = SchedulePreferences(
                dayStart = LocalTime.of(8, 0),
                dayEnd = LocalTime.of(23, 0)
            )
        )

        val study = day.suggestions.first { it.type == SuggestionType.STUDY }
        assertTrue(study.range.durationMinutes <= 90L)
    }

    @Test
    fun `study suggestions never overlap meal suggestions`() {
        val day = engine.buildDay(
            date = date,
            zoneId = zone,
            events = emptyList(),
            preferences = SchedulePreferences(
                dayStart = LocalTime.of(8, 0),
                dayEnd = LocalTime.of(20, 0),
                studyStart = LocalTime.of(8, 0),
                studyEnd = LocalTime.of(20, 0),
                lunchStart = LocalTime.of(11, 30),
                lunchEnd = LocalTime.of(13, 30),
                minStudyMinutes = 30
            )
        )

        val meals = day.suggestions.filter { it.type == SuggestionType.MEAL }
        val studies = day.suggestions.filter { it.type == SuggestionType.STUDY }
        assertTrue(meals.isNotEmpty())
        assertTrue(studies.isNotEmpty())
        studies.forEach { study ->
            meals.forEach { meal ->
                assertFalse(
                    "学习建议 ${study.range} 与餐时建议 ${meal.range} 重叠",
                    study.range.overlaps(meal.range)
                )
            }
        }
    }

    @Test
    fun `fully busy day produces no free ranges or suggestions`() {
        val events = listOf(
            CalendarEvent(
                eventId = 1,
                calendarId = 1,
                title = "全天会议",
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
        assertTrue(day.suggestions.isEmpty())
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

    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()

    private fun TimeRange.startOffsetMinutes(): Long =
        (startMillis - at(0, 0)) / 60_000L

    private fun TimeRange.endOffsetMinutes(): Long =
        (endMillis - at(0, 0)) / 60_000L
}

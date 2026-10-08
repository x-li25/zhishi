package com.zhishi.schedule.domain

import com.zhishi.schedule.util.AppDateFormat
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min

class ScheduleEngine {

    fun buildDay(
        date: LocalDate,
        zoneId: ZoneId,
        events: List<CalendarEvent>,
        preferences: SchedulePreferences
    ): DaySchedule {
        val dayStart = AppDateFormat.millis(date, preferences.dayStart, zoneId)
        val dayEnd = AppDateFormat.millis(date, preferences.dayEnd, zoneId)
        val busy = events
            .asSequence()
            .filterNot { it.allDay }
            .map { TimeRange(it.startMillis, it.endMillis) }
            .filter { it.endMillis > it.startMillis }
            .map { it.expand(preferences.bufferMinutes) }
            .toList()

        val free = if (dayEnd > dayStart) {
            subtract(TimeRange(dayStart, dayEnd), merge(busy))
        } else {
            emptyList()
        }
        return DaySchedule(
            date = date,
            zoneId = zoneId,
            events = events.sortedBy { it.startMillis },
            freeRanges = free
        )
    }

    private fun merge(ranges: List<TimeRange>): List<TimeRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.startMillis }
        val result = mutableListOf(sorted.first())
        sorted.drop(1).forEach { current ->
            val last = result.last()
            if (current.startMillis <= last.endMillis) {
                result[result.lastIndex] = TimeRange(last.startMillis, maxOf(last.endMillis, current.endMillis))
            } else {
                result += current
            }
        }
        return result
    }

    private fun subtract(freeRange: TimeRange, busyRanges: List<TimeRange>): List<TimeRange> {
        var cursor = freeRange.startMillis
        val result = mutableListOf<TimeRange>()
        busyRanges.forEach { busy ->
            if (busy.endMillis <= cursor) return@forEach
            if (busy.startMillis >= freeRange.endMillis) return@forEach
            val start = maxOf(cursor, busy.startMillis)
            val end = min(busy.endMillis, freeRange.endMillis)
            if (start > cursor) result += TimeRange(cursor, start)
            cursor = maxOf(cursor, end)
        }
        if (cursor < freeRange.endMillis) result += TimeRange(cursor, freeRange.endMillis)
        return result
    }

    private fun TimeRange.expand(minutes: Int): TimeRange {
        val delta = minutes.coerceAtLeast(0) * 60_000L
        return TimeRange(startMillis - delta, endMillis + delta)
    }
}

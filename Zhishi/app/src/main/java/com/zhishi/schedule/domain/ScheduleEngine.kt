package com.zhishi.schedule.domain

import com.zhishi.schedule.util.AppDateFormat
import java.time.Instant
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
            freeRanges = free,
            suggestions = buildSuggestions(date, zoneId, free, preferences)
        )
    }

    private fun buildSuggestions(
        date: LocalDate,
        zoneId: ZoneId,
        freeRanges: List<TimeRange>,
        preferences: SchedulePreferences
    ): List<ScheduleSuggestion> {
        val result = mutableListOf<ScheduleSuggestion>()
        val lunchStart = AppDateFormat.millis(date, preferences.lunchStart, zoneId)
        val lunchEnd = AppDateFormat.millis(date, preferences.lunchEnd, zoneId)
        val dinnerStart = AppDateFormat.millis(date, preferences.dinnerStart, zoneId)
        val dinnerEnd = AppDateFormat.millis(date, preferences.dinnerEnd, zoneId)
        val studyStart = AppDateFormat.millis(date, preferences.studyStart, zoneId)
        val studyEnd = AppDateFormat.millis(date, preferences.studyEnd, zoneId)

        // 餐时建议会占用空闲时间，后续建议只能从剩余空闲中选取，避免互相重叠。
        var remaining = freeRanges
        addMealSuggestion(
            result = result,
            freeRanges = remaining,
            preferred = TimeRange(lunchStart, lunchEnd),
            title = "午餐",
            mealName = "午饭",
            durationMinutes = 45L
        )?.let { remaining = remaining.flatMap { range -> subtractRange(range, it) } }
        addMealSuggestion(
            result = result,
            freeRanges = remaining,
            preferred = TimeRange(dinnerStart, dinnerEnd),
            title = "晚餐",
            mealName = "晚饭",
            durationMinutes = 45L
        )?.let { remaining = remaining.flatMap { range -> subtractRange(range, it) } }

        remaining
            .mapNotNull { it.intersect(TimeRange(studyStart, studyEnd)) }
            .filter { it.durationMinutes >= preferences.minStudyMinutes }
            .sortedBy {
                val hour = Instant.ofEpochMilli(it.startMillis).atZone(zoneId).hour
                if (hour in 9 until 12) 0 else 1
            }
            .take(2)
            .sortedBy { it.startMillis }
            .forEachIndexed { index, range ->
                val minutes = min(range.durationMinutes, 90L)
                val suggestedRange = TimeRange(
                    range.startMillis,
                    range.startMillis + minutes * 60_000L
                )
                result += ScheduleSuggestion(
                    type = SuggestionType.STUDY,
                    title = if (index == 0) "建议学习时段" else "备选学习时段",
                    range = suggestedRange,
                    reason = "这段空闲时间较完整，适合安排 $minutes 分钟学习。"
                )
            }

        return result.sortedBy { it.range.startMillis }
    }

    /** 返回餐时建议实际占用的区间；没有合适位置时返回 null。 */
    private fun addMealSuggestion(
        result: MutableList<ScheduleSuggestion>,
        freeRanges: List<TimeRange>,
        preferred: TimeRange,
        title: String,
        mealName: String,
        durationMinutes: Long
    ): TimeRange? {
        val slot = freeRanges
            .mapNotNull { it.intersect(preferred) }
            .filter { it.durationMinutes >= durationMinutes }
            .minByOrNull { kotlin.math.abs(it.startMillis - preferred.startMillis) }
            ?: return null

        val suggestedRange = TimeRange(
            slot.startMillis,
            minOf(slot.endMillis, slot.startMillis + durationMinutes * 60_000L)
        )
        result += ScheduleSuggestion(
            type = SuggestionType.MEAL,
            title = title,
            range = suggestedRange,
            reason = "在${mealName}偏好时间内有空闲，建议预留至少 $durationMinutes 分钟。"
        )
        return suggestedRange
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

    /** 从单个空闲区间中扣除一个占用区间，返回剩余（0 到 2 段）。 */
    private fun subtractRange(free: TimeRange, busy: TimeRange): List<TimeRange> {
        val start = maxOf(free.startMillis, busy.startMillis)
        val end = min(free.endMillis, busy.endMillis)
        if (start >= end) return listOf(free)
        val result = mutableListOf<TimeRange>()
        if (free.startMillis < start) result += TimeRange(free.startMillis, start)
        if (end < free.endMillis) result += TimeRange(end, free.endMillis)
        return result
    }

    private fun TimeRange.expand(minutes: Int): TimeRange {
        val delta = minutes.coerceAtLeast(0) * 60_000L
        return TimeRange(startMillis - delta, endMillis + delta)
    }

    private fun TimeRange.intersect(other: TimeRange): TimeRange? {
        val start = maxOf(startMillis, other.startMillis)
        val end = min(endMillis, other.endMillis)
        return if (start < end) TimeRange(start, end) else null
    }
}

package com.zhishi.schedule.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 按日期整理日程，保留跨天实例的原始信息供周视图显示。 */
class ScheduleEngine {

    fun buildDays(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId,
        events: List<CalendarEvent>
    ): List<DaySchedule> {
        require(!endDate.isBefore(startDate)) { "日期范围无效" }
        return (0L..ChronoUnit.DAYS.between(startDate, endDate)).map { offset ->
            buildDay(startDate.plusDays(offset), zoneId, events)
        }
    }

    fun buildDay(
        date: LocalDate,
        zoneId: ZoneId,
        events: List<CalendarEvent>
    ): DaySchedule {
        val dayEvents = events.filter { it.rangeOn(date, zoneId) != null }
        return DaySchedule(
            date = date,
            zoneId = zoneId,
            events = dayEvents.sortedBy { it.startMillis }
        )
    }
}

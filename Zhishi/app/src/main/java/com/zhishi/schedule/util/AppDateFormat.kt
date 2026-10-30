package com.zhishi.schedule.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object AppDateFormat {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE")
    private val shortDateFormatter = DateTimeFormatter.ofPattern("M月d日")
    private val weekdayFormatter = DateTimeFormatter.ofPattern("EEEE")
    private val fullDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)
    private val fullDateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)

    fun time(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zoneId).format(timeFormatter)

    fun date(date: LocalDate): String = date.format(dateFormatter)

    fun shortDate(date: LocalDate): String = date.format(shortDateFormatter)

    fun weekday(date: LocalDate): String = date.format(weekdayFormatter)

    fun fullDateTime(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zoneId).format(fullDateTimeFormatter)

    /** 系统日历的全天事件按 UTC 日期解释，结束时间是排他边界。 */
    fun allDayDateRange(startMillis: Long, endMillis: Long): String {
        val start = Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val end = Instant.ofEpochMilli(maxOf(startMillis, endMillis - 1)).atZone(ZoneOffset.UTC).toLocalDate()
        return if (start == end) "${start.format(fullDateFormatter)} · 全天"
        else "${start.format(fullDateFormatter)} 至 ${end.format(fullDateFormatter)} · 全天"
    }

    fun timeRange(startMillis: Long, endMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        "${time(startMillis, zoneId)} - ${time(endMillis, zoneId)}"

    fun millis(date: LocalDate, time: LocalTime, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli()
}

package com.zhishi.schedule.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AppDateFormat {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("M月d日 EEEE")
    private val shortDateFormatter = DateTimeFormatter.ofPattern("M月d日")
    private val weekdayFormatter = DateTimeFormatter.ofPattern("EEEE")

    fun time(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zoneId).format(timeFormatter)

    fun date(date: LocalDate): String = date.format(dateFormatter)

    fun shortDate(date: LocalDate): String = date.format(shortDateFormatter)

    fun weekday(date: LocalDate): String = date.format(weekdayFormatter)

    fun timeRange(startMillis: Long, endMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        "${time(startMillis, zoneId)} - ${time(endMillis, zoneId)}"

    fun millis(date: LocalDate, time: LocalTime, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        LocalDateTime.of(date, time).atZone(zoneId).toInstant().toEpochMilli()
}

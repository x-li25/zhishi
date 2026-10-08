package com.zhishi.schedule.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.ParsedActionType
import com.zhishi.schedule.domain.ParsedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

class CalendarRepository(private val context: Context) {

    fun hasReadPermission(): Boolean = hasPermission(Manifest.permission.READ_CALENDAR)

    fun hasWritePermission(): Boolean = hasPermission(Manifest.permission.WRITE_CALENDAR)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun eventsForDate(
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<CalendarEvent> = eventsForRange(date, date, zoneId)

    suspend fun eventsForRange(
        startDate: LocalDate,
        endDate: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()

        val startOfDay = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endOfDay = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.RRULE
        )
        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startOfDay.toString())
            .appendPath(endOfDay.toString())
            .build()

        data class RawEvent(
            val eventId: Long,
            val calendarId: Long,
            val title: String,
            val startMillis: Long,
            val endMillis: Long,
            val allDay: Boolean,
            val location: String?,
            val isRecurring: Boolean
        )

        val raw = mutableListOf<RawEvent>()
        context.contentResolver.query(
            instancesUri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val calendarIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val startIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val rruleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.RRULE)

            while (cursor.moveToNext()) {
                val start = cursor.getLong(startIndex)
                val rawEnd = if (cursor.isNull(endIndex)) start + 60 * 60 * 1000 else cursor.getLong(endIndex)
                val end = if (rawEnd <= start) start + 60 * 60 * 1000 else rawEnd
                raw += RawEvent(
                    eventId = cursor.getLong(idIndex),
                    calendarId = cursor.getLong(calendarIndex),
                    title = cursor.getString(titleIndex).orEmpty().ifBlank { "未命名日程" },
                    startMillis = start,
                    endMillis = end,
                    allDay = cursor.getInt(allDayIndex) == 1,
                    location = cursor.getString(locationIndex),
                    isRecurring = !cursor.isNull(rruleIndex) && cursor.getString(rruleIndex).isNotBlank()
                )
            }
        }

        // 提醒批量查询一次，避免每个事件都单独访问一次 Reminders 表。
        val reminders = remindersForEvents(raw.map { it.eventId }.distinct())
        raw.map { event ->
            CalendarEvent(
                eventId = event.eventId,
                calendarId = event.calendarId,
                title = event.title,
                startMillis = event.startMillis,
                endMillis = event.endMillis,
                allDay = event.allDay,
                location = event.location,
                reminderMinutes = reminders[event.eventId].orEmpty(),
                isRecurring = event.isRecurring
            )
        }
    }

    suspend fun firstWritableCalendarId(): Long? = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) return@withContext null
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val candidates = mutableListOf<Pair<Long, Boolean>>()

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val primaryIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
            while (cursor.moveToNext()) {
                candidates += cursor.getLong(idIndex) to (cursor.getInt(primaryIndex) == 1)
            }
        }
        candidates.firstOrNull { it.second }?.first ?: candidates.firstOrNull()?.first
    }

    suspend fun insertEvent(
        event: ParsedEvent,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long = withContext(Dispatchers.IO) {
        check(hasWritePermission()) { "没有日历写入权限" }
        val calendarId = firstWritableCalendarId() ?: error("手机中没有可写入的日历账户")

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DTSTART, event.startMillis)
            put(CalendarContract.Events.DTEND, event.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, zoneId.id)
            put(CalendarContract.Events.EVENT_LOCATION, event.location)
            put(CalendarContract.Events.HAS_ALARM, if (event.reminderMinutes.isEmpty()) 0 else 1)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("写入系统日历失败")
        val eventId = ContentUris.parseId(uri)

        replaceReminders(eventId, event.reminderMinutes)
        eventId
    }

    suspend fun applyEvent(event: ParsedEvent, zoneId: ZoneId = ZoneId.systemDefault()) {
        when (event.action) {
            ParsedActionType.CREATE -> insertEvent(event, zoneId)
            ParsedActionType.UPDATE -> updateEvent(event, zoneId)
            ParsedActionType.DELETE -> deleteEvent(event)
        }
    }

    suspend fun updateEvent(event: ParsedEvent, zoneId: ZoneId = ZoneId.systemDefault()) {
        withContext(Dispatchers.IO) {
            check(hasWritePermission()) { "没有日历写入权限" }
            val eventId = event.targetEventId ?: error("缺少要修改的日程 ID")
            ensureEditable(eventId)
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, event.title)
                put(CalendarContract.Events.DTSTART, event.startMillis)
                put(CalendarContract.Events.DTEND, event.endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, zoneId.id)
                put(CalendarContract.Events.EVENT_LOCATION, event.location)
                put(CalendarContract.Events.HAS_ALARM, if (event.reminderMinutes.isEmpty()) 0 else 1)
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            if (context.contentResolver.update(uri, values, null, null) <= 0) {
                error("日程不存在或已被其他应用修改")
            }
            replaceReminders(eventId, event.reminderMinutes)
        }
    }

    suspend fun deleteEvent(event: ParsedEvent) {
        deleteEventById(event.targetEventId ?: error("缺少要删除的日程 ID"))
    }

    /** 手动删除入口：按事件 ID 删除，沿用全天/重复日程保护。 */
    suspend fun deleteEventById(eventId: Long) {
        withContext(Dispatchers.IO) {
            check(hasWritePermission()) { "没有日历写入权限" }
            ensureEditable(eventId)
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            if (context.contentResolver.delete(uri, null, null) <= 0) {
                error("日程不存在或已被其他应用删除")
            }
        }
    }

    private fun replaceReminders(eventId: Long, minutes: List<Int>) {
        context.contentResolver.delete(
            CalendarContract.Reminders.CONTENT_URI,
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString())
        )
        minutes.distinct().filter { it >= 0 }.forEach { minute ->
            val reminder = ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, minute)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder)
        }
    }

    /** 一次查询多个事件的提醒，按事件 ID 分组。 */
    private fun remindersForEvents(eventIds: List<Long>): Map<Long, List<Int>> {
        if (eventIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, MutableList<Int>>()
        eventIds.chunked(200).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val selection = "${CalendarContract.Reminders.EVENT_ID} IN ($placeholders)"
            val args = chunk.map { it.toString() }.toTypedArray()
            context.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES),
                selection,
                args,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(0)
                    result.getOrPut(eventId) { mutableListOf() } += cursor.getInt(1)
                }
            }
        }
        return result.mapValues { it.value.distinct() }
    }

    private fun ensureEditable(eventId: Long) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val projection = arrayOf(
            CalendarContract.Events.DELETED,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_ID
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                if (!cursor.isNull(0) && cursor.getInt(0) == 1) {
                    error("该日程已被删除")
                }
                if (!cursor.isNull(1) && cursor.getString(1).isNotBlank()) {
                    error("暂不支持直接修改重复日程，请在系统日历中调整该日程")
                }
                if (!cursor.isNull(2) && cursor.getInt(2) == 1) {
                    error("暂不支持修改全天日程，请在系统日历中调整该日程")
                }
                if (!isWritableCalendar(cursor.getLong(3))) {
                    error("该日程所在日历账户不允许写入")
                }
                return
            }
        }
        error("找不到要调整的日程")
    }

    private fun isWritableCalendar(calendarId: Long): Boolean {
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
        val projection = arrayOf(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
            }
        }
        return false
    }
}

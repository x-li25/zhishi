package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.AcademicCalendar
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.ChecklistKeys
import com.zhishi.schedule.domain.ParsedActionType
import com.zhishi.schedule.domain.ParsedEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** 一次提问内的按需读取、去重和预算；不复用上次提问的日历快照。 */
internal class CalendarReadSession(
    private val zoneId: ZoneId,
    private val reader: (suspend (LocalDate, LocalDate) -> List<CalendarEvent>)?
) {
    private data class Range(val start: LocalDate, val end: LocalDate)

    private val cache = mutableMapOf<Range, List<CalendarEvent>>()
    private val readDays = mutableSetOf<LocalDate>()
    private val completeDays = mutableSetOf<LocalDate>()
    private val loadedEvents = linkedMapOf<String, CalendarEvent>()
    val events: List<CalendarEvent> get() = loadedEvents.values.toList()
    val warnings = linkedSetOf<String>()

    fun coversAction(event: ParsedEvent): Boolean {
        if (event.action == ParsedActionType.DELETE) return true // 目标已由真实读取结果解析。
        val (start, end) = actionDates(event)
        val days = ChronoUnit.DAYS.between(start, end) + 1
        return days in 1..MAX_DAYS.toLong() && (0 until days).all { start.plusDays(it) in completeDays }
    }

    fun unreadActionRanges(events: List<ParsedEvent>): JsonArray = buildJsonArray {
        events.filterNot { coversAction(it) }.map { actionDates(it) }.distinct().forEach { (start, end) ->
            add(buildJsonObject { put("start_date", start.toString()); put("end_date", end.toString()) })
        }
    }

    private fun actionDates(event: ParsedEvent): Pair<LocalDate, LocalDate> =
        Instant.ofEpochMilli(event.startMillis).atZone(zoneId).toLocalDate() to
            Instant.ofEpochMilli(event.endMillis - 1).atZone(zoneId).toLocalDate()

    suspend fun read(requests: JsonArray): JsonObject = buildJsonObject {
        put("calendar_results", buildJsonArray {
            requests.take(MAX_REQUESTS_PER_ROUND).forEach { request ->
                add(readOne(request as? JsonObject))
            }
            if (requests.size > MAX_REQUESTS_PER_ROUND) {
                add(errorResult("每轮最多读取 $MAX_REQUESTS_PER_ROUND 个日期范围，其他范围尚未读取"))
            }
        })
    }

    private suspend fun readOne(request: JsonObject?): JsonObject {
        val resolved = runCatching { resolveRange(request) }
        val range = resolved.getOrNull()
            ?: return errorResult("${resolved.exceptionOrNull()?.message ?: "无效日期范围"}；范围尚未读取")

        val days = ChronoUnit.DAYS.between(range.start, range.end) + 1
        if (days !in 1..MAX_DAYS.toLong()) {
            return errorResult("起止日期须按先后顺序，单次提问累计最多读取 $MAX_DAYS 天；请缩小范围")
        }
        if (reader == null) return errorResult("日历读取不可用，不能据此判断用户是否有安排")
        val dates = (0 until days).map { range.start.plusDays(it) }
        if ((readDays + dates).size > MAX_DAYS) {
            return errorResult("本次提问累计最多读取 $MAX_DAYS 天，此范围尚未读取；请缩小范围")
        }

        // 仅在成功返回后记录范围。权限或系统日历异常向上传递，不能冒充空日程。
        val events = cache.getOrPut(range) {
            reader.invoke(range.start, range.end)
                .filter { event ->
                    val zone = if (event.allDay) ZoneOffset.UTC else zoneId
                    event.startMillis < range.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() &&
                        event.endMillis > range.start.atStartOfDay(zone).toInstant().toEpochMilli()
                }
                .distinctBy { ChecklistKeys.of(it) }
                .sortedBy { it.startMillis }
        }
        readDays.addAll(dates)
        // 超大范围不静默裁剪：模型可继续请求较小范围，获取遗漏的事件。
        val visible = events.take(MAX_EVENTS_PER_RANGE)
        visible.forEach { loadedEvents[ChecklistKeys.of(it)] = it }
        if (visible.size == events.size) completeDays.addAll(dates)
        if (visible.size < events.size) {
            warnings += "${range.start} 至 ${range.end} 有 ${events.size} 条日程，本次仅展示前 $MAX_EVENTS_PER_RANGE 条；需缩小日期范围查看其余日程"
        }
        return buildJsonObject {
            put("start_date", range.start.toString())
            put("end_date", range.end.toString())
            put("end_inclusive", true)
            put("total_events", events.size)
            put("complete", visible.size == events.size)
            put("events", buildJsonArray {
                visible.forEach { event ->
                    add(buildJsonObject {
                        put("event_id", event.eventId)
                        put("title", event.title)
                        put("all_day", event.allDay)
                        put("recurring", event.isRecurring)
                        if (event.allDay) {
                            // CalendarContract 的全天日期按 UTC 存储，end_date 为排他边界。
                            put("start_date", event.start.atZone(ZoneOffset.UTC).toLocalDate().toString())
                            put("end_date_exclusive", event.end.atZone(ZoneOffset.UTC).toLocalDate().toString())
                        } else {
                            put("start", Instant.ofEpochMilli(event.startMillis).atZone(zoneId).toOffsetDateTime().toString())
                            put("end", Instant.ofEpochMilli(event.endMillis).atZone(zoneId).toOffsetDateTime().toString())
                        }
                        event.location?.let { put("location", it) }
                    })
                }
            })
            if (visible.size < events.size) put("note", "结果不完整，请缩小日期范围后重新读取，不能据此得出完整查询结论")
        }
    }

    /** 在本地解析教学周，避免模型自行换算时偏移一周或跨年出错。 */
    private fun resolveRange(request: JsonObject?): Range {
        requireNotNull(request) { "读取请求须为 JSON 对象" }
        fun integer(key: String): Int =
            requireNotNull((request[key] as? JsonPrimitive)?.intOrNull) { "$key 须为整数" }

        val hasWeek = listOf("week", "start_week", "end_week", "weekday").any { it in request }
        val weekRange = if (hasWeek) {
            if ("week" in request) {
                require("start_week" !in request && "end_week" !in request) { "week 不能与 start_week/end_week 混用" }
                val week = AcademicCalendar.week(integer("week"))
                if ("weekday" in request) {
                    val date = AcademicCalendar.date(week.number, integer("weekday"))
                    Range(date, date)
                } else Range(week.start, week.end)
            } else {
                require("weekday" !in request) { "weekday 只能与单个 week 一起使用；不同周的单日请分开请求" }
                val start = AcademicCalendar.week(integer("start_week"))
                val end = AcademicCalendar.week(integer("end_week"))
                require(start.number <= end.number) { "start_week 不能晚于 end_week" }
                Range(start.start, end.end)
            }
        } else null
        val dates = if ("start_date" in request || "end_date" in request) {
            val start = (request["start_date"] as? JsonPrimitive)?.contentOrNull
            val end = (request["end_date"] as? JsonPrimitive)?.contentOrNull
            require(start?.matches(DATE_PATTERN) == true && end?.matches(DATE_PATTERN) == true) {
                "日期必须是有效的 YYYY-MM-DD，且同时提供 start_date/end_date"
            }
            Range(LocalDate.parse(start), LocalDate.parse(end))
        } else null
        require(weekRange == null || dates == null || weekRange == dates) { "周次与显式日期不一致，请使用校历对应日期" }
        return requireNotNull(weekRange ?: dates) { "须提供日期范围或已配置的教学周次" }
    }

    private fun errorResult(message: String): JsonObject = buildJsonObject {
        put("error", message)
        put("complete", false)
    }

    companion object {
        const val MAX_DAYS = 366
        const val MAX_REQUESTS_PER_ROUND = 4
        const val MAX_EVENTS_PER_RANGE = 200
        private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}

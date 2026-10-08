package com.zhishi.schedule.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String? = null,
    val reminderMinutes: List<Int> = emptyList(),
    val isRecurring: Boolean = false
) {
    val start: Instant get() = Instant.ofEpochMilli(startMillis)
    val end: Instant get() = Instant.ofEpochMilli(endMillis)
}

data class TimeRange(
    val startMillis: Long,
    val endMillis: Long
) {
    val durationMinutes: Long get() = Duration.ofMillis(endMillis - startMillis).toMinutes()
    fun overlaps(other: TimeRange): Boolean = startMillis < other.endMillis && other.startMillis < endMillis
}

data class SchedulePreferences(
    val dayStart: LocalTime = LocalTime.of(7, 0),
    val dayEnd: LocalTime = LocalTime.of(23, 0),
    val studyStart: LocalTime = LocalTime.of(8, 0),
    val studyEnd: LocalTime = LocalTime.of(22, 0),
    val lunchStart: LocalTime = LocalTime.of(11, 30),
    val lunchEnd: LocalTime = LocalTime.of(13, 30),
    val dinnerStart: LocalTime = LocalTime.of(17, 0),
    val dinnerEnd: LocalTime = LocalTime.of(19, 30),
    val minStudyMinutes: Int = 30,
    val bufferMinutes: Int = 10
)

enum class SuggestionType { STUDY, MEAL, BREAK, OTHER }

data class ScheduleSuggestion(
    val type: SuggestionType,
    val title: String,
    val range: TimeRange,
    val reason: String
)

enum class ParsedActionType { CREATE, UPDATE, DELETE }

data class ParsedEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val reminderMinutes: List<Int> = emptyList(),
    val confidence: Double = 0.8,
    val action: ParsedActionType = ParsedActionType.CREATE,
    val targetEventId: Long? = null,
    val originalTitle: String? = null,
    val originalStartMillis: Long? = null,
    val originalEndMillis: Long? = null
)

data class AiParseResult(
    val summary: String,
    val events: List<ParsedEvent>,
    val questions: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val rawJson: String = ""
)

data class GlmConfig(
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val apiKey: String = "",
    val model: String = "glm-5.3-flash",
    val responseJsonMode: Boolean = true
) {
    fun isConfigured(): Boolean = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}

data class DaySchedule(
    val date: LocalDate,
    val zoneId: ZoneId,
    val events: List<CalendarEvent>,
    val freeRanges: List<TimeRange>,
    val suggestions: List<ScheduleSuggestion>
)

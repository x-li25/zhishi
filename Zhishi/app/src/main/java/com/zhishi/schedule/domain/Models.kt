package com.zhishi.schedule.domain

import kotlinx.serialization.Serializable
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
    val bufferMinutes: Int = 10
)

@Serializable
enum class ParsedActionType { CREATE, UPDATE, DELETE }

@Serializable
data class ParsedEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val location: String? = null,
    val reminderMinutes: List<Int> = emptyList(),
    val checklist: List<String> = emptyList(),
    val confidence: Double = 0.8,
    val action: ParsedActionType = ParsedActionType.CREATE,
    val targetEventId: Long? = null,
    val originalTitle: String? = null,
    val originalStartMillis: Long? = null,
    val originalEndMillis: Long? = null
)

@Serializable
data class ChecklistItem(
    val text: String,
    val done: Boolean = false
)

data class AiParseResult(
    val summary: String,
    val events: List<ParsedEvent>,
    val questions: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val rawJson: String = ""
)

@Serializable
enum class ChatRole { USER, ASSISTANT }

@Serializable
data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val timestamp: Long = 0L,
    val actions: List<ParsedEvent> = emptyList(),
    val warnings: List<String> = emptyList(),
    val executed: Boolean = false
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
    val freeRanges: List<TimeRange>
)

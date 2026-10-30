package com.zhishi.schedule.domain

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
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
    val isRecurring: Boolean = false,
    val description: String? = null
) {
    val start: Instant get() = Instant.ofEpochMilli(startMillis)
    val end: Instant get() = Instant.ofEpochMilli(endMillis)

    /** 保留实例原始开始时间作为清单 key，仅裁剪该日的显示区间。全天事件按 UTC 日期解释。 */
    fun rangeOn(date: LocalDate, zoneId: ZoneId): TimeRange? {
        val dateZone = if (allDay) ZoneId.of("UTC") else zoneId
        val dayStart = date.atStartOfDay(dateZone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(dateZone).toInstant().toEpochMilli()
        val start = maxOf(startMillis, dayStart)
        val end = minOf(endMillis, dayEnd)
        return if (start < end) TimeRange(start, end) else null
    }
}

data class TimeRange(
    val startMillis: Long,
    val endMillis: Long
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
    val originalEndMillis: Long? = null,
    val weatherSensitive: Boolean = false
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
    val totalTokens: Int? = null,
    val imageAnalysis: String? = null,
    val imageText: String? = null
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
    val executed: Boolean = false,
    val tokenUsage: Int? = null,
    val imageFileName: String? = null,
    val imageText: String? = null,
    val imageAnalysis: String? = null
)

data class AvailableModel(
    val id: String,
    val displayName: String? = null,
    // null 表示接口未声明能力，不能根据模型名称推断。
    val supportsImageInput: Boolean? = null
)

data class ModelCatalogState(
    val models: List<AvailableModel> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null
)

data class GlmConfig(
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val apiKey: String = "",
    val model: String = "glm-5.3-flash",
    val responseJsonMode: Boolean = true,
    val visionModel: String = ""
) {
    fun isConfigured(): Boolean = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
    val imageModel: String get() = visionModel.trim().ifBlank { model.trim() }
}

data class DaySchedule(
    val date: LocalDate,
    val zoneId: ZoneId,
    val events: List<CalendarEvent>
)

/** 清单存储的 key 规则（事件 ID + 实例开始时间），UI 与存储共用。 */
object ChecklistKeys {
    fun of(event: CalendarEvent): String = "${event.eventId}:${event.startMillis}"
    fun of(eventId: Long, startMillis: Long): String = "$eventId:$startMillis"
}

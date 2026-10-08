package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.AiParseResult
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.GlmConfig
import com.zhishi.schedule.domain.ParsedActionType
import com.zhishi.schedule.domain.ParsedEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class GlmClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun interpret(
        text: String,
        config: GlmConfig,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        existingEvents: List<CalendarEvent> = emptyList(),
        scheduleContext: String = ""
    ): AiParseResult = withContext(Dispatchers.IO) {
        require(config.isConfigured()) { "请先在设置中填写 API Key" }
        require(text.isNotBlank()) { "请输入日程描述文字" }

        val requestJson = buildJsonObject {
            put("model", config.model)
            put("temperature", 0.1)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt(now, zoneId, existingEvents, scheduleContext))
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", text.trim())
                })
            })
            if (config.responseJsonMode) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
        }

        val request = Request.Builder()
            .url(chatCompletionsUrl(config.baseUrl))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("AI 接口错误 ${response.code}: ${body.take(500)}")
            }
            parseResponse(body, zoneId, existingEvents)
        }
    }

    suspend fun testConnection(config: GlmConfig): String = withContext(Dispatchers.IO) {
        require(config.isConfigured()) { "请先填写 Base URL、API Key 和模型名称" }
        val requestJson = buildJsonObject {
            put("model", config.model)
            put("temperature", 0.0)
            put("max_tokens", 8)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "只回复 OK")
                })
            })
        }
        val request = Request.Builder()
            .url(chatCompletionsUrl(config.baseUrl))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("连接失败 ${response.code}: ${body.take(300)}")
            }
            val message = json.parseToJsonElement(body).jsonObject["choices"]
                ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?: error("接口已响应，但返回格式不兼容")
            extractMessageContent(message).trim().ifBlank { "接口已响应" }
        }
    }

    private fun parseResponse(
        body: String,
        zoneId: ZoneId,
        existingEvents: List<CalendarEvent>
    ): AiParseResult {
        val root = json.parseToJsonElement(body).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("AI 返回内容为空")
        val message = choice["message"]?.jsonObject ?: error("AI 返回内容为空")
        val content = extractMessageContent(message)
        val payload = extractJsonObject(content)

        val summary = readString(payload, "summary").orEmpty()
        val questions = payload["questions"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        val actions = payload["actions"]?.jsonArray ?: JsonArray(emptyList())

        val warnings = mutableListOf<String>()
        val events = actions.mapNotNull { element ->
            val action = element.jsonObject
            val type = readString(action, "type", "action").orEmpty().lowercase()
            when {
                type in CREATE_TYPES -> parseCreate(action, zoneId, warnings)
                type in UPDATE_TYPES -> parseUpdate(action, existingEvents, zoneId, warnings)
                type in DELETE_TYPES -> parseDelete(action, existingEvents, warnings)
                else -> {
                    warnings += "无法识别的操作类型“$type”，已忽略"
                    null
                }
            }
        }
        return AiParseResult(
            summary = summary.ifBlank { "已识别 ${events.size} 个日程操作" },
            events = events,
            questions = questions,
            warnings = warnings,
            rawJson = payload.toString()
        )
    }

    private fun parseCreate(action: JsonObject, zoneId: ZoneId, warnings: MutableList<String>): ParsedEvent? {
        val title = readString(action, "title", "summary").orEmpty().ifBlank { "新日程" }
        val start = readInstant(action, zoneId, "start", "start_time", "new_start", "begin")
        val end = readInstant(action, zoneId, "end", "end_time", "new_end", "finish")
        if (start == null || end == null) {
            warnings += "「$title」缺少可解析的开始或结束时间，已忽略"
            return null
        }
        if (end <= start) {
            warnings += "「$title」的结束时间早于开始时间，已忽略"
            return null
        }
        return ParsedEvent(
            title = title,
            startMillis = start,
            endMillis = end,
            reminderMinutes = readIntList(action, "reminders", "reminder_minutes"),
            confidence = readDouble(action, "confidence").coerceIn(0.0, 1.0),
            action = ParsedActionType.CREATE
        )
    }

    private fun parseUpdate(
        action: JsonObject,
        existingEvents: List<CalendarEvent>,
        zoneId: ZoneId,
        warnings: MutableList<String>
    ): ParsedEvent? {
        val target = resolveTarget(action, existingEvents)
        if (target == null) {
            warnings += "找不到要修改的目标日程，已忽略一条修改请求"
            return null
        }
        if (target.allDay) {
            warnings += "「${target.title}」是全天日程，暂不支持修改，请在系统日历中调整"
            return null
        }
        val title = readString(action, "title", "new_title").orEmpty().ifBlank { target.title }
        val start = readInstant(action, zoneId, "start", "start_time", "new_start", "begin")
            ?: target.startMillis
        val end = readInstant(action, zoneId, "end", "end_time", "new_end", "finish")
            ?: target.endMillis
        if (end <= start) {
            warnings += "「$title」的结束时间早于开始时间，已忽略"
            return null
        }
        val reminders = readIntList(action, "reminders", "reminder_minutes")
            .ifEmpty { target.reminderMinutes }
        return ParsedEvent(
            title = title,
            startMillis = start,
            endMillis = end,
            reminderMinutes = reminders,
            confidence = readDouble(action, "confidence").coerceIn(0.0, 1.0),
            action = ParsedActionType.UPDATE,
            targetEventId = target.eventId,
            originalTitle = target.title,
            originalStartMillis = target.startMillis,
            originalEndMillis = target.endMillis
        )
    }

    private fun parseDelete(
        action: JsonObject,
        existingEvents: List<CalendarEvent>,
        warnings: MutableList<String>
    ): ParsedEvent? {
        val target = resolveTarget(action, existingEvents)
        if (target == null) {
            warnings += "找不到要删除的目标日程，已忽略一条删除请求"
            return null
        }
        if (target.allDay) {
            warnings += "「${target.title}」是全天日程，暂不支持删除，请在系统日历中处理"
            return null
        }
        return ParsedEvent(
            title = target.title,
            startMillis = target.startMillis,
            endMillis = target.endMillis,
            reminderMinutes = target.reminderMinutes,
            confidence = readDouble(action, "confidence").coerceIn(0.0, 1.0),
            action = ParsedActionType.DELETE,
            targetEventId = target.eventId,
            originalTitle = target.title,
            originalStartMillis = target.startMillis,
            originalEndMillis = target.endMillis
        )
    }

    private fun resolveTarget(
        action: JsonObject,
        existingEvents: List<CalendarEvent>
    ): CalendarEvent? {
        val id = readLong(action, "event_id", "target_event_id", "calendar_event_id", "id")
        if (id != null) {
            existingEvents.firstOrNull { it.eventId == id }?.let { return it }
        }
        val title = readString(action, "target_title", "existing_title", "title").orEmpty().trim()
        if (title.isBlank()) return null
        val exact = existingEvents.filter { it.title.equals(title, ignoreCase = true) }
        if (exact.size == 1) return exact.first()
        if (exact.size > 1) return null
        val partial = existingEvents.filter {
            it.title.contains(title, ignoreCase = true) || title.contains(it.title, ignoreCase = true)
        }
        return partial.singleOrNull()
    }

    private fun readInstant(action: JsonObject, zoneId: ZoneId, vararg keys: String): Long? {
        val text = readString(action, *keys) ?: return null
        return parseInstant(text, zoneId)
    }

    private fun readString(obj: JsonObject, vararg keys: String): String? {
        keys.forEach { key ->
            val value = obj[key] ?: return@forEach
            val text = when (value) {
                is JsonPrimitive -> value.contentOrNull
                else -> null
            }
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun readLong(obj: JsonObject, vararg keys: String): Long? {
        keys.forEach { key ->
            val value = obj[key] as? JsonPrimitive ?: return@forEach
            value.longOrNull?.let { return it }
            value.contentOrNull?.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun readDouble(obj: JsonObject, key: String, fallback: Double = 0.8): Double {
        val value = obj[key] as? JsonPrimitive ?: return fallback
        return value.doubleOrNull ?: value.contentOrNull?.toDoubleOrNull() ?: fallback
    }

    private fun readIntList(obj: JsonObject, vararg keys: String): List<Int> {
        keys.forEach { key ->
            val value = obj[key] ?: return@forEach
            val list = when (value) {
                is JsonArray -> value.mapNotNull { it.jsonPrimitive.intOrNull }
                is JsonPrimitive -> value.contentOrNull
                    ?.split(',', '，')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    .orEmpty()
                else -> emptyList()
            }
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }

    private fun extractMessageContent(message: JsonObject): String {
        message["content"]?.let { content ->
            when (content) {
                is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
                is JsonArray -> {
                    val text = content.mapNotNull { item ->
                        when (item) {
                            is JsonPrimitive -> item.contentOrNull
                            is JsonObject -> readString(item, "text", "content")
                            else -> null
                        }
                    }.joinToString("\n").trim()
                    if (text.isNotBlank()) return text
                }
                else -> Unit
            }
        }
        message["tool_calls"]?.jsonArray?.forEach { call ->
            val arguments = call.jsonObject["function"]?.jsonObject
                ?.get("arguments")?.jsonPrimitive?.contentOrNull
            if (!arguments.isNullOrBlank()) return arguments
        }
        error("AI 返回内容为空")
    }

    private fun parseInstant(value: String, zoneId: ZoneId): Long? {
        val text = value.trim()
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(text).toEpochMilli() }
            .recoverCatching {
                LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(zoneId).toInstant().toEpochMilli()
            }
            .recoverCatching {
                // 兜底：模型偶尔返回 “2026-10-09 19:00” 这种空格分隔格式
                LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]"))
                    .atZone(zoneId).toInstant().toEpochMilli()
            }
            .getOrNull()
    }

    private fun extractJsonObject(content: String): JsonObject {
        val cleaned = content
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) error("AI 未返回可解析的 JSON")
        return json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonObject
    }

    private fun chatCompletionsUrl(baseUrl: String): String = endpointUrl(baseUrl, "/chat/completions")

    private fun endpointUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val suffix = path.trimStart('/')
        return if (base.endsWith("/" + suffix)) base else "$base/$suffix"
    }

    private fun systemPrompt(
        now: Instant,
        zoneId: ZoneId,
        existingEvents: List<CalendarEvent>,
        scheduleContext: String
    ): String {
        val localNow = now.atZone(zoneId)
        val eventList = if (existingEvents.isEmpty()) {
            "无"
        } else {
            existingEvents.joinToString("\n") { event ->
                if (event.allDay) {
                    "- id=${event.eventId} | title=${event.title} | 全天日程 | recurring=${event.isRecurring}"
                } else {
                    val start = Instant.ofEpochMilli(event.startMillis).atZone(zoneId)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    val end = Instant.ofEpochMilli(event.endMillis).atZone(zoneId)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    "- id=${event.eventId} | title=${event.title} | start=$start | end=$end | recurring=${event.isRecurring}"
                }
            }
        }
        return """
            你是知时日程助手。你的任务是把用户提供的文字内容转换成日程操作。
            只输出一个 JSON 对象，不要输出 Markdown、代码围栏、解释或额外文字。
            当前时间：${localNow.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}
            时区：${zoneId.id}
            今天日期：${localNow.toLocalDate()}
            星期：${localNow.dayOfWeek}

            规则：
            1. start/end 必须是带时区的 ISO-8601 时间，例如 2026-10-09T19:00:00+08:00。
            2. 用户说“明天、今晚、下周一”等相对时间时，必须基于当前时间计算绝对时间。
            3. 未说明结束时间时，可根据语义或常见时长推断；无法可靠推断时在 questions 中询问。
            4. 只有用户明确要求新增或安排内容时才输出 create_event。
            5. 只有用户明确要求修改、移动、改名或调整已有日程时才输出 update_event，并必须使用下方已有日程中的真实 id。
            6. 只有用户明确要求删除、取消已有日程时才输出 delete_event，并必须使用真实 id。
            7. 用户指代不明确、找不到目标日程或可能误删时，不要猜测 id；actions 为空，并在 questions 中说明需要确认什么。
            8. 多个新建日程会产生多个 create_event。
            9. 不要编造用户没有提供的标题、日期或关键时间。置信度用 0 到 1 表示。
            10. reminders 使用分钟数组，例如提前 10 分钟为 [10]；未提及时可省略。
            11. 标注“全天日程”的条目不支持修改或删除，不要对它们输出 update_event 或 delete_event。

            已有日程：
            $eventList

            本地排程参考：
            ${scheduleContext.ifBlank { "无" }}

            只输出以下结构：
            {
              "summary": "简短说明本次识别结果",
              "actions": [
                {
                  "type": "create_event",
                  "title": "复习数学",
                  "start": "2026-10-09T19:00:00+08:00",
                  "end": "2026-10-09T20:00:00+08:00",
                  "reminders": [10],
                  "confidence": 0.95
                },
                {
                  "type": "update_event",
                  "event_id": 123,
                  "title": "复习数学",
                  "start": "2026-10-09T20:00:00+08:00",
                  "end": "2026-10-09T21:00:00+08:00",
                  "confidence": 0.9
                },
                {
                  "type": "delete_event",
                  "event_id": 456,
                  "confidence": 0.9
                }
              ],
              "questions": []
            }
            actions 为空时也必须保留 actions 和 questions 字段。
        """.trimIndent()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val CREATE_TYPES = setOf("create_event", "create", "add_event")
        private val UPDATE_TYPES = setOf(
            "update_event", "update", "move_event", "reschedule_event", "edit_event"
        )
        private val DELETE_TYPES = setOf("delete_event", "delete", "cancel_event", "remove_event")
    }
}

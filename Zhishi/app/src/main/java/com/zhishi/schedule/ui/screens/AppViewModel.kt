package com.zhishi.schedule.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.ai.GlmClient
import com.zhishi.schedule.data.CalendarRepository
import com.zhishi.schedule.data.ChatStore
import com.zhishi.schedule.data.ChecklistStore
import com.zhishi.schedule.data.ConfigStore
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.ChatMessage
import com.zhishi.schedule.domain.ChatRole
import com.zhishi.schedule.domain.ChecklistItem
import com.zhishi.schedule.domain.DaySchedule
import com.zhishi.schedule.domain.GlmConfig
import com.zhishi.schedule.domain.ParsedActionType
import com.zhishi.schedule.domain.ParsedEvent
import com.zhishi.schedule.domain.ScheduleEngine
import com.zhishi.schedule.domain.SchedulePreferences
import com.zhishi.schedule.util.AppDateFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class AppUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val daySchedule: DaySchedule? = null,
    val weekStart: LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
    val weekDays: List<DaySchedule> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val calendarPermissionGranted: Boolean = false,
    val textInput: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val parsing: Boolean = false,
    val glmConfig: GlmConfig = GlmConfig(),
    val preferences: SchedulePreferences = SchedulePreferences(),
    val testingConnection: Boolean = false,
    val checklists: Map<String, List<ChecklistItem>> = emptyMap(),
    val ratings: Map<String, Int> = emptyMap(),
    val detailEvent: CalendarEvent? = null,
    val pendingDeleteEvent: CalendarEvent? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val calendarRepository = CalendarRepository(application)
    private val configStore = ConfigStore(application)
    private val checklistStore = ChecklistStore(application)
    private val chatStore = ChatStore(application)
    private val glmClient = GlmClient()
    private val scheduleEngine = ScheduleEngine()

    private var messageCounter = 0L

    private val initialConfig = loadConfigSafely()

    private val _state = MutableStateFlow(
        AppUiState(
            glmConfig = initialConfig.first,
            preferences = configStore.loadSchedulePreferences(),
            calendarPermissionGranted = calendarRepository.hasReadPermission(),
            checklists = checklistStore.load(),
            ratings = checklistStore.loadRatings(),
            messages = chatStore.load(),
            error = initialConfig.second
        )
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        refreshDay()
        refreshWeek()
    }

    fun refreshCalendarPermission() {
        _state.value = _state.value.copy(
            calendarPermissionGranted = calendarRepository.hasReadPermission()
        )
        refreshDay()
        refreshWeek()
    }

    fun refreshDay() {
        val current = _state.value
        _state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                val events = calendarRepository.eventsForDate(current.selectedDate)
                scheduleEngine.buildDay(
                    date = current.selectedDate,
                    zoneId = ZoneId.systemDefault(),
                    events = events,
                    preferences = current.preferences
                )
            }.onSuccess { day ->
                _state.value = _state.value.copy(daySchedule = day, loading = false, error = null)
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = error.message ?: "读取日程失败"
                )
            }
        }
    }

    fun selectDate(date: LocalDate) {
        _state.value = _state.value.copy(selectedDate = date)
        refreshDay()
    }

    fun refreshWeek() {
        val weekStart = _state.value.weekStart
        val preferences = _state.value.preferences
        viewModelScope.launch {
            runCatching {
                val zoneId = ZoneId.systemDefault()
                val events = calendarRepository.eventsForRange(weekStart, weekStart.plusDays(6), zoneId)
                val byDay = events.groupBy {
                    Instant.ofEpochMilli(it.startMillis).atZone(zoneId).toLocalDate()
                }
                (0L..6L).map { offset ->
                    val date = weekStart.plusDays(offset)
                    scheduleEngine.buildDay(date, zoneId, byDay[date].orEmpty(), preferences)
                }
            }.onSuccess { days ->
                _state.value = _state.value.copy(weekDays = days)
            }.onFailure { error ->
                _state.value = _state.value.copy(error = error.message ?: "读取本周日程失败")
            }
        }
    }

    fun selectWeek(weekStart: LocalDate) {
        _state.value = _state.value.copy(weekStart = weekStart)
        refreshWeek()
    }

    fun previousWeek() = selectWeek(_state.value.weekStart.minusWeeks(1))

    fun nextWeek() = selectWeek(_state.value.weekStart.plusWeeks(1))

    fun currentWeek() = selectWeek(
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    )

    /** 有清单的日程可点进二级页；没有清单的日程不可点。 */
    fun openEventDetail(event: CalendarEvent) {
        if (checklistOf(event).isEmpty()) return
        _state.value = _state.value.copy(detailEvent = event)
    }

    fun closeEventDetail() {
        _state.value = _state.value.copy(detailEvent = null)
    }

    fun checklistOf(event: CalendarEvent): List<ChecklistItem> =
        _state.value.checklists[ChecklistStore.keyOf(event)].orEmpty()

    fun toggleChecklistItem(event: CalendarEvent, index: Int) {
        checklistStore.toggleItem(ChecklistStore.keyOf(event), index)
        reloadChecklists()
    }

    /** 清单全部完成后给自己打分（1~5 星）。 */
    fun rateEvent(event: CalendarEvent, stars: Int) {
        runCatching { checklistStore.setRating(ChecklistStore.keyOf(event), stars) }
            .onSuccess { reloadChecklists() }
            .onFailure { _state.value = _state.value.copy(error = it.message) }
    }

    fun ratingOf(event: CalendarEvent): Int? =
        _state.value.ratings[ChecklistStore.keyOf(event)]

    /** 长按日程请求删除：全天/重复日程受保护，直接提示。 */
    fun requestDeleteEvent(event: CalendarEvent) {
        if (event.allDay) {
            _state.value = _state.value.copy(error = "全天日程请在系统日历中删除")
            return
        }
        if (event.isRecurring) {
            _state.value = _state.value.copy(error = "重复日程请在系统日历中删除（删除会移除整个系列）")
            return
        }
        _state.value = _state.value.copy(pendingDeleteEvent = event)
    }

    fun cancelDeleteEvent() {
        _state.value = _state.value.copy(pendingDeleteEvent = null)
    }

    fun confirmDeleteEvent() {
        val event = _state.value.pendingDeleteEvent ?: return
        _state.value = _state.value.copy(pendingDeleteEvent = null)
        viewModelScope.launch {
            runCatching {
                calendarRepository.deleteEventById(event.eventId)
                checklistStore.removeByEventId(event.eventId)
            }.onSuccess {
                // 如果删掉的日程正开着清单二级页，一并关闭
                val detail = _state.value.detailEvent
                _state.value = _state.value.copy(
                    detailEvent = if (detail?.eventId == event.eventId) null else detail,
                    notice = "已删除「${event.title}」"
                )
                reloadChecklists()
                refreshDay()
                refreshWeek()
            }.onFailure { failure ->
                _state.value = _state.value.copy(error = failure.message ?: "删除失败")
            }
        }
    }

    private fun reloadChecklists() {
        _state.value = _state.value.copy(
            checklists = checklistStore.load(),
            ratings = checklistStore.loadRatings()
        )
    }

    fun updateText(value: String) {
        _state.value = _state.value.copy(textInput = value)
    }

    /** 发送一条聊天消息，AI 带着最近对话历史（记忆）回复。 */
    fun sendChatMessage() {
        val current = _state.value
        val text = current.textInput.trim()
        if (text.isBlank() || current.parsing) return
        val userMessage = ChatMessage(
            id = nextMessageId(),
            role = ChatRole.USER,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        val baseMessages = current.messages + userMessage
        _state.value = current.copy(
            messages = baseMessages,
            textInput = "",
            parsing = true,
            error = null
        )
        chatStore.save(baseMessages)
        viewModelScope.launch {
            runCatching {
                val today = LocalDate.now()
                val events = calendarRepository.eventsForRange(today.minusDays(7), today.plusDays(30))
                glmClient.chat(
                    history = baseMessages,
                    config = current.glmConfig,
                    existingEvents = events,
                    scheduleContext = buildScheduleContext(current.daySchedule)
                )
            }.onSuccess { result ->
                val reply = buildString {
                    append(result.summary)
                    result.questions.forEach { append("\n• ").append(it) }
                }
                val assistantMessage = ChatMessage(
                    id = nextMessageId(),
                    role = ChatRole.ASSISTANT,
                    text = reply,
                    timestamp = System.currentTimeMillis(),
                    actions = result.events,
                    warnings = result.warnings
                )
                val updated = _state.value.messages + assistantMessage
                _state.value = _state.value.copy(messages = updated, parsing = false)
                chatStore.save(updated)
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    parsing = false,
                    error = error.message ?: "AI 回复失败"
                )
            }
        }
    }

    fun clearChat() {
        chatStore.clear()
        _state.value = _state.value.copy(messages = emptyList(), notice = "对话已清空")
    }

    /**
     * 执行某条回复里的日程操作：逐条执行，成功的移除，失败的保留并汇总原因，
     * 避免重试时重复写入。
     */
    fun confirmMessageActions(messageId: Long) {
        val message = _state.value.messages.firstOrNull { it.id == messageId } ?: return
        if (message.actions.isEmpty() || _state.value.parsing) return
        _state.value = _state.value.copy(parsing = true, error = null)
        viewModelScope.launch {
            val remaining = mutableListOf<ParsedEvent>()
            val failures = mutableListOf<String>()
            var succeeded = 0
            message.actions.forEach { event ->
                val failure = runCatching {
                    require(event.endMillis > event.startMillis) { "日程结束时间必须晚于开始时间" }
                    // CREATE 需要拿到新事件 ID 才能把清单挂到正确的 key 上
                    val checklistKey = when (event.action) {
                        ParsedActionType.CREATE -> {
                            val newId = calendarRepository.insertEvent(event)
                            ChecklistStore.keyOf(newId, event.startMillis)
                        }
                        ParsedActionType.UPDATE -> {
                            calendarRepository.updateEvent(event)
                            // 开始时间变化时，把旧清单迁移到新 key
                            val targetId = event.targetEventId
                            val oldStart = event.originalStartMillis
                            if (targetId != null && oldStart != null && oldStart != event.startMillis) {
                                val oldKey = ChecklistStore.keyOf(targetId, oldStart)
                                val oldItems = checklistStore.load()[oldKey]
                                checklistStore.remove(oldKey)
                                if (event.checklist.isEmpty() && !oldItems.isNullOrEmpty()) {
                                    checklistStore.setItems(
                                        ChecklistStore.keyOf(targetId, event.startMillis), oldItems
                                    )
                                }
                            }
                            targetId?.let { ChecklistStore.keyOf(it, event.startMillis) }
                        }
                        ParsedActionType.DELETE -> {
                            calendarRepository.deleteEvent(event)
                            event.targetEventId?.let { checklistStore.removeByEventId(it) }
                            null
                        }
                    }
                    if (checklistKey != null && event.checklist.isNotEmpty()) {
                        checklistStore.setItems(
                            checklistKey,
                            event.checklist.map { ChecklistItem(it) }
                        )
                    }
                }.exceptionOrNull()
                if (failure == null) {
                    succeeded++
                } else {
                    remaining += event
                    failures += "「${event.title}」：${failure.message ?: "执行失败"}"
                }
            }
            val updated = _state.value.messages.map {
                if (it.id == messageId) it.copy(actions = remaining, executed = remaining.isEmpty()) else it
            }
            _state.value = if (failures.isEmpty()) {
                _state.value.copy(
                    messages = updated,
                    parsing = false,
                    notice = "已处理 $succeeded 个日程"
                )
            } else {
                _state.value.copy(
                    messages = updated,
                    parsing = false,
                    error = buildString {
                        if (succeeded > 0) append("已处理 $succeeded 个日程；")
                        append("以下 ${failures.size} 条未执行：\n")
                        append(failures.joinToString("\n"))
                    }
                )
            }
            chatStore.save(updated)
            if (succeeded > 0) {
                reloadChecklists()
                refreshDay()
                refreshWeek()
            }
        }
    }

    fun updateMessageAction(messageId: Long, index: Int, updated: ParsedEvent) {
        updateMessage(messageId) { message ->
            if (index !in message.actions.indices) message
            else message.copy(actions = message.actions.toMutableList().apply { set(index, updated) })
        }
    }

    fun removeMessageAction(messageId: Long, index: Int) {
        updateMessage(messageId) { message ->
            if (index !in message.actions.indices) message
            else message.copy(actions = message.actions.toMutableList().apply { removeAt(index) })
        }
    }

    private fun updateMessage(messageId: Long, transform: (ChatMessage) -> ChatMessage) {
        val updated = _state.value.messages.map {
            if (it.id == messageId) transform(it) else it
        }
        _state.value = _state.value.copy(messages = updated)
        chatStore.save(updated)
    }

    private fun nextMessageId(): Long =
        System.currentTimeMillis() * 100 + (messageCounter++ % 100)

    fun saveGlmConfig(config: GlmConfig) {
        runCatching {
            configStore.saveGlmConfig(config)
            configStore.loadGlmConfig()
        }.onSuccess { saved ->
            _state.value = _state.value.copy(glmConfig = saved, notice = "AI 设置已保存")
        }.onFailure { error ->
            _state.value = _state.value.copy(error = error.message ?: "保存设置失败")
        }
    }

    fun testGlmConnection(config: GlmConfig) {
        _state.value = _state.value.copy(testingConnection = true, error = null, notice = null)
        viewModelScope.launch {
            runCatching { glmClient.testConnection(config) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        testingConnection = false,
                        notice = "连接成功：${it}"
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        testingConnection = false,
                        error = it.message ?: "连接失败"
                    )
                }
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    private fun buildScheduleContext(day: DaySchedule?): String {
        if (day == null) return ""
        val free = day.freeRanges
            .filter { it.durationMinutes >= 20 }
            .take(8)
            .joinToString("；") { AppDateFormat.timeRange(it.startMillis, it.endMillis, day.zoneId) }
            .ifBlank { "无" }
        return "查看日期=${day.date}；当天空闲=$free"
    }

    fun reportError(message: String?) {
        if (!message.isNullOrBlank()) {
            _state.value = _state.value.copy(error = message)
        }
    }

    /** 解密失败（如换机恢复备份）时返回默认配置和提示文案，而不是静默丢 Key。 */
    private fun loadConfigSafely(): Pair<GlmConfig, String?> =
        runCatching { configStore.loadGlmConfig() to null }
            .getOrElse { error -> GlmConfig() to (error.message ?: "读取 AI 设置失败") }
}

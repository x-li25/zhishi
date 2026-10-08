package com.zhishi.schedule.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.ai.GlmClient
import com.zhishi.schedule.data.CalendarRepository
import com.zhishi.schedule.data.CompletionStore
import com.zhishi.schedule.data.ConfigStore
import com.zhishi.schedule.domain.AiParseResult
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.DaySchedule
import com.zhishi.schedule.domain.GlmConfig
import com.zhishi.schedule.domain.ParsedEvent
import com.zhishi.schedule.domain.ScheduleEngine
import com.zhishi.schedule.domain.SchedulePreferences
import com.zhishi.schedule.domain.SuggestionType
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
    val parseResult: AiParseResult? = null,
    val parsing: Boolean = false,
    val glmConfig: GlmConfig = GlmConfig(),
    val preferences: SchedulePreferences = SchedulePreferences(),
    val testingConnection: Boolean = false,
    val completedKeys: Set<String> = emptySet()
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val calendarRepository = CalendarRepository(application)
    private val configStore = ConfigStore(application)
    private val completionStore = CompletionStore(application)
    private val glmClient = GlmClient()
    private val scheduleEngine = ScheduleEngine()

    private val initialConfig = loadConfigSafely()

    private val _state = MutableStateFlow(
        AppUiState(
            glmConfig = initialConfig.first,
            preferences = configStore.loadSchedulePreferences(),
            calendarPermissionGranted = calendarRepository.hasReadPermission(),
            completedKeys = completionStore.loadCompleted(),
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

    fun toggleCompleted(event: CalendarEvent) {
        completionStore.toggle(event)
        _state.value = _state.value.copy(completedKeys = completionStore.loadCompleted())
    }

    fun updateText(value: String) {
        _state.value = _state.value.copy(textInput = value)
    }

    fun clearCapture() {
        _state.value = _state.value.copy(
            textInput = "",
            parseResult = null,
            error = null
        )
    }

    fun parseWithAi() {
        val current = _state.value
        val text = current.textInput.trim()
        if (text.isBlank()) {
            _state.value = _state.value.copy(error = "请输入日程描述文字")
            return
        }
        _state.value = current.copy(parsing = true, error = null, parseResult = null)
        viewModelScope.launch {
            runCatching {
                val rangeStart = current.selectedDate.minusDays(7)
                val rangeEnd = current.selectedDate.plusDays(30)
                val events = calendarRepository.eventsForRange(rangeStart, rangeEnd)
                glmClient.interpret(
                    text = text,
                    config = current.glmConfig,
                    existingEvents = events,
                    scheduleContext = buildScheduleContext(current.daySchedule)
                )
            }.onSuccess { result ->
                _state.value = _state.value.copy(parsing = false, parseResult = result)
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    parsing = false,
                    error = error.message ?: "AI 识别失败"
                )
            }
        }
    }

    fun updateParsedEvent(index: Int, updated: ParsedEvent) {
        val result = _state.value.parseResult ?: return
        val events = result.events.toMutableList()
        if (index !in events.indices) return
        events[index] = updated
        _state.value = _state.value.copy(parseResult = result.copy(events = events))
    }

    fun removeParsedEvent(index: Int) {
        val result = _state.value.parseResult ?: return
        val events = result.events.toMutableList()
        if (index !in events.indices) return
        events.removeAt(index)
        _state.value = _state.value.copy(parseResult = result.copy(events = events))
    }

    /**
     * 逐条执行，成功的从结果列表移除，失败的保留并汇总原因。
     * 避免中途失败后重试导致已成功的日程被重复写入。
     */
    fun confirmParsedEvents() {
        val result = _state.value.parseResult ?: return
        if (result.events.isEmpty()) {
            _state.value = _state.value.copy(error = "没有可处理的日程")
            return
        }
        _state.value = _state.value.copy(parsing = true, error = null)
        viewModelScope.launch {
            val remaining = mutableListOf<ParsedEvent>()
            val failures = mutableListOf<String>()
            var succeeded = 0
            result.events.forEach { event ->
                val failure = runCatching {
                    require(event.endMillis > event.startMillis) { "日程结束时间必须晚于开始时间" }
                    calendarRepository.applyEvent(event)
                }.exceptionOrNull()
                if (failure == null) {
                    succeeded++
                } else {
                    remaining += event
                    failures += "「${event.title}」：${failure.message ?: "执行失败"}"
                }
            }
            if (failures.isEmpty()) {
                _state.value = _state.value.copy(
                    parsing = false,
                    parseResult = null,
                    textInput = "",
                    notice = "已处理 $succeeded 个日程"
                )
            } else {
                _state.value = _state.value.copy(
                    parsing = false,
                    parseResult = result.copy(events = remaining),
                    error = buildString {
                        if (succeeded > 0) append("已处理 $succeeded 个日程；")
                        append("以下 ${failures.size} 条未执行：\n")
                        append(failures.joinToString("\n"))
                    }
                )
            }
            if (succeeded > 0) {
                refreshDay()
                refreshWeek()
            }
        }
    }

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

    fun savePreferences(preferences: SchedulePreferences) {
        configStore.saveSchedulePreferences(preferences)
        _state.value = _state.value.copy(preferences = preferences, notice = "排程偏好已保存")
        refreshDay()
        refreshWeek()
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
        val suggestions = day.suggestions
            .joinToString("；") {
                "${it.title} ${AppDateFormat.timeRange(it.range.startMillis, it.range.endMillis, day.zoneId)}"
            }
            .ifBlank { "无" }
        return "查看日期=${day.date}；可自由安排=$free；本地建议=$suggestions"
    }

    fun reportError(message: String?) {
        if (!message.isNullOrBlank()) {
            _state.value = _state.value.copy(error = message)
        }
    }

    fun createSuggestedEvent(type: SuggestionType, title: String, startMillis: Long, endMillis: Long) {
        val event = ParsedEvent(
            title = title,
            startMillis = startMillis,
            endMillis = endMillis,
            confidence = 1.0
        )
        _state.value = _state.value.copy(parsing = true, error = null)
        viewModelScope.launch {
            runCatching { calendarRepository.insertEvent(event) }
                .onSuccess {
                    _state.value = _state.value.copy(parsing = false, notice = "已加入日历")
                    refreshDay()
                    refreshWeek()
                }
                .onFailure {
                    _state.value = _state.value.copy(parsing = false, error = it.message ?: "写入日历失败")
                }
        }
    }

    /** 解密失败（如换机恢复备份）时返回默认配置和提示文案，而不是静默丢 Key。 */
    private fun loadConfigSafely(): Pair<GlmConfig, String?> =
        runCatching { configStore.loadGlmConfig() to null }
            .getOrElse { error -> GlmConfig() to (error.message ?: "读取 AI 设置失败") }
}

package com.zhishi.schedule.ui.screens

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.ai.GlmClient
import com.zhishi.schedule.data.CalendarRepository
import com.zhishi.schedule.data.ChatStore
import com.zhishi.schedule.data.ChatImageStore
import com.zhishi.schedule.data.ChecklistStore
import com.zhishi.schedule.data.ConfigStore
import com.zhishi.schedule.data.WeatherStore
import com.zhishi.schedule.data.WeatherClient
import com.zhishi.schedule.data.HomeworkClient
import com.zhishi.schedule.data.HomeworkPriorityStore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.zhishi.schedule.domain.AcademicCalendar
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.ChatMessage
import com.zhishi.schedule.domain.ChatRole
import com.zhishi.schedule.domain.ChecklistItem
import com.zhishi.schedule.domain.DaySchedule
import com.zhishi.schedule.domain.GlmConfig
import com.zhishi.schedule.domain.ModelCatalogState
import com.zhishi.schedule.domain.ParsedActionType
import com.zhishi.schedule.domain.ParsedEvent
import com.zhishi.schedule.domain.ScheduleEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val dayError: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val calendarPermissionGranted: Boolean = false,
    val textInput: String = "",
    val selectedImageFileName: String? = null,
    val preparingImage: Boolean = false,
    val parsingLabel: String = "正在思考…",
    val messages: List<ChatMessage> = emptyList(),
    val parsing: Boolean = false,
    val canStopGeneration: Boolean = false,
    val glmConfig: GlmConfig = GlmConfig(),
    val testingConnection: Boolean = false,
    val connectionResult: String? = null,
    val modelCatalog: ModelCatalogState = ModelCatalogState(),
    val checklists: Map<String, List<ChecklistItem>> = emptyMap(),
    val detailEvent: CalendarEvent? = null,
    val weekDetailEvent: CalendarEvent? = null,
    val savingEventDetails: Boolean = false,
    val eventDetailsError: String? = null,
    val pendingDeleteEvent: CalendarEvent? = null,
    val knownEvents: List<CalendarEvent> = emptyList(),
    val notificationPermissionGranted: Boolean = true
)

class AppViewModel @JvmOverloads constructor(
    application: Application,
    private val glmClient: GlmClient = GlmClient(),
    private val dayReader: suspend (LocalDate) -> List<CalendarEvent> = { CalendarRepository(application).eventsForDate(it) }
) : AndroidViewModel(application) {
    private val calendarRepository = CalendarRepository(application)
    private val configStore = ConfigStore(application)
    private val checklistStore = ChecklistStore(application)
    private val chatStore = ChatStore(application)
    private val imageStore = ChatImageStore(application)
    private val weatherStore = WeatherStore(application)
    private val weatherClient = WeatherClient()
    private val scheduleEngine = ScheduleEngine()

    private var messageCounter = 0L
    private var modelsRequestId = 0L
    private var modelsJob: Job? = null
    private var dayJob: Job? = null
    private var dayRequest = 0L
    private data class GenerationDraft(
        val messageId: Long,
        val text: String,
        val imageFileName: String?,
        val previousKnownEvents: List<CalendarEvent>
    )
    @Volatile private var activeGenerationId: Long? = null
    private var generationJob: Job? = null
    private var generationDraft: GenerationDraft? = null

    private val initialConfig = loadConfigSafely()

    private val _state = MutableStateFlow(
        AppUiState(
            glmConfig = initialConfig.first,
            calendarPermissionGranted = calendarRepository.hasReadPermission(),
            checklists = checklistStore.load(),
            messages = chatStore.load(),
            error = initialConfig.second
        )
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        imageStore.retainOnly(_state.value.messages.mapNotNull { it.imageFileName }.toSet())
        refreshNotificationPermission()
        refreshDay()
        refreshWeek()
    }

    /** 提醒通知权限（API 33 以下默认开启）。 */
    fun refreshNotificationPermission() {
        val app = getApplication<Application>()
        val granted = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                app, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        _state.value = _state.value.copy(notificationPermissionGranted = granted)
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
        val token = ++dayRequest
        dayJob?.cancel()
        _state.value = current.copy(loading = true, dayError = null)
        dayJob = viewModelScope.launch {
            try {
                val events = dayReader(current.selectedDate)
                val day = scheduleEngine.buildDay(
                    date = current.selectedDate,
                    zoneId = ZoneId.systemDefault(),
                    events = events
                )
                ensureActive()
                if (token == dayRequest && _state.value.selectedDate == current.selectedDate) {
                    _state.value = _state.value.copy(daySchedule = day, loading = false, dayError = null)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == dayRequest && _state.value.selectedDate == current.selectedDate)
                    _state.value = _state.value.copy(loading = false, dayError = "暂时无法读取日程，请检查日历权限后重试。")
            }
        }
    }

    fun selectDate(date: LocalDate) {
        _state.value = _state.value.copy(selectedDate = date, daySchedule = _state.value.daySchedule?.takeIf { it.date == date })
        refreshDay()
    }

    fun refreshWeek() {
        val weekStart = _state.value.weekStart
        viewModelScope.launch {
            runCatching {
                val zoneId = ZoneId.systemDefault()
                val events = calendarRepository.eventsForRange(weekStart, weekStart.plusDays(6), zoneId)
                scheduleEngine.buildDays(weekStart, weekStart.plusDays(6), zoneId, events)
            }.onSuccess { days ->
                // 同 refreshDay：快速翻周时丢弃过期的结果
                if (_state.value.weekStart == weekStart) {
                    _state.value = _state.value.copy(weekDays = days)
                }
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

    /** 周视图的全部日程都能查看详情，不受是否有清单限制。 */
    fun openWeekEventDetail(event: CalendarEvent) {
        if (_state.value.savingEventDetails) return
        _state.value = _state.value.copy(weekDetailEvent = event, eventDetailsError = null)
    }

    fun closeWeekEventDetail() {
        if (_state.value.savingEventDetails) return
        _state.value = _state.value.copy(weekDetailEvent = null, eventDetailsError = null)
    }

    fun saveWeekEventDetails(title: String, location: String) {
        val event = _state.value.weekDetailEvent ?: return
        if (_state.value.savingEventDetails) return
        _state.value = _state.value.copy(savingEventDetails = true, eventDetailsError = null)
        viewModelScope.launch {
            runCatching { calendarRepository.updateEventDetails(event.eventId, title, location) }
                .onSuccess {
                    fun CalendarEvent.updatedDetails() = if (eventId == event.eventId) {
                        copy(title = title.trim(), location = location.trim().takeIf { it.isNotEmpty() })
                    } else this
                    _state.value = _state.value.copy(
                        weekDetailEvent = null,
                        savingEventDetails = false,
                        detailEvent = _state.value.detailEvent?.updatedDetails(),
                        knownEvents = _state.value.knownEvents.map { it.updatedDetails() },
                        notice = "日程已保存"
                    )
                    refreshDay()
                    refreshWeek()
                }.onFailure { failure ->
                    _state.value = _state.value.copy(
                        savingEventDetails = false,
                        eventDetailsError = failure.message ?: "保存失败，请重试"
                    )
                }
        }
    }

    fun checklistOf(event: CalendarEvent): List<ChecklistItem> =
        _state.value.checklists[ChecklistStore.keyOf(event)].orEmpty()

    fun toggleChecklistItem(event: CalendarEvent, index: Int) {
        checklistStore.toggleItem(ChecklistStore.keyOf(event), index)
        reloadChecklists()
    }

    fun addChecklistItem(event: CalendarEvent, text: String) {
        checklistStore.addItem(ChecklistStore.keyOf(event), text)
        reloadChecklists()
    }

    fun removeChecklistItem(event: CalendarEvent, index: Int) {
        checklistStore.removeItem(ChecklistStore.keyOf(event), index)
        reloadChecklists()
    }

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
            checklists = checklistStore.load()
        )
    }

    fun updateText(value: String) {
        _state.value = _state.value.copy(textInput = value)
    }

    fun selectImage(uri: Uri) {
        if (_state.value.parsing || _state.value.preparingImage) return
        _state.value = _state.value.copy(preparingImage = true, error = null)
        viewModelScope.launch {
            runCatching { imageStore.importImage(uri) }
                .onSuccess { name ->
                    _state.value.selectedImageFileName?.let(imageStore::delete)
                    _state.value = _state.value.copy(selectedImageFileName = name, preparingImage = false)
                }
                .onFailure { failure ->
                    _state.value = _state.value.copy(preparingImage = false, error = failure.message ?: "读取图片失败，请重新选择")
                }
        }
    }

    fun removeSelectedImage() {
        if (_state.value.parsing || _state.value.preparingImage) return
        _state.value.selectedImageFileName?.let(imageStore::delete)
        _state.value = _state.value.copy(selectedImageFileName = null)
    }

    private fun persistMessages(messages: List<ChatMessage>) {
        chatStore.save(messages, retainedImageFileName = _state.value.selectedImageFileName)
    }

    /** 发送一条聊天消息，AI 带着最近对话历史（记忆）回复。 */
    fun sendChatMessage() {
        val current = _state.value
        val image = current.selectedImageFileName
        val text = current.textInput.trim().ifBlank { if (image != null) "请识别这张图片中的日程，帮我安排。" else "" }
        if (text.isBlank() || current.parsing || current.preparingImage) return
        val userMessage = ChatMessage(
            id = nextMessageId(),
            role = ChatRole.USER,
            text = text,
            timestamp = System.currentTimeMillis(),
            imageFileName = image
        )
        val baseMessages = current.messages + userMessage
        val draft = GenerationDraft(userMessage.id, current.textInput, image, current.knownEvents)
        generationDraft = draft
        activeGenerationId = userMessage.id
        _state.value = current.copy(
            messages = baseMessages,
            textInput = "",
            selectedImageFileName = null,
            parsing = true,
            canStopGeneration = true,
            knownEvents = emptyList(),
            parsingLabel = if (image == null) "正在思考…" else "正在本机提取图片文字…",
            error = null,
            notice = null
        )
        persistMessages(baseMessages)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val imageText = image?.let { name ->
                    try {
                        withTimeoutOrNull(8_000) { imageStore.recognizeText(name) }
                            ?.takeIf { it.length <= 30_000 }.orEmpty()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 识别失败或无文字时自动回退一次原图理解，草稿仍可失败重试。
                        ""
                    }
                }
                val now = Instant.now()
                currentCoroutineContext().ensureActive()
                val zoneId = ZoneId.systemDefault()
                val canRead = calendarRepository.hasReadPermission()
                val queriedEvents = linkedMapOf<String, CalendarEvent>()
                val weatherLocation = weatherStore.location()
                val result = glmClient.chat(
                    history = baseMessages,
                    config = current.glmConfig,
                    now = now,
                    zoneId = zoneId,
                    weatherLocation = weatherLocation,
                    readWeather = weatherLocation?.let { location ->
                        { start, end -> weatherClient.forecast(location, start, end, zoneId) }
                    },
                    scheduleContext = buildScheduleContext(current),
                    memory = chatStore.memory(),
                    readHomework = { query ->
                        val school = withContext(Dispatchers.Main.immediate) { HomeworkClient.forAndroid(getApplication()) }
                        HomeworkPriorityStore(getApplication()).decorate(school.read(query))
                    },
                    imageText = imageText,
                    loadImageDataUrl = image?.let { name -> suspend { imageStore.dataUrl(name) } },
                    onProgress = { label ->
                        updateGenerationState(userMessage.id) { it.copy(parsingLabel = label) }
                    },
                    readCalendar = if (!canRead) null else { start, end ->
                        currentCoroutineContext().ensureActive()
                        check(calendarRepository.hasReadPermission()) { "请先授予日历读取权限" }
                        calendarRepository.eventsForRange(start, end, zoneId).also { events ->
                            currentCoroutineContext().ensureActive()
                            events.forEach { queriedEvents[ChecklistStore.keyOf(it)] = it }
                            updateGenerationState(userMessage.id) { it.copy(knownEvents = queriedEvents.values.toList()) }
                        }
                    }
                )
                currentCoroutineContext().ensureActive()
                if (!finishGeneration(userMessage.id)) return@launch
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
                    warnings = result.warnings,
                    tokenUsage = result.totalTokens
                )
                val updated = _state.value.messages.map { message ->
                    if (message.id == userMessage.id && image != null) message.copy(
                        imageAnalysis = result.imageAnalysis,
                        imageText = result.imageText
                    )
                    else message
                } + assistantMessage
                _state.value = _state.value.copy(messages = updated, parsing = false, canStopGeneration = false)
                persistMessages(updated)
            } catch (cancelled: CancellationException) {
                if (finishGeneration(userMessage.id)) restoreGenerationDraft(draft, notice = "已停止生成")
                throw cancelled
            } catch (failure: Exception) {
                if (finishGeneration(userMessage.id)) {
                    restoreGenerationDraft(draft, error = failure.message ?: "AI 回复失败")
                }
            }
        }
        generationJob = job
        job.start()
    }

    /** 先使本次请求失效再取消，迟到的进度/日历/网络结果不能覆盖新对话。 */
    fun stopGeneration() {
        val draft = generationDraft ?: return
        val job = generationJob
        if (!finishGeneration(draft.messageId)) return
        job?.cancel()
        restoreGenerationDraft(draft, notice = "已停止生成")
    }

    private fun finishGeneration(messageId: Long): Boolean {
        if (activeGenerationId != messageId) return false
        activeGenerationId = null
        generationJob = null
        generationDraft = null
        return true
    }

    private fun updateGenerationState(messageId: Long, transform: (AppUiState) -> AppUiState) {
        _state.update { current ->
            if (activeGenerationId == messageId && current.canStopGeneration) transform(current) else current
        }
    }

    private fun restoreGenerationDraft(draft: GenerationDraft, notice: String? = null, error: String? = null) {
        val restored = _state.value.messages.filterNot { it.id == draft.messageId }
        _state.value = _state.value.copy(
            messages = restored,
            textInput = draft.text,
            selectedImageFileName = draft.imageFileName,
            knownEvents = draft.previousKnownEvents,
            parsing = false,
            canStopGeneration = false,
            parsingLabel = "正在思考…",
            notice = notice,
            error = error
        )
        persistMessages(restored)
    }

    fun clearChat() {
        if (_state.value.parsing || _state.value.preparingImage) return
        chatStore.clear()
        _state.value = _state.value.copy(messages = emptyList(), textInput = "", selectedImageFileName = null, notice = "对话已清空")
    }

    /**
     * 执行某条回复里的日程操作：逐条执行，成功的移除，失败的保留并汇总原因，
     * 避免重试时重复写入。
     */
    fun confirmMessageActions(messageId: Long) {
        val message = _state.value.messages.firstOrNull { it.id == messageId } ?: return
        if (message.actions.isEmpty() || _state.value.parsing) return
        _state.value = _state.value.copy(parsing = true, canStopGeneration = false, parsingLabel = "正在写入日程…", error = null)
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
                                checklistStore.move(oldKey, ChecklistStore.keyOf(targetId, event.startMillis))
                            }
                            targetId?.let { ChecklistStore.keyOf(it, event.startMillis) }
                        }
                        ParsedActionType.DELETE -> {
                            calendarRepository.deleteEventById(
                                event.targetEventId ?: error("缺少要删除的日程 ID")
                            )
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
            persistMessages(updated)
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
        persistMessages(updated)
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
        _state.value = _state.value.copy(testingConnection = true, connectionResult = null, error = null, notice = null)
        viewModelScope.launch {
            runCatching { glmClient.testConnection(config) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        testingConnection = false,
                        notice = "连接成功：${it}", connectionResult = "连接成功：${it}"
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        testingConnection = false,
                        error = it.message ?: "连接失败", connectionResult = it.message ?: "连接失败"
                    )
                }
        }
    }

    fun invalidateModelCatalog() {
        modelsRequestId++
        modelsJob?.cancel()
        modelsJob = null
        _state.value = _state.value.copy(modelCatalog = ModelCatalogState(), connectionResult = null)
    }

    fun fetchModels(config: GlmConfig) {
        invalidateModelCatalog()
        val requestId = modelsRequestId
        _state.value = _state.value.copy(modelCatalog = ModelCatalogState(loading = true))
        modelsJob = viewModelScope.launch {
            try {
                val models = glmClient.listModels(config)
                if (requestId == modelsRequestId) {
                    _state.value = _state.value.copy(modelCatalog = ModelCatalogState(models = models, loaded = true))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (requestId == modelsRequestId) {
                    _state.value = _state.value.copy(modelCatalog = ModelCatalogState(
                        error = failure.message ?: "获取模型列表失败，请重试或手动填写模型名称"
                    ))
                }
            }
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(error = null, notice = null)
    }

    private fun buildScheduleContext(current: AppUiState): String {
        // 页面日期仅用于理解“当前这天/这一周”，实际日程按需读取。
        return "页面查看日期=${current.selectedDate}（${AcademicCalendar.label(current.selectedDate)}）；" +
            "页面查看周=${current.weekStart}至${current.weekStart.plusDays(6)}（${AcademicCalendar.label(current.weekStart)}）。"
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

package com.zhishi.schedule.ui.screens

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.data.*
import com.zhishi.schedule.domain.AcademicCalendar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.Instant

data class CampusState(
    val graduate: Boolean = false,
    val start: String = AcademicCalendar.firstMonday.toString(),
    val end: String = AcademicCalendar.lastSunday.toString(),
    val loginVisible: Boolean = false,
    val busy: Boolean = false,
    val importing: Boolean = false,
    val clearing: Boolean = false,
    val progress: String? = null,
    val message: String? = null,
    val preview: List<CampusEvent>? = null,
    val previewVisible: Boolean = false,
    val previewUpdatedAt: Instant? = null,
    val previewNeedsRefresh: Boolean = false,
    val selected: Set<String> = emptySet(),
    val imported: Set<String> = emptySet(),
    val importRevision: Int = 0
)

class CampusViewModel @JvmOverloads constructor(app: Application,
    private val clientFactory: () -> CampusClient = { CampusClient(CampusHttpTransport(CookieManager.getInstance(), WebSettings.getDefaultUserAgent(app))) }
) : AndroidViewModel(app) {
    private val preferences = app.getSharedPreferences("campus", android.content.Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(CampusState(graduate = preferences.getBoolean("graduate", false)))
    val state = mutable.asStateFlow()
    private val calendar = CalendarRepository(app)
    private var work: Job? = null
    private var generation = 0L
    private var epoch = CampusSession.changes.value
    private var startupRefreshed = false

    fun refreshOnAppStart() {
        if (startupRefreshed) return
        startupRefreshed = true
        refresh(openPreview = false, finishLogin = false)
    }

    init {
        viewModelScope.launch {
            CampusSession.changes.collect { current ->
                if (current != epoch) {
                    epoch = current
                    if (!mutable.value.importing) { generation++; work?.cancel() }
                    mutable.update { it.copy(preview = null, previewUpdatedAt = null, previewNeedsRefresh = false, selected = emptySet(),
                        busy = it.importing, progress = if (it.importing) it.progress else null) }
                }
            }
        }
    }

    fun configure(graduate: Boolean = mutable.value.graduate, start: String = mutable.value.start, end: String = mutable.value.end) {
        if (mutable.value.busy || mutable.value.clearing) return
        preferences.edit().putBoolean("graduate", graduate).apply()
        mutable.update { it.copy(graduate = graduate, start = start, end = end, preview = null, previewUpdatedAt = null,
            previewNeedsRefresh = false, selected = emptySet(), message = null) }
    }

    fun login() {
        if (mutable.value.busy || mutable.value.clearing) return
        CampusSession.invalidate()
        epoch = CampusSession.changes.value
        mutable.update { it.copy(loginVisible = true, previewVisible = false, preview = null, previewUpdatedAt = null,
            previewNeedsRefresh = false, selected = emptySet(), message = null) }
    }

    fun closeLogin() { mutable.update { it.copy(loginVisible = false) } }
    fun openPreview() { mutable.update { it.copy(previewVisible = true) } }
    fun closePreview() { if (!mutable.value.importing) mutable.update { it.copy(previewVisible = false) } }

    fun clearLogin() {
        if (mutable.value.busy || mutable.value.clearing) return
        CampusSession.invalidate()
        epoch = CampusSession.changes.value
        generation++
        mutable.update { it.copy(clearing = true, loginVisible = false, preview = null, previewUpdatedAt = null,
            previewNeedsRefresh = false, selected = emptySet()) }
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            CampusCredentialStore(getApplication()).clear()
            CampusSession.signOut()
            mutable.update { it.copy(clearing = false, message = "已清除本机清华登录状态，已导入日程保留") }
        }
    }

    fun cancelRead() {
        if (mutable.value.importing) return
        generation++; work?.cancel(); work = null
        mutable.update { it.copy(busy = false, progress = null, message = "已停止读取") }
    }

    fun read() = refresh(openPreview = true, finishLogin = true)

    fun refreshAfterLogin(showPreview: Boolean, finishLogin: Boolean = true) = refresh(openPreview = showPreview, finishLogin = finishLogin)

    private fun refresh(openPreview: Boolean, finishLogin: Boolean) {
        if (mutable.value.busy || mutable.value.clearing) return
        mutable.update { it.copy(loginVisible = if (finishLogin) false else it.loginVisible, previewVisible = openPreview || it.previewVisible) }
        val s = mutable.value
        val range = runCatching {
            val from = LocalDate.parse(s.start.trim()); val to = LocalDate.parse(s.end.trim())
            CampusScheduleParser.chunks(from, to)
            from to to
        }.getOrElse {
            mutable.update { it.copy(message = "请填写有效日期（如 2026-09-14），范围不超过 183 天") }; return
        }
        val token = ++generation
        mutable.update { it.copy(busy = true, previewNeedsRefresh = true, message = null, progress = "正在刷新课表与考试…") }
        work = viewModelScope.launch {
            try {
                val client = clientFactory()
                val rows = client.read(s.graduate, range.first, range.second) { current, total ->
                    if (generation == token) mutable.update { it.copy(progress = "正在读取第 $current / $total 段日期…") }
                }
                val imported = if (calendar.hasReadPermission()) calendar.campusImportedMarkers() else emptySet()
                ensureActive()
                if (generation == token) mutable.update { it.copy(busy = false, progress = null, preview = rows, imported = imported,
                    previewUpdatedAt = Instant.now(), previewNeedsRefresh = false,
                    selected = rows.filter { row -> row.marker !in imported }.map { row -> row.key }.toSet(),
                    message = if (rows.isEmpty()) "学校在这个范围内未返回课程或考试；未公布的安排不会显示，请稍后再读取。" else "请核对日期、时间和地点，再确认导入。") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == token) mutable.update { it.copy(busy = false, progress = null,
                    message = "刷新失败，原预览如有保留仅供参考，请下拉重试；若登录失效，请重新登录学校。") }
            }
        }
    }

    fun select(key: String) {
        if (mutable.value.busy || mutable.value.previewNeedsRefresh) return
        mutable.update { s -> if (s.preview.orEmpty().none { it.key == key && it.marker !in s.imported }) s else
            s.copy(selected = if (key in s.selected) s.selected - key else s.selected + key) }
    }

    fun selectAll(selected: Boolean) {
        if (mutable.value.busy || mutable.value.previewNeedsRefresh) return
        mutable.update { s -> s.copy(selected = if (selected) s.preview.orEmpty().filter { it.marker !in s.imported }.map { it.key }.toSet() else emptySet()) }
    }

    fun importSelected() {
        val s = mutable.value
        if (s.busy || s.previewNeedsRefresh || s.selected.isEmpty()) return
        val rows = s.preview.orEmpty().filter { it.key in s.selected }
        mutable.update { it.copy(busy = true, importing = true, progress = "正在导入系统日历…", message = null) }
        work = viewModelScope.launch {
            try {
                val (added, skipped) = calendar.importCampusEvents(rows)
                val imported = calendar.campusImportedMarkers()
                mutable.update { it.copy(busy = false, importing = false, progress = null, imported = imported,
                    selected = emptySet(), importRevision = it.importRevision + 1,
                    message = "已导入 $added 条日程，跳过 $skipped 条已导入项目。可在今天、周视图和 AI 对话中使用。") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.update { it.copy(busy = false, importing = false, progress = null, importRevision = it.importRevision + 1,
                    message = "导入未全部完成，请检查日历权限和可写账户后重试。重试会跳过已成功导入的项目。") }
            }
        }
    }
}

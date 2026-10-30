package com.zhishi.schedule.ui.screens

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ClassroomState(
    val buildings: List<ClassroomBuilding> = emptyList(), val building: ClassroomBuilding? = null,
    val date: LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai")), val snapshot: ClassroomWeek? = null,
    val loading: Boolean = false, val error: String? = null, val updatedAt: Instant? = null, val requestedWeek: Int? = null
)

class ClassroomViewModel @JvmOverloads constructor(app: Application,
    private val clientFactory: () -> ClassroomReader = { ClassroomClient(CampusHttpTransport(CookieManager.getInstance(), WebSettings.getDefaultUserAgent(app))) }
) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(ClassroomState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0L
    private var epoch = CampusSession.changes.value
    private var listUpdated: Instant? = null
    private data class Cached(val data: ClassroomWeek, val time: Instant)
    private val cache = linkedMapOf<Pair<String, Int>, Cached>()
    init {
        viewModelScope.launch { CampusSession.changes.collect { current ->
            if (epoch != current) {
                epoch = current; generation++; work?.cancel(); cache.clear(); listUpdated = null
                mutable.value = ClassroomState()
            }
        } }
    }
    fun open(force: Boolean = false) {
        if (!force && mutable.value.buildings.isNotEmpty() && fresh(listUpdated)) return
        val token = ++generation; work?.cancel(); val session = CampusSession.changes.value
        mutable.update { it.copy(loading = true, error = null) }
        work = viewModelScope.launch {
            try {
                val buildings = clientFactory().buildings()
                ensureActive(); CampusSession.check(session)
                if (generation == token) { listUpdated = Instant.now(); mutable.update { it.copy(buildings = buildings, loading = false) } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == token && session == CampusSession.changes.value) mutable.update { it.copy(loading = false, error = e.message ?: "读取楼宇失败，请刷新") } }
        }
    }
    fun select(building: ClassroomBuilding) {
        mutable.update { it.copy(building = building, date = LocalDate.now(ZoneId.of("Asia/Shanghai")), snapshot = null, updatedAt = null, error = null, requestedWeek = null) }
        load(building.week, mutable.value.date, initial = true)
    }
    fun back() {
        generation++; work?.cancel()
        mutable.update { it.copy(building = null, snapshot = null, updatedAt = null, error = null, loading = false, requestedWeek = null) }
    }
    fun stop() {
        generation++; work?.cancel(); mutable.update { it.copy(loading = false) }
    }
    fun refresh() {
        val current = mutable.value
        if (current.loading) return
        if (current.building == null) open(force = true)
        else load(current.requestedWeek ?: current.snapshot?.number ?: current.building.week, current.date, force = true, initial = current.requestedWeek == null)
    }
    fun canSelect(date: LocalDate): Boolean {
        val report = mutable.value.snapshot ?: return false
        val number = report.number + Math.floorDiv(ChronoUnit.DAYS.between(report.dates.first(), date), 7).toInt()
        return number in report.validWeeks
    }
    fun selectDate(date: LocalDate) {
        val current = mutable.value; val report = current.snapshot ?: return
        if (!canSelect(date)) { mutable.update { it.copy(error = "学校尚未开放该日期的教室数据") }; return }
        if (date in report.dates) { mutable.update { it.copy(date = date) }; return }
        val number = report.number + Math.floorDiv(ChronoUnit.DAYS.between(report.dates.first(), date), 7).toInt()
        mutable.update { it.copy(date = date, snapshot = null, updatedAt = null, error = null) }
        load(number, date)
    }
    private fun load(number: Int, date: LocalDate, force: Boolean = false, initial: Boolean = false) {
        val building = mutable.value.building ?: return
        val key = building.queryName to number
        val token = ++generation; work?.cancel(); val session = CampusSession.changes.value
        val cached = cache[key]?.takeIf { !force && fresh(it.time) && (initial || date in it.data.dates) }
        if (cached != null) {
            mutable.update { it.copy(snapshot = cached.data, date = if (date in cached.data.dates) date else cached.data.dates[date.dayOfWeek.value - 1],
                updatedAt = cached.time, loading = false, error = null, requestedWeek = number) }; return
        }
        mutable.update { it.copy(loading = true, error = null, requestedWeek = number) }
        work = viewModelScope.launch {
            try {
                val report = clientFactory().week(building, number, date)
                ensureActive(); CampusSession.check(session)
                require(report.number == number && report.dates.size == 7 && (initial || date in report.dates)) { "学校返回日期与查询不一致，请刷新" }
                if (generation == token) {
                    val time = Instant.now(); cache[key] = Cached(report, time)
                    while (cache.size > 4) cache.remove(cache.keys.first())
                    mutable.update { it.copy(snapshot = report, date = if (date in report.dates) date else report.dates[date.dayOfWeek.value - 1],
                        loading = false, updatedAt = time, error = null) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == token && session == CampusSession.changes.value) mutable.update { it.copy(loading = false, error = e.message ?: "读取教室状态失败，请刷新") } }
        }
    }
    private fun fresh(time: Instant?) = time != null && time.plusSeconds(300).isAfter(Instant.now())
}

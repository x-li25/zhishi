package com.zhishi.schedule.ui.screens

import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClassroomViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ClassroomViewModel>()
    private val building = ClassroomBuilding("新水利馆", "新水利馆", 7)
    private val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    private val monday = today.minusDays(today.dayOfWeek.value.toLong() - 1)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private fun report(number: Int, name: String = "300") = ClassroomWeek(number, (1..18).toList(),
        (0L..6).map { monday.plusWeeks(number.toLong() - 7).plusDays(it) },
        listOf(ClassroomRoom(name, 50, List(42) { ClassroomSlot(ClassroomOccupancy.TEACHING) })))
    private fun model(read: suspend (ClassroomBuilding, Int, LocalDate) -> ClassroomWeek): ClassroomViewModel {
        val reader = object : ClassroomReader {
            override suspend fun buildings() = listOf(building)
            override suspend fun week(building: ClassroomBuilding, number: Int, reference: LocalDate) = read(building, number, reference)
        }
        return ClassroomViewModel(RuntimeEnvironment.getApplication()) { reader }.also { models += it }
    }
    @Test fun `same week day changes reuse complete table and crossing monday reads correct week`() {
        val weeks = mutableListOf<Int>(); val m = model { _, n, _ -> weeks += n; report(n) }
        m.open(); dispatcher.scheduler.runCurrent(); m.select(building); dispatcher.scheduler.runCurrent()
        m.selectDate(monday.plusDays(6)); assertEquals(listOf(7), weeks)
        m.selectDate(monday.plusWeeks(1)); dispatcher.scheduler.runCurrent()
        assertEquals(listOf(7, 8), weeks); assertEquals(8, m.state.value.snapshot?.number)
        m.selectDate(monday); dispatcher.scheduler.runCurrent()
        assertEquals(listOf(7, 8), weeks); assertEquals(7, m.state.value.requestedWeek)
        m.refresh(); dispatcher.scheduler.runCurrent()
        assertEquals(listOf(7, 8, 7), weeks)
    }
    @Test fun `out of range calendar dates make no school request`() {
        var calls = 0; val m = model { _, n, _ -> calls++; report(n) }
        m.select(building); dispatcher.scheduler.runCurrent()
        m.selectDate(monday.plusWeeks(30)); dispatcher.scheduler.runCurrent()
        assertEquals(1, calls); assertNotNull(m.state.value.error); assertNotNull(m.state.value.snapshot)
    }
    @Test fun `failed refresh keeps dated old data and another pull retries`() {
        var fail = false; val m = model { _, n, _ -> if (fail) error("offline") else report(n) }
        m.select(building); dispatcher.scheduler.runCurrent(); val original = m.state.value.snapshot
        fail = true; m.refresh(); dispatcher.scheduler.runCurrent()
        assertEquals(original, m.state.value.snapshot); assertEquals("offline", m.state.value.error); assertFalse(m.state.value.loading)
        fail = false; m.refresh(); dispatcher.scheduler.runCurrent(); assertNull(m.state.value.error)
    }
    @Test fun `retry after failed week change uses requested week rather than original building week`() {
        var fail = true; val weeks = mutableListOf<Int>()
        val m = model { _, n, _ -> weeks += n; if (n == 8 && fail) error("offline") else report(n) }
        m.select(building); dispatcher.scheduler.runCurrent()
        m.selectDate(monday.plusWeeks(1)); dispatcher.scheduler.runCurrent(); assertNull(m.state.value.snapshot)
        fail = false; m.refresh(); dispatcher.scheduler.runCurrent()
        assertEquals(listOf(7, 8, 8), weeks); assertEquals(8, m.state.value.snapshot?.number)
    }
    @Test fun `late response from old building never replaces current selection`() {
        val release = CompletableDeferred<Unit>()
        val m = model { b, n, _ -> if (b.queryName == building.queryName) withContext(NonCancellable) { release.await(); report(n, "old") } else report(n, "new") }
        m.select(building); dispatcher.scheduler.runCurrent()
        m.select(building.copy(name = "六教", queryName = "六教")); dispatcher.scheduler.runCurrent()
        release.complete(Unit); dispatcher.scheduler.runCurrent()
        assertEquals("六教", m.state.value.building?.name); assertEquals("new", m.state.value.snapshot?.rooms?.single()?.name)
    }
    @Test fun `account change clears tables and cache before another school read`() {
        var calls = 0; val m = model { _, n, _ -> calls++; report(n) }
        m.select(building); dispatcher.scheduler.runCurrent()
        CampusSession.invalidate(); dispatcher.scheduler.runCurrent()
        assertNull(m.state.value.snapshot); assertNull(m.state.value.building)
        m.select(building); dispatcher.scheduler.runCurrent(); assertEquals(2, calls)
    }
    @Test fun `a mismatched school date is an error and cannot populate another week`() {
        val m = model { _, n, _ -> report(if (n == 8) 7 else n) }
        m.select(building); dispatcher.scheduler.runCurrent()
        m.selectDate(monday.plusWeeks(1)); dispatcher.scheduler.runCurrent()
        assertNull(m.state.value.snapshot); assertNotNull(m.state.value.error); assertFalse(m.state.value.loading)
    }
}

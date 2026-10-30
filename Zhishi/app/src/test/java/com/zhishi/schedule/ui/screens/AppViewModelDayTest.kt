package com.zhishi.schedule.ui.screens

import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.domain.CalendarEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppViewModelDayTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<AppViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private fun model(reader: suspend (LocalDate) -> List<CalendarEvent>) = AppViewModel(
        RuntimeEnvironment.getApplication(), dayReader = reader).also { models += it }

    @Test fun `failed day read exits loading and retry recovers the empty day`() {
        var failing = true
        val m = model { if (failing) error("provider unavailable") else emptyList() }
        dispatcher.scheduler.runCurrent()
        assertFalse(m.state.value.loading)
        assertNotNull(m.state.value.dayError)
        assertNull(m.state.value.daySchedule)
        failing = false
        m.refreshDay()
        assertTrue(m.state.value.loading)
        assertNull(m.state.value.dayError)
        dispatcher.scheduler.runCurrent()
        assertFalse(m.state.value.loading)
        assertNull(m.state.value.dayError)
        assertEquals(emptyList<CalendarEvent>(), m.state.value.daySchedule?.events)
    }

    @Test fun `switching date clears the previous day while the next request is pending`() {
        val nextResponse = CompletableDeferred<List<CalendarEvent>>()
        val today = LocalDate.now()
        val m = model { date -> if (date == today) emptyList() else nextResponse.await() }
        dispatcher.scheduler.runCurrent()
        assertEquals(today, m.state.value.daySchedule?.date)
        m.selectDate(today.plusDays(1))
        assertNull(m.state.value.daySchedule)
        assertTrue(m.state.value.loading)
        dispatcher.scheduler.runCurrent()
        nextResponse.complete(emptyList()); dispatcher.scheduler.runCurrent()
        assertEquals(today.plusDays(1), m.state.value.daySchedule?.date)
    }

    @Test fun `late cancelled failure cannot replace the newly selected day with an error`() {
        val oldResponse = CompletableDeferred<Unit>()
        val oldDate = LocalDate.now()
        val m = model { date ->
            if (date == oldDate) {
                withContext(NonCancellable) { oldResponse.await(); error("late failure") }
            }
            emptyList()
        }
        dispatcher.scheduler.runCurrent()
        m.selectDate(oldDate.plusDays(1)); dispatcher.scheduler.runCurrent()
        oldResponse.complete(Unit); dispatcher.scheduler.runCurrent()
        assertEquals(oldDate.plusDays(1), m.state.value.daySchedule?.date)
        assertFalse(m.state.value.loading)
        assertNull(m.state.value.dayError)
    }

    @Test fun `returning to same date still rejects an earlier response for that date`() {
        val oldResponse = CompletableDeferred<Unit>()
        val date = LocalDate.now()
        var first = true
        val m = model {
            if (first) { first = false; withContext(NonCancellable) { oldResponse.await(); error("stale") } }
            emptyList()
        }
        dispatcher.scheduler.runCurrent()
        m.selectDate(date.plusDays(1)); dispatcher.scheduler.runCurrent()
        m.selectDate(date); dispatcher.scheduler.runCurrent()
        oldResponse.complete(Unit); dispatcher.scheduler.runCurrent()
        assertEquals(date, m.state.value.daySchedule?.date)
        assertNull(m.state.value.dayError)
        assertFalse(m.state.value.loading)
    }
}

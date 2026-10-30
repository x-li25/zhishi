package com.zhishi.schedule.ui.screens

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.data.CampusClient
import com.zhishi.schedule.data.CampusTransport
import com.zhishi.schedule.data.CampusUrls
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CampusViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<CampusViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    private fun model(transport: CampusTransport): CampusViewModel = CampusViewModel(
        RuntimeEnvironment.getApplication()) { CampusClient(transport) }.also { models += it }

    private fun successful(url: String): String = when {
        url == CampusUrls.COOKIE -> "XSRF-TOKEN=fixture;"
        url.startsWith(CampusUrls.ROAM) -> """{"object":{"roamingurl":"http://zhjw.cic.tsinghua.edu.cn/jxmh.do?ticket=fixture"}}"""
        url.contains("jxmh_out.do") -> "m([])"
        else -> "<html>教学日历</html>"
    }

    @Test fun `app entry refreshes timetable once in background without writing calendar`() {
        var calls = 0
        val m = model { calls++; successful(it) }
        m.refreshOnAppStart(); m.refreshOnAppStart(); dispatcher.scheduler.runCurrent()
        val first = calls
        assertTrue(first > 0)
        assertNotNull(m.state.value.preview)
        assertFalse(m.state.value.previewVisible); assertFalse(m.state.value.loginVisible)
        assertEquals(0, m.state.value.importRevision)
        m.refreshOnAppStart(); dispatcher.scheduler.runCurrent()
        assertEquals(first, calls)
    }

    @Test fun `login completion refreshes an empty timetable without importing or opening a background preview`() {
        val m = model { successful(it) }
        m.refreshAfterLogin(showPreview = false); dispatcher.scheduler.runCurrent()
        assertEquals(emptyList<Any>(), m.state.value.preview)
        assertNotNull(m.state.value.previewUpdatedAt)
        assertFalse(m.state.value.previewVisible)
        assertFalse(m.state.value.previewNeedsRefresh)
        assertEquals(0, m.state.value.importRevision)
        m.openPreview(); assertTrue(m.state.value.previewVisible)
    }

    @Test fun `verified timetable refresh does not close unfinished homework authentication`() {
        val m = model { successful(it) }
        m.login()
        m.refreshAfterLogin(showPreview = false, finishLogin = false)
        dispatcher.scheduler.runCurrent()
        assertNotNull(m.state.value.preview)
        assertTrue(m.state.value.loginVisible)
        assertFalse(m.state.value.previewVisible)
        assertEquals(0, m.state.value.importRevision)
    }

    @Test fun `failed pull refresh keeps old preview visible and disables stale import`() {
        var offline = false
        val m = model { if (offline) error("offline") else successful(it) }
        m.read(); dispatcher.scheduler.runCurrent()
        val updated = m.state.value.previewUpdatedAt
        offline = true
        m.read(); dispatcher.scheduler.runCurrent()
        assertNotNull(m.state.value.preview)
        assertTrue(m.state.value.previewVisible)
        assertTrue(m.state.value.previewNeedsRefresh)
        assertEquals(updated, m.state.value.previewUpdatedAt)
        assertFalse(m.state.value.busy)
        m.importSelected(); dispatcher.scheduler.runCurrent()
        assertEquals(0, m.state.value.importRevision)
    }

    @Test fun `repeated pulls do not overlap and stopping ends refresh indicator`() {
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val m = model { reads++; release.await(); error("offline") }
        m.read(); m.read(); dispatcher.scheduler.runCurrent()
        assertEquals(1, reads)
        m.cancelRead(); dispatcher.scheduler.runCurrent()
        assertFalse(m.state.value.busy)
        release.complete(Unit); dispatcher.scheduler.runCurrent()
        assertNull(m.state.value.preview)
    }

    @Test fun `invalid range makes no network request`() {
        var calls = 0
        val m = model { calls++; error("must not connect") }
        m.configure(start = "2026-99-99")
        m.read(); dispatcher.scheduler.runCurrent()
        assertEquals(0, calls); assertFalse(m.state.value.busy); assertNotNull(m.state.value.message)
    }

    @Test fun `stopped old request cannot publish late data over next read`() {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val m = model { url ->
            calls++
            if (calls == 1) withContext(NonCancellable) { release.await(); "late cookie" }
            else if (url == CampusUrls.COOKIE) "new login expired" else error("no other calls expected")
        }
        m.read(); dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.busy)
        m.cancelRead()
        m.read(); dispatcher.scheduler.runCurrent()
        val message = m.state.value.message
        assertFalse(m.state.value.busy)
        release.complete(Unit); dispatcher.scheduler.runCurrent()
        assertEquals(message, m.state.value.message)
        assertNull(m.state.value.preview)
    }

    @Test fun `changing identity clears any prior selection before next login`() {
        val m = model { "expired" }
        m.login(); assertTrue(m.state.value.loginVisible)
        m.closeLogin(); m.configure(graduate = true)
        assertTrue(m.state.value.graduate)
        assertNull(m.state.value.preview); assertTrue(m.state.value.selected.isEmpty())
    }
}

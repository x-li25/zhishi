package com.zhishi.schedule.ui.screens

import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.data.*
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
class CampusAuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<CampusAuthViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    private class Store(var credentials: CampusCredentials? = null) : CampusCredentialStorage {
        var saves = 0
        override fun read() = credentials
        override fun save(credentials: CampusCredentials) { this.credentials = credentials; saves++ }
        override fun clear() { credentials = null }
    }
    private class Gateway(var cookies: Boolean = false) : CampusAuthGateway {
        var verifies = 0
        var clears = 0
        var verifyAction: suspend () -> Unit = {}
        override fun hasSession() = cookies
        override suspend fun verify(graduate: Boolean) { verifies++; verifyAction() }
        override suspend fun clearSession() { clears++; cookies = false }
    }
    private fun model(gateway: Gateway = Gateway(), store: Store = Store()) = CampusAuthViewModel(
        RuntimeEnvironment.getApplication(), gateway, store).also { models += it }

    @Test fun `saved login is silent and only explicit official login requests a visible authentication page`() {
        val m = model(store = Store(CampusCredentials("student", "secret")))
        dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.browserVisible); assertFalse(m.state.value.manualVerification)
        m.cancelBrowser(); m.officialLogin()
        assertTrue(m.state.value.manualVerification)
        m.cancelBrowser(); assertFalse(m.state.value.manualVerification)
        m.login("student", "secret"); dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.browserVisible); assertFalse(m.state.value.manualVerification)
    }

    @Test fun `no credentials or cookies cannot enter main screen`() {
        val m = model(); dispatcher.scheduler.runCurrent()
        assertFalse(m.state.value.authenticated)
        assertFalse(m.state.value.checking)
        assertFalse(m.state.value.browserVisible)
        m.verified() // A late callback from a dismissed browser cannot unlock the app.
        assertFalse(m.state.value.authenticated)
    }

    @Test fun `valid local session is verified and reused without another login`() {
        val gateway = Gateway(true)
        val store = Store(CampusCredentials("student", "secret"))
        val m = model(gateway, store)
        dispatcher.scheduler.runCurrent()
        assertEquals(1, gateway.verifies)
        assertEquals(0, gateway.clears)
        assertTrue(m.state.value.authenticated)
        assertFalse(m.state.value.browserVisible)
        assertNull(m.state.value.credentials)
        m.restore(); dispatcher.scheduler.runCurrent()
        assertEquals(1, gateway.verifies)
    }

    @Test fun `cookies alone are never treated as successful authentication`() {
        val gateway = Gateway(true).apply { verifyAction = { error("not authorized") } }
        val m = model(gateway); dispatcher.scheduler.runCurrent()
        assertFalse(m.state.value.authenticated)
        assertTrue(m.state.value.browserVisible) // Warm up the existing official SSO without another login click.
        assertFalse(m.state.value.checking)
    }

    @Test fun `expired session automatically opens saved official login but keeps main locked`() {
        val gateway = Gateway(true).apply { verifyAction = { error("expired") } }
        val store = Store(CampusCredentials("student", "secret"))
        val m = model(gateway, store); dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.browserVisible)
        assertFalse(m.state.value.authenticated)
        assertEquals("student", m.state.value.credentials?.studentId)
        assertEquals(0, store.saves)
        m.verified()
        assertTrue(m.state.value.authenticated)
        assertNull(m.state.value.credentials)
        assertEquals(1, store.saves)
    }

    @Test fun `new credentials clear previous account and are saved only after verification`() {
        val gateway = Gateway()
        val store = Store()
        val m = model(gateway, store); dispatcher.scheduler.runCurrent()
        m.login(" new-student ", "new-secret")
        m.login("duplicate", "secret")
        dispatcher.scheduler.runCurrent()
        assertEquals(1, gateway.clears)
        assertFalse(m.state.value.authenticated)
        assertEquals("new-student", m.state.value.credentials?.studentId)
        assertEquals(0, store.saves)
        m.verified()
        assertTrue(m.state.value.authenticated)
        assertEquals("new-student", store.credentials?.studentId)
        assertEquals(1, store.saves)
    }

    @Test fun `cancelling official verification cannot unlock main or save credentials`() {
        val store = Store()
        val m = model(store = store); dispatcher.scheduler.runCurrent()
        m.login("student", "secret"); dispatcher.scheduler.runCurrent()
        m.cancelBrowser(); m.verified()
        assertFalse(m.state.value.authenticated)
        assertFalse(m.state.value.browserVisible)
        assertNull(m.state.value.credentials)
        assertEquals(0, store.saves)
    }

    @Test fun `rejected password returns to native form and ignores stale completion`() {
        val store = Store(CampusCredentials("student", "old-secret"))
        val m = model(store = store); dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.browserVisible)
        m.credentialsRejected()
        assertNull(m.state.value.credentials)
        assertNull(store.credentials)
        assertFalse(m.state.value.authenticated)
        assertFalse(m.state.value.browserVisible)
        assertFalse(m.state.value.checking)
        assertFalse(m.state.value.manualVerification)
        assertNotNull(m.state.value.error)
        assertEquals("student", m.state.value.studentId)
        m.verified()
        assertFalse(m.state.value.authenticated)
        m.officialLogin()
        m.verified()
        assertTrue(m.state.value.authenticated)
        assertEquals(0, store.saves)
    }

    @Test fun `corrected password can log in from the native form after rejection`() {
        val store = Store(CampusCredentials("student", "old-secret"))
        val gateway = Gateway()
        val m = model(gateway, store); dispatcher.scheduler.runCurrent()
        m.credentialsRejected()
        m.login("student", "corrected-secret"); dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.browserVisible)
        assertFalse(m.state.value.manualVerification)
        assertFalse(m.state.value.authenticated)
        assertEquals(1, gateway.clears)
        assertNull(m.state.value.error)
        assertEquals(0, store.saves)
        m.verified()
        assertEquals("corrected-secret", store.credentials?.password)
        assertTrue(m.state.value.authenticated)
    }

    @Test fun `native cancel during startup check prevents late entry and permits password login`() {
        val response = CompletableDeferred<Unit>()
        val gateway = Gateway(true).apply { verifyAction = { response.await() } }
        val m = model(gateway)
        dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.checking)
        m.cancelBrowser()
        response.complete(Unit); dispatcher.scheduler.runCurrent()
        assertFalse(m.state.value.authenticated)
        assertFalse(m.state.value.checking)
        m.login("student", "secret"); dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.browserVisible)
        assertFalse(m.state.value.manualVerification)
    }

    @Test fun `logout clears saved credentials and returns to gate`() {
        val store = Store(CampusCredentials("student", "secret"))
        val m = model(Gateway(true), store); dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.authenticated)
        CampusSession.signOut(); dispatcher.scheduler.runCurrent()
        assertFalse(m.state.value.authenticated)
        assertNull(store.credentials)
        m.verified()
        assertFalse(m.state.value.authenticated)
    }

    @Test fun `logout cancels an in flight session check and rejects late completion`() {
        val response = CompletableDeferred<Unit>()
        val gateway = Gateway(true).apply { verifyAction = { response.await() } }
        val store = Store(CampusCredentials("student", "secret"))
        val m = model(gateway, store); dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.checking)
        CampusSession.signOut(); dispatcher.scheduler.runCurrent()
        response.complete(Unit); dispatcher.scheduler.runCurrent()
        assertFalse(m.state.value.authenticated)
        assertFalse(m.state.value.browserVisible)
    }

    @Test fun `timeout keeps main locked and allows user to retry`() {
        val gateway = Gateway(true).apply { verifyAction = { awaitCancellation() } }
        val m = model(gateway)
        dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(30_001); dispatcher.scheduler.runCurrent()
        assertFalse(m.state.value.authenticated)
        assertFalse(m.state.value.checking)
        m.cancelBrowser()
        gateway.verifyAction = {}
        m.restore(); dispatcher.scheduler.runCurrent()
        assertTrue(m.state.value.authenticated)
    }
}

package com.zhishi.schedule.ui.screens

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhishi.schedule.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.resume

interface CampusAuthGateway {
    fun hasSession(): Boolean
    suspend fun verify(graduate: Boolean)
    suspend fun clearSession()
}

private class SchoolAuthGateway(private val app: Application) : CampusAuthGateway {
    override fun hasSession() = !CookieManager.getInstance().getCookie(CampusUrls.VPN).isNullOrBlank() ||
        !CookieManager.getInstance().getCookie("https://id.tsinghua.edu.cn").isNullOrBlank()
    override suspend fun verify(graduate: Boolean) {
        val transport = CampusHttpTransport(CookieManager.getInstance(), WebSettings.getDefaultUserAgent(app))
        CampusClient(transport).verifyLogin(graduate)
        HomeworkClient(transport).verifyLogin()
        CookieManager.getInstance().flush()
    }
    override suspend fun clearSession() {
        WebStorage.getInstance().deleteAllData()
        suspendCancellableCoroutine { continuation ->
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}

data class CampusAuthState(
    val checking: Boolean = true,
    val authenticated: Boolean = false,
    val studentId: String = "",
    val browserVisible: Boolean = false,
    val credentials: CampusCredentials? = null,
    val error: String? = null,
    val manualVerification: Boolean = false
)

/** The main screen is mounted only after a real school response has been verified. */
class CampusAuthViewModel @JvmOverloads constructor(app: Application,
    private val gateway: CampusAuthGateway = SchoolAuthGateway(app),
    private val store: CampusCredentialStorage = CampusCredentialStore(app)
) : AndroidViewModel(app) {
    private val mutable = MutableStateFlow(CampusAuthState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0L

    init {
        restore(app.getSharedPreferences("campus", android.content.Context.MODE_PRIVATE).getBoolean("graduate", false))
        viewModelScope.launch {
            var observed = CampusSession.logouts.value
            CampusSession.logouts.collect { current ->
                if (current != observed) {
                    observed = current
                    generation++; work?.cancel()
                    runCatching { store.clear() }
                    mutable.value = CampusAuthState(checking = false)
                }
            }
        }
    }

    fun restore(graduate: Boolean = false) {
        if (work?.isActive == true || mutable.value.authenticated || mutable.value.browserVisible) return
        val token = ++generation
        mutable.update { it.copy(checking = true, error = null) }
        work = viewModelScope.launch {
            val saved = runCatching { store.read() }
            val credentials = saved.getOrNull()
            var hadSession = false
            try {
                hadSession = gateway.hasSession()
                if (hadSession) {
                    withTimeout(30_000) { gateway.verify(graduate) }
                    ensureActive()
                    if (token == generation) mutable.value = CampusAuthState(checking = false, authenticated = true,
                        studentId = credentials?.studentId.orEmpty())
                    return@launch
                }
            } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
            catch (_: Exception) { /* Try the saved official form once, including any required school verification. */ }
            if (token == generation) mutable.value = CampusAuthState(checking = false,
                studentId = credentials?.studentId.orEmpty(), browserVisible = credentials != null || hadSession, credentials = credentials,
                error = if (saved.isFailure) "本机登录信息无法解密，请重新登录" else null)
        }
    }

    fun login(studentId: String, password: String) {
        if (mutable.value.checking || mutable.value.browserVisible) return
        if (studentId.isBlank() || password.isBlank()) {
            mutable.update { it.copy(error = "请填写学号和密码") }; return
        }
        val token = ++generation
        work?.cancel()
        mutable.update { it.copy(checking = true, error = null, studentId = studentId.trim()) }
        CampusSession.invalidate()
        work = viewModelScope.launch {
            try {
                // A manually entered account must never silently inherit another person's session.
                gateway.clearSession()
                ensureActive()
                if (token == generation) mutable.update { it.copy(checking = false, browserVisible = true,
                    credentials = CampusCredentials(studentId.trim(), password)) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation) mutable.update { it.copy(checking = false, error = "无法准备登录，请重试") }
            }
        }
    }

    fun officialLogin() {
        if (mutable.value.checking || mutable.value.browserVisible) return
        mutable.update { it.copy(browserVisible = true, credentials = null, error = null, manualVerification = true) }
    }

    fun cancelBrowser() {
        generation++; work?.cancel()
        mutable.update { it.copy(checking = false, browserVisible = false, credentials = null, manualVerification = false,
            error = "尚未完成学校验证，请登录后进入知时") }
    }

    fun credentialsRejected() {
        if (!mutable.value.browserVisible) return
        generation++; work?.cancel()
        runCatching { store.clear() }
        mutable.update { it.copy(checking = false, browserVisible = false, manualVerification = false,
            credentials = null, error = "学校未接受本次登录，请核对账号密码后重试") }
    }

    fun verified() {
        if (!mutable.value.browserVisible) return
        // Only called by CampusLoginDialog after both protected services have answered successfully.
        val credentials = mutable.value.credentials
        val saved = runCatching { if (credentials != null) store.save(credentials) }
        mutable.update { it.copy(checking = false, authenticated = true, browserVisible = false, credentials = null, manualVerification = false,
            error = if (saved.isFailure) "已登录，但本机登录信息保存失败；下次可能需要重新验证" else null) }
    }
}

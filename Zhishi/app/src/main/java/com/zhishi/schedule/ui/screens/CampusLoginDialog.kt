package com.zhishi.schedule.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zhishi.schedule.data.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CampusLoginProgress(val message: String, val needsAttention: Boolean)

@Composable
fun CampusLoginDialog(graduate: Boolean, onDismiss: () -> Unit, onRead: () -> Unit, learn: Boolean = false,
    onServiceVerified: (CampusLoginTarget) -> Unit = {}, credentials: CampusCredentials? = null,
    onCredentialsRejected: () -> Unit = {}, showBrowserInitially: Boolean = false,
    inlineContent: (@Composable (CampusLoginProgress, () -> Unit, () -> Unit) -> Unit)? = null) {
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var manual by rememberSaveable { mutableStateOf(showBrowserInitially) }
    // A new WebView/flow is created only by an explicit retry, never by a page redirect.
    key(attempt) {
        CampusLoginAttempt(graduate, onDismiss, onRead, learn, onServiceVerified, credentials, onCredentialsRejected,
            manual, onRetry = { showManual -> manual = showManual; attempt++ }, inlineContent = inlineContent)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CampusLoginAttempt(graduate: Boolean, onDismiss: () -> Unit, onRead: () -> Unit, learn: Boolean,
    onServiceVerified: (CampusLoginTarget) -> Unit, credentials: CampusCredentials?, onCredentialsRejected: () -> Unit,
    showBrowserInitially: Boolean, onRetry: (Boolean) -> Unit,
    inlineContent: (@Composable (CampusLoginProgress, () -> Unit, () -> Unit) -> Unit)?) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var pageUrl by remember { mutableStateOf("") }
    var reveal by rememberSaveable { mutableStateOf(showBrowserInitially) }
    var notice by remember { mutableStateOf<String?>(null) }
    var openedPortal by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var navigation by remember { mutableLongStateOf(0L) }
    var verification by remember { mutableStateOf<Job?>(null) }
    var attemptedForms by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    var verificationFailed by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    val flow = remember { CampusLoginFlow(graduate, if (learn) CampusLoginTarget.HOMEWORK else CampusLoginTarget.TIMETABLE) }
    val autofill = remember { CampusAutofillAttempt() }
    val verifiedServices = remember { linkedSetOf<CampusLoginTarget>() }
    var stage by remember { mutableStateOf(flow.target) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val currentCredentials by rememberUpdatedState(credentials)
    val rejectCredentials by rememberUpdatedState(onCredentialsRejected)
    val completeLogin by rememberUpdatedState(onRead)
    val serviceVerified by rememberUpdatedState(onServiceVerified)
    val epoch = remember { CampusSession.changes.value }
    val showPage = reveal && CampusLoginFlow.canShowVerificationPage(pageUrl)

    fun pause(message: String) {
        flow.cancel(); verification?.cancel(); checking = false; web?.stopLoading()
        reveal = false; web?.alpha = 0f; notice = message; stopped = true
    }
    fun dismiss() { flow.cancel(); verification?.cancel(); web?.stopLoading(); onDismiss() }
    fun showVerification() {
        if (flow.target == null || verificationFailed) onRetry(true)
        else { reveal = true; if (!CampusLoginFlow.canShowVerificationPage(pageUrl)) web?.loadUrl(flow.entry()) }
    }
    BackHandler(enabled = inlineContent != null) { if (showPage) reveal = false else dismiss() }
    DisposableEffect(Unit) { onDispose { flow.cancel(); verification?.cancel() } }
    LaunchedEffect(showPage) { if (!showPage) keyboard?.hide() }
    LaunchedEffect(flow, stage, showPage, notice) {
        if (!showPage && notice == null && flow.target != null) {
            delay(45_000)
            if (flow.target != null) pause("自动验证暂未完成。可以重试；需要验证码或学校确认时，请点击“完成学校验证”。")
        }
    }
    LaunchedEffect(Unit) {
        CampusSession.changes.collect { if (it != epoch) pause("学校登录状态已改变，请关闭后重新登录。") }
    }

    val content: @Composable ColumnScope.() -> Unit = {
        if (inlineContent == null || showPage) Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(::dismiss) { Text("返回") }
            Text(if (showPage) "完成学校验证" else "登录知时", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (showPage) TextButton({ reveal = false }) { Text("收起") }
        }
        if (showPage) {
            Text(pageUrl.toHttpUrlOrNull()?.host.orEmpty(), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!stopped) AndroidView(factory = { context ->
                WebView(context).apply {
                    web = this; alpha = 0f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            // Once an API check starts, portal timers/redirects have no work left to do.
                            if (checking) return true
                            val url = request.url.toString()
                            if (!CampusLoginFlow.canShowVerificationPage(url)) view.alpha = 0f
                            val u = url.toHttpUrlOrNull()
                            if (u?.host in setOf("info.tsinghua.edu.cn", "zhjw.cic.tsinghua.edu.cn", "learn.tsinghua.edu.cn")) {
                                val target = runCatching { CampusUrls.proxied(url) }.getOrNull()
                                if (target != null && request.method == "GET") { view.loadUrl(target); return true }
                            }
                            if (CampusUrls.allowedBrowser(url)) return false
                            pause("学校页面出现了不受支持的跳转，请关闭后重试。")
                            return true
                        }
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            navigation++
                            pageUrl = url.orEmpty()
                            if (!CampusLoginFlow.canShowVerificationPage(pageUrl)) { view.alpha = 0f; view.clearFocus() }
                            if (!flow.navigationAllowed(pageUrl)) {
                                if (checking) view.stopLoading()
                                else pause("学校登录反复跳转，已停止本次尝试。请重试，或完成学校验证。")
                            }
                            // An INFO page's own navigation must not cancel a protected API check.
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (url == null || view.url?.substringBefore('#') != url.substringBefore('#') || flow.target == null) return
                            CookieManager.getInstance().flush()
                            val credential = currentCredentials
                            val formKey = url.substringBefore('?')
                            val pageNavigation = navigation
                            if (credential != null && !rejected && autofill.returnedToForm(url, pageNavigation)) {
                                view.evaluateJavascript("""
                                    (function() {
                                      var f = document.getElementById('theform');
                                      if (!f) return 'none';
                                      var c = f.querySelector('#c_code');
                                      return c && c.getClientRects().length > 0 && getComputedStyle(c).visibility !== 'hidden'
                                          ? 'verification' : 'rejected';
                                    })()
                                """.trimIndent()) { result ->
                                    if (navigation != pageNavigation || view.url != url || flow.target == null || epoch != CampusSession.changes.value)
                                        return@evaluateJavascript
                                    if (result == "\"verification\"") {
                                        notice = "请完成学校验证码，验证成功后会自动继续。"
                                        reveal = true
                                    } else if (result == "\"rejected\"") {
                                        rejected = true; rejectCredentials()
                                        notice = "本次自动登录未完成，请核对登录信息并完成学校验证；不会重复提交密码。"
                                    }
                                }
                                return
                            }
                            if (CampusCredentialAutofill.supports(url)) {
                                if (credential != null && !rejected && formKey !in attemptedForms && autofill.claim(url)) {
                                    attemptedForms = attemptedForms + formKey
                                    view.evaluateJavascript(CampusCredentialAutofill.script(url, credential)) { result ->
                                        if (flow.target == null || epoch != CampusSession.changes.value) return@evaluateJavascript
                                        if (result == "\"submitted\"") autofill.submitted(url, pageNavigation)
                                        else if (navigation == pageNavigation && view.url == url) {
                                            notice = "需要验证码或其他学校确认，请点击“完成学校验证”。"
                                            // An actual visible captcha needs the user's input. Keep the
                                            // same WebView/form; do not restart SSO or fill the password twice.
                                            if (result == "\"verification\"") reveal = true
                                        }
                                    }
                                } else if (credential == null || rejected) {
                                    notice = "需要完成学校验证后才能进入知时。"
                                }
                                return
                            }
                            val u = url.toHttpUrlOrNull()
                            if (u?.host in setOf("info.tsinghua.edu.cn", "learn.tsinghua.edu.cn")) {
                                // A completed SSO POST is followed by a new protected GET, never replayed.
                                val target = runCatching { CampusUrls.proxied(url) }.getOrNull()
                                if (target != null) { view.loadUrl(target); return }
                            }
                            if (!openedPortal && u?.host == "webvpn.tsinghua.edu.cn" && u.encodedPath in setOf("/", "/index", "/portal")) {
                                openedPortal = true; view.loadUrl(flow.entry()); return
                            }
                            val candidate = flow.beginVerification(url) ?: return
                            checking = true; verificationFailed = false; notice = null
                            verification = scope.launch {
                                try {
                                    withTimeout(30_000) {
                                        val transport = CampusHttpTransport(CookieManager.getInstance(), view.settings.userAgentString)
                                        if (candidate == CampusLoginTarget.HOMEWORK) HomeworkClient(transport).verifyLogin()
                                        else CampusClient(transport).verifyLogin(graduate)
                                    }
                                    ensureActive(); CampusSession.check(epoch)
                                    if (flow.target != candidate) return@launch
                                    verifiedServices += candidate
                                    val finished = flow.verified(candidate)
                                    stage = flow.target; checking = false
                                    CookieManager.getInstance().flush()
                                    if (finished) {
                                        view.stopLoading()
                                        verifiedServices.forEach { serviceVerified(it) }
                                        completeLogin()
                                    } else {
                                        view.stopLoading(); view.loadUrl(flow.entry())
                                    }
                                } catch (e: CancellationException) {
                                    if (e is TimeoutCancellationException) { verificationFailed = true; pause("学校验证超时，请检查网络后重试。") } else throw e
                                } catch (_: Exception) {
                                    if (epoch == CampusSession.changes.value && flow.target == candidate) {
                                        verificationFailed = true
                                        pause("学校授权尚未完成，请重试自动验证，或完成学校验证。")
                                    }
                                } finally { checking = false }
                            }
                        }
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            handler.cancel(); pause("学校页面证书验证失败，请检查网络和系统时间后重试。")
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame && request.url.toString() == view.url && flow.target != null)
                                pause("学校页面连接失败，请检查网络后重试。")
                        }
                    }
                    loadUrl(if (!CookieManager.getInstance().getCookie(CampusUrls.VPN).isNullOrBlank()) flow.entry() else CampusUrls.LOGIN)
                }
            }, modifier = Modifier.fillMaxSize(), update = { view ->
                view.alpha = if (showPage) 1f else 0f
                view.importantForAccessibility = if (showPage) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                view.descendantFocusability = if (showPage) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
                if (!showPage) view.clearFocus()
            }, onRelease = { it.stopLoading(); it.webViewClient = WebViewClient(); it.destroy(); web = null })
            if (!showPage) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (inlineContent != null) {
                    inlineContent(CampusLoginProgress(
                        notice ?: if (checking) "正在确认学校授权…" else "正在登录，完成后自动进入…",
                        needsAttention = notice != null
                    ), { onRetry(false) }, ::showVerification)
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("知时", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(24.dp))
                    if (notice == null) CircularProgressIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.height(20.dp))
                    Text(if (notice == null) (if (checking) "正在确认学校授权…" else "正在恢复学校登录…") else "需要完成登录",
                        style = MaterialTheme.typography.titleMedium)
                    Text(notice ?: if (stage == CampusLoginTarget.HOMEWORK) "正在验证作业服务，完成后自动进入。" else "正在验证课表服务，完成后自动进入。",
                        Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    if (notice != null) {
                        Button({ onRetry(false) }) { Text("重试自动验证") }
                        TextButton(::showVerification) { Text("完成学校验证") }
                    }
                }
            }
        }
    }
    if (inlineContent != null) {
        // The native account form stays in this screen while the same hidden WebView
        // completes SSO. Only a verification challenge exposes the school form.
        Column(if (showPage) Modifier.fillMaxSize().systemBarsPadding().imePadding() else Modifier.fillMaxSize(), content = content)
    } else FullScreenPageDialog(::dismiss, content = content)
}

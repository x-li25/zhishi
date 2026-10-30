package com.zhishi.schedule.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zhishi.schedule.data.LibraryService
import com.zhishi.schedule.data.LibraryUrls

@Composable
fun LibraryReservationScreen() {
    var service by rememberSaveable { mutableStateOf<LibraryService?>(null) }
    var vpn by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!vpn, { vpn = false }, label = { Text("官网直连") })
                FilterChip(vpn, { vpn = true }, label = { Text("学校 WebVPN") })
            }
            Text("默认直连官方预约页面；访问失败时可切换学校 WebVPN。已有学校登录状态可复用，如需再次认证，请按学校页面提示完成。",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LibraryService.entries.forEach { entry -> item {
            Card(onClick = { service = entry }, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(if (entry == LibraryService.SEATS) Icons.Outlined.EventSeat else Icons.Outlined.MeetingRoom, null,
                        Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (entry == LibraryService.SEATS) "选择馆舍、座位和时段；在“我的中心”查看预约" else "选择研读间或研讨间，查看可用时段与预约记录",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        } }
        item { Text("预约结果、签到要求与取消规则以学校页面为准。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    service?.let { LibraryBrowserDialog(it, vpn) { service = null } }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LibraryBrowserDialog(service: LibraryService, initialVpn: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var vpn by rememberSaveable(service, initialVpn) { mutableStateOf(initialVpn) }
    val entry = if (vpn) service.vpnUrl else service.officialUrl
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    fun openOfficialBrowser() {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(service.officialUrl))) }
            .onFailure { error = "未找到可打开网页的浏览器" }
    }
    fun switchConnection() {
        web?.stopLoading()
        error = null
        loading = true
        vpn = !vpn
    }
    fun back() { val current = web; if (current?.canGoBack() == true) current.goBack() else onClose() }
    FullScreenPageDialog(::back) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一页") }
            Column(Modifier.weight(1f)) {
                Text(service.title, style = MaterialTheme.typography.titleMedium)
                Text(if (vpn) "学校 WebVPN" else "图书馆官网", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                DropdownMenu(menu, { menu = false }) {
                    // Always open a known GET entry. Reloading the last page could replay a reservation POST.
                    DropdownMenuItem(text = { Text("返回预约入口") }, onClick = { menu = false; web?.loadUrl(entry) })
                    DropdownMenuItem(text = { Text(if (vpn) "切换官网直连" else "切换学校 WebVPN") }, onClick = {
                        menu = false; switchConnection()
                    })
                    DropdownMenuItem(text = { Text("在浏览器打开官网") }, onClick = {
                        menu = false
                        openOfficialBrowser()
                    })
                }
            }
            IconButton(onClose) { Icon(Icons.Outlined.Close, "关闭预约页面") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Row {
                        TextButton(::switchConnection) { Text(if (vpn) "切换官网直连" else "切换学校 WebVPN") }
                        TextButton(::openOfficialBrowser) { Text("浏览器打开") }
                    }
                }
            }
        }
        // Recreate on an explicit mode switch: callbacks must not capture the previous mode,
        // and browsing history must not replay an earlier reservation POST.
        key(service, vpn) { AndroidView(factory = { browserContext ->
            WebView(browserContext).apply {
                web = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webChromeClient = WebChromeClient() // Official dialogs/SPA scripts; no native JavaScript bridge.
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        val url = request.url.toString()
                        val target = LibraryUrls.navigation(url, vpn)
                        if (target == null) { loading = false; error = LibraryUrls.unsupportedMessage(url); return true }
                        if (target != url && request.method == "GET") { view.loadUrl(target); return true }
                        return false
                    }
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        // A new-window navigation can briefly create an empty document.
                        if (url == "about:blank") return
                        if (!LibraryUrls.allowed(url.orEmpty())) { view.stopLoading(); loading = false; error = LibraryUrls.unsupportedMessage(url.orEmpty()); return }
                        loading = true; error = null
                    }
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (url == view.url) { loading = false; CookieManager.getInstance().flush() }
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                        if (request.isForMainFrame) { loading = false; error = "页面加载失败。可返回选择官网直连，或检查网络后重开入口。若刚确认过预约，请先查看预约记录。" }
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) { loading = false; error = "学校页面暂时无法访问（${response.statusCode}）。若刚确认过预约，请先查看预约记录。" }
                    }
                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, failure: SslError) {
                        handler.cancel(); loading = false; error = "学校页面证书验证失败，请检查网络和系统时间。"
                    }
                }
                loadUrl(entry)
            }
        }, modifier = Modifier.weight(1f).fillMaxWidth(), onRelease = {
            it.stopLoading(); it.webViewClient = WebViewClient(); it.webChromeClient = null; it.destroy()
            if (web === it) web = null
        }) }
    }
}

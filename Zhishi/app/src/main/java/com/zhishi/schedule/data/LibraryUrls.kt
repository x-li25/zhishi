package com.zhishi.schedule.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class LibraryService(val title: String, val host: String, val path: String, val vpnHost: String) {
    SEATS("座位预约", "seat.lib.tsinghua.edu.cn", "/home/web/f_second", "77726476706e69737468656265737421e3f24088693c6152301c9aa596522b204c02212b859d0a19"),
    ROOMS("研读间 / 研讨间", "cab.lib.tsinghua.edu.cn", "/", "77726476706e69737468656265737421f3f643d22b396a1e6a1b80a29f5d363409e413829737d1");

    val officialUrl get() = "https://$host$path"
    val vpnUrl get() = "${CampusUrls.VPN}/https/$vpnHost$path"
}

/** Separate browser policy: do not broaden the teaching-system API or send AI credentials here. */
object LibraryUrls {
    private val hosts = LibraryService.entries.map { it.host }.toSet() +
        setOf("id.tsinghua.edu.cn", "oauth.tsinghua.edu.cn", "webvpn.tsinghua.edu.cn")

    fun allowed(url: String): Boolean {
        val target = url.toHttpUrlOrNull() ?: return false
        return target.scheme == "https" && target.port == 443 && target.username.isEmpty() && target.password.isEmpty() && target.host in hosts
    }

    /** Old official links can still use HTTP. Upgrade known hosts before loading them;
     * never enable cleartext traffic or relax the teaching-system API policy. */
    fun navigation(url: String, vpn: Boolean): String? {
        val target = url.toHttpUrlOrNull() ?: return null
        if (target.host !in hosts || target.username.isNotEmpty() || target.password.isNotEmpty()) return null
        val secure = when {
            target.scheme == "https" && target.port == 443 -> target
            target.scheme == "http" && target.port == 80 -> target.newBuilder().scheme("https").port(443).build()
            else -> return null
        }
        val service = LibraryService.entries.firstOrNull { it.host == target.host }
        return if (vpn && service != null) {
            "${CampusUrls.VPN}/https/${service.vpnHost}${secure.encodedPath}" +
                (secure.encodedQuery?.let { "?$it" } ?: "") + (secure.encodedFragment?.let { "#$it" } ?: "")
        } else secure.toString()
    }

    /** Show only the origin: callback queries can contain authentication tickets. */
    fun unsupportedMessage(url: String): String {
        val target = url.toHttpUrlOrNull()
        val origin = target?.let { "${it.scheme}://${it.host}:${it.port}" } ?: "非网页链接"
        return "暂不支持打开 $origin。可切换访问方式，或在浏览器打开官方入口。"
    }
}

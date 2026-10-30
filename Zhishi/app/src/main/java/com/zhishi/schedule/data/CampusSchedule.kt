package com.zhishi.schedule.data

import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Independent protocol implementation; no password, token or login HTML enters the AI context. */
object CampusUrls {
    const val VPN = "https://webvpn.tsinghua.edu.cn"
    const val LOGIN = "$VPN/login?oauth_login=true"
    const val INFO_AUTH = "https://id.tsinghua.edu.cn/do/off/ui/auth/login/form/10000ea055dd8d81d09d5a1ba55d39ad"
    const val LEARN_AUTH = "https://id.tsinghua.edu.cn/do/off/ui/auth/login/form/bb5df85216504820be7bba2b0ae1535b/0"
    const val LEARN = "$VPN/https/77726476706e69737468656265737421fcf2408e297e7c4377068ea48d546d30ca8cc97bcc"
    const val LEARN_HOME = "$LEARN/f/wlxt/index/course/student/"
    private const val INFO_HASH = "77726476706e69737468656265737421f9f9479369247b59700f81b9991b2631506205de"
    private const val TEACH_HASH = "77726476706e69737468656265737421eaff4b8b69336153301c9aa596522b20bc86e6e559a9b290"
    const val INFO = "$VPN/https/$INFO_HASH"
    const val TEACH = "$VPN/http/$TEACH_HASH"
    const val COOKIE = "$VPN/wengine-vpn/cookie?method=get&host=info.tsinghua.edu.cn&scheme=https&path=/f/info/gxfw_fg/common/index"
    const val ROAM = "$INFO/b/yyfw/vyyfwxx/info/portal_fg/common/onlineAppRedirect"
    private val browserHosts = setOf("webvpn.tsinghua.edu.cn", "id.tsinghua.edu.cn", "oauth.tsinghua.edu.cn", "info.tsinghua.edu.cn", "learn.tsinghua.edu.cn")

    fun allowedBrowser(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return u.scheme == "https" && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() && u.host in browserHosts
    }

    fun allowedApi(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 &&
        url.host == "webvpn.tsinghua.edu.cn" && url.username.isEmpty() && url.password.isEmpty()

    /** Only the two documented campus targets may be translated into a VPN URL. */
    fun proxied(url: String): String {
        val u = url.replace("&amp;", "&").toHttpUrlOrNull() ?: error("学校返回的跳转地址无效")
        require(u.username.isEmpty() && u.password.isEmpty()) { "学校跳转地址不受支持" }
        if (allowedApi(u)) return u.toString()
        val prefix = when {
            u.host == "learn.tsinghua.edu.cn" && u.port == 443 && u.scheme == "https" -> LEARN
            u.host == "info.tsinghua.edu.cn" && u.port == 443 && u.scheme == "https" -> INFO
            u.host == "zhjw.cic.tsinghua.edu.cn" && u.port == 80 && u.scheme == "http" -> TEACH
            else -> error("学校返回了不受支持的教学系统地址，请在官方页面完成登录")
        }
        return prefix + u.encodedPath + (u.encodedQuery?.let { "?$it" } ?: "")
    }

    fun entry(graduate: Boolean) = "$INFO/f/info/gxfw_fg/common/cardRoaming/JXRL_${if (graduate) "YJS" else "BKS"}"
    fun appId(graduate: Boolean) = if (graduate) "BEABB32641DC4EC3510B048BAF42471A" else "287C0C6D90ABB364CD5FDF1495199962"
    fun schedule(graduate: Boolean, start: LocalDate, end: LocalDate): String =
        "$TEACH/jxmh_out.do".toHttpUrl().newBuilder()
            .addQueryParameter("m", if (graduate) "yjs_jxrl_all" else "bks_jxrl_all")
            .addQueryParameter("p_start_date", start.format(DateTimeFormatter.BASIC_ISO_DATE))
            .addQueryParameter("p_end_date", end.format(DateTimeFormatter.BASIC_ISO_DATE))
            .addQueryParameter("jsoncallback", "m").build().toString()
}

data class CampusEvent(val title: String, val location: String, val category: String,
    val startMillis: Long, val endMillis: Long) {
    val exam: Boolean get() = category.contains("考")
    // Stable even after a user edits the imported calendar item. No school identifier is stored.
    val key: String get() = MessageDigest.getInstance("SHA-256").digest(
        JsonArray(listOf(title, location, category, startMillis.toString(), endMillis.toString()).map(::JsonPrimitive))
            .toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    val marker: String get() = "zhishi://thu-import/$key"
}

object CampusScheduleParser {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    fun chunks(start: LocalDate, end: LocalDate): List<Pair<LocalDate, LocalDate>> {
        require(!end.isBefore(start) && ChronoUnit.DAYS.between(start, end) < 183) { "请选择不超过 183 天的日期范围" }
        return generateSequence(start) { it.plusDays(21) }.takeWhile { it <= end }
            .map { it to minOf(it.plusDays(20), end) }.toList()
    }

    fun parse(raw: String, start: LocalDate, end: LocalDate): List<CampusEvent> {
        val body = Regex("(?s)^\\s*m\\s*\\((.*)\\)\\s*;?\\s*$").matchEntire(raw)?.groupValues?.get(1)
            ?: error("未收到教学日历数据，请先在官方页面完成登录和教学日历授权")
        val array = Json.parseToJsonElement(body) as? JsonArray ?: error("教学日历数据格式已变化")
        require(array.size <= 5000) { "学校返回数据过多，请缩小日期范围" }
        return array.mapNotNull { row ->
            val o = row as? JsonObject ?: error("教学日历条目格式无效")
            fun field(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val category = field("fl")
            if (category == "个人日历") return@mapNotNull null
            val title = field("nr")
            require(title.isNotBlank() && title.length <= 2048) { "学校返回了无标题或过长的日程，请缩小范围后重试" }
            val ds = field("nq")
            val date = if (Regex("\\d{8}").matches(ds)) LocalDate.parse(ds, DateTimeFormatter.BASIC_ISO_DATE) else LocalDate.parse(ds)
            require(date in start..end) { "学校返回了范围以外的日程，请重试" }
            fun time(key: String): Long {
                val t = LocalTime.parse(field(key).replace('：', ':'))
                return date.atTime(t).atZone(zone).toInstant().toEpochMilli()
            }
            val begin = time("kssj"); val finish = time("jssj")
            require(finish > begin) { "学校返回了未确定的时间，请在教学日历中核实后重试" }
            val location = field("dd")
            require(location.length <= 2048 && category.length <= 100) { "教学日历条目过长" }
            CampusEvent(title, location, category, begin, finish)
        }
    }

    /** Primary calendar may split one lesson into individual periods. Merge only matching same-day lessons. */
    fun merge(rows: List<CampusEvent>): List<CampusEvent> = rows.distinct().groupBy {
        listOf(it.title, it.location, it.category, java.time.Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate().toString())
    }.values.flatMap { group ->
        val merged = mutableListOf<CampusEvent>()
        group.sortedBy { it.startMillis }.forEach { next ->
            val last = merged.lastOrNull()
            if (last != null && next.startMillis <= last.endMillis + Duration.ofMinutes(15).toMillis()) {
                merged[merged.lastIndex] = last.copy(endMillis = maxOf(last.endMillis, next.endMillis))
            } else merged += next
        }
        merged
    }.sortedWith(compareBy<CampusEvent> { it.startMillis }.thenBy { it.title })
}

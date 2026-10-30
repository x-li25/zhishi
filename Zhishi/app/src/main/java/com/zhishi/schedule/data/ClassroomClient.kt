package com.zhishi.schedule.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class ClassroomBuilding(val name: String, val queryName: String, val week: Int)
enum class ClassroomOccupancy(val label: String, val symbol: String) {
    AVAILABLE("未占用", "空"), TEACHING("上课", "课"), EXAM("考试", "考"), BORROWED("借用", "借"), DISABLED("停用", "停"), UNKNOWN("未知", "?")
}
data class ClassroomSlot(val occupancy: ClassroomOccupancy, val detail: String = "")
data class ClassroomRoom(val name: String, val capacity: Int?, val slots: List<ClassroomSlot>)
data class ClassroomWeek(val number: Int, val validWeeks: List<Int>, val dates: List<LocalDate>, val rooms: List<ClassroomRoom>)

interface ClassroomReader {
    suspend fun buildings(): List<ClassroomBuilding>
    suspend fun week(building: ClassroomBuilding, number: Int, reference: LocalDate): ClassroomWeek
}

object ClassroomUrls {
    const val LIST = "${CampusUrls.TEACH}/portal3rd.do?url=/portal3rd.do&m=jasJy_Xs_Js_index"
    const val APP_ID = "40470BB47E0849E9EF717983490BC964"
    fun week(building: ClassroomBuilding, number: Int): String {
        require(number in 1..60 && building.queryName.isNotBlank() && building.queryName.length <= 100)
        val charset = charset("GB2312")
        require(charset.newEncoder().canEncode(building.queryName)) { "楼宇名称编码不受支持" }
        val encoded = URLEncoder.encode(building.queryName, charset.name())
        return "${CampusUrls.TEACH}/pk.classroomctrl.do?m=qyClassroomState&classroom=$encoded&weeknumber=$number"
    }
}

/** Read-only portal protocol; independent Kotlin parser, no upstream business code dependency. */
class ClassroomClient(private val transport: CampusTransport) : ClassroomReader {
    private suspend fun authorize() {
        val cookie = transport.get(CampusUrls.COOKIE)
        val csrf = Regex("XSRF-TOKEN=([^;\\s\"\\\\]+)").find(cookie)?.groupValues?.get(1)
            ?: error("学校登录已过期，请重新完成学校登录")
        val url = CampusUrls.ROAM.toHttpUrl().newBuilder().addQueryParameter("yyfwid", ClassroomUrls.APP_ID)
            .addQueryParameter("_csrf", csrf).addQueryParameter("machine", "p").build().toString()
        val target = try { Json.parseToJsonElement(transport.get(url)).jsonObject["object"]?.jsonObject?.get("roamingurl")?.jsonPrimitive?.content }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        require(!target.isNullOrBlank()) { "教室资源尚未授权，请重新完成学校登录后刷新" }
        transport.get(CampusUrls.proxied(target))
    }
    override suspend fun buildings(): List<ClassroomBuilding> {
        authorize()
        val html = transport.get(ClassroomUrls.LIST)
        return withContext(Dispatchers.Default) { ClassroomParser.buildings(html) }
    }
    override suspend fun week(building: ClassroomBuilding, number: Int, reference: LocalDate): ClassroomWeek {
        authorize()
        val html = transport.get(ClassroomUrls.week(building, number))
        return withContext(Dispatchers.Default) { ClassroomParser.week(html, number, reference) }
    }
}

object ClassroomParser {
    fun buildings(html: String): List<ClassroomBuilding> {
        val root = ClassroomHtml.parse(html)
        val result = root.descendants("a").mapNotNull { node ->
            val href = node.attributes["href"].orEmpty()
            if (!href.contains("pk.classroomctrl.do") || !href.contains("m=qyClassroomState")) return@mapNotNull null
            val raw = Regex("(?:[?&])classroom=([^&]+)").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val number = Regex("(?:[?&])weeknumber=(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val query = if (raw.contains('%') || raw.contains('+')) URLDecoder.decode(raw, "GB18030") else raw
            val name = node.text().trim()
            require(number in 1..60 && name.isNotBlank() && name.length <= 100 && query.length <= 100) { "学校楼宇列表格式无效" }
            ClassroomBuilding(name, query, number)
        }.distinctBy { it.queryName }.toList()
        require(result.isNotEmpty() && result.size <= 200) { "未读取到楼宇列表，请检查学校登录后刷新" }
        return result
    }

    fun week(html: String, number: Int, reference: LocalDate): ClassroomWeek {
        require(number in 1..60)
        val root = ClassroomHtml.parse(html)
        val selector = root.descendants("select").firstOrNull { it.attributes["id"] == "weeknumber" || it.attributes["name"] == "weeknumber" }
            ?: error("未读取到教室周次，请重新登录或稍后刷新")
        val options = selector.descendants("option").toList()
        val valid = options.mapNotNull { it.attributes["value"]?.toIntOrNull() }.filter { it in 1..60 }.distinct()
        require(number in valid) { "学校未开放这个周次的教室数据" }
        options.firstOrNull { "selected" in it.attributes }?.attributes?.get("value")?.toIntOrNull()?.let {
            require(it == number) { "学校返回的周次与请求不一致，请刷新" }
        }
        val headings = root.descendants().filter { it.attributes["colspan"] == "6" }.map { it.text() }
            .filter { Regex("[（(]\\s*\\d{1,2}[.月/\\-]\\d{1,2}").containsMatchIn(it) }.take(7).toList()
        require(headings.size == 7) { "学校教室日期表格式已变化，请稍后刷新" }
        val monthDays = headings.map { text ->
            val m = Regex("[（(]\\s*(\\d{1,2})[.月/\\-](\\d{1,2})").find(text) ?: error("教室日期无效")
            m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        val first = (reference.year - 1..reference.year + 1).mapNotNull { year ->
            runCatching { LocalDate.of(year, monthDays[0].first, monthDays[0].second) }.getOrNull()
        }.minByOrNull { abs(ChronoUnit.DAYS.between(reference, it)) } ?: error("教室日期无效")
        require(first.dayOfWeek == DayOfWeek.MONDAY && abs(ChronoUnit.DAYS.between(reference, first)) <= 30) { "学校返回的日期与查询不符，请刷新" }
        val dates = (0L..6).map { first.plusDays(it) }
        require(dates.map { it.monthValue to it.dayOfMonth } == monthDays) { "学校日期不连续，请刷新" }
        val content = root.descendants().firstOrNull { it.attributes["id"] == "scrollContent" }
            ?: error("未读取到教室状态，请检查学校登录")
        val rooms = content.descendants("tr").mapNotNull { row ->
            val cells = row.children.filter { it.tag == "td" || it.tag == "th" }
            if (cells.isEmpty() || cells.first().tag == "th") return@mapNotNull null
            if (cells.size == 1 && cells.first().attributes["colspan"] != null) return@mapNotNull null
            require(cells.size == 43 && cells.drop(1).all { it.attributes["colspan"].orEmpty() in listOf("", "1") && it.attributes["rowspan"].orEmpty() in listOf("", "1") }) {
                "教室时段数量不完整，暂不显示占用表，请刷新"
            }
            val identity = cells.first().text().trim().split(Regex("[:：]"), limit = 2)
            val name = identity[0].trim()
            require(name.isNotEmpty() && name.length <= 100) { "教室名称格式无效" }
            val capacity = identity.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it in 1..10000 }
            val slots = cells.drop(1).map { cell ->
                val classes = cell.attributes["class"].orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() && it != "colBound" }
                val status = when (classes.singleOrNull()) {
                    "onteaching" -> ClassroomOccupancy.TEACHING
                    "onexam" -> ClassroomOccupancy.EXAM
                    "onborrowed" -> ClassroomOccupancy.BORROWED
                    "ondisabled" -> ClassroomOccupancy.DISABLED
                    null -> if (classes.isEmpty() && cell.text().isBlank()) ClassroomOccupancy.AVAILABLE else ClassroomOccupancy.UNKNOWN
                    else -> ClassroomOccupancy.UNKNOWN
                }
                val detail = (listOfNotNull(cell.attributes["title"]) + cell.descendants().mapNotNull { it.attributes["title"] }.toList() + cell.text())
                    .map { it.trim() }.filter { it.isNotBlank() && it !in listOf("·", "*", "●") }.distinct().joinToString("\n").take(1500)
                ClassroomSlot(status, detail)
            }
            ClassroomRoom(name, capacity, slots)
        }.toList()
        require(rooms.size <= 1000 && rooms.map { it.name }.distinct().size == rooms.size) { "学校教室列表格式无效" }
        return ClassroomWeek(number, valid, dates, rooms)
    }
}

/** Bounded structural HTML reader for the legacy table, including optional td/tr/option end tags. */
private object ClassroomHtml {
    class Node(val tag: String, val attributes: Map<String, String>) {
        val children = mutableListOf<Node>()
        val content = StringBuilder()
        fun text() = content.toString().replace('\u00a0', ' ').trim()
        fun descendants(tag: String? = null): Sequence<Node> = children.asSequence().flatMap { child ->
            sequence { if (tag == null || child.tag == tag) yield(child); yieldAll(child.descendants(tag)) }
        }
    }
    private val tokens = Regex("""<!--[\s\S]*?-->|<![^>]*>|<(?:"[^"]*"|'[^']*'|[^'">])*>|[^<]+""")
    private val attributes = Regex("""([\w:-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""")
    private fun decode(value: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|quot|apos|lt|gt|nbsp);", RegexOption.IGNORE_CASE).replace(value) {
        val code = it.groupValues[1]
        when (code.lowercase()) {
            "amp" -> "&"; "quot" -> "\""; "apos" -> "'"; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "
            else -> runCatching { val n = if (code.startsWith("#x", true)) code.drop(2).toInt(16) else code.drop(1).toInt(); String(Character.toChars(n)) }.getOrDefault("�")
        }
    }
    fun parse(html: String): Node {
        require(html.length <= 2_000_000) { "教室页面过大，请稍后刷新" }
        val clean = Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE).replace(html, "")
        val root = Node("root", emptyMap()); val stack = mutableListOf(root)
        var count = 0
        tokens.findAll(clean).forEach { match ->
            val token = match.value
            if (!token.startsWith('<')) {
                val text = decode(token)
                stack.forEach { if (it.content.length < 4000) it.content.append(text.take(4000 - it.content.length)) }
            } else if (token.startsWith("</")) {
                val tag = token.drop(2).trim().substringBefore(' ').substringBefore('>').lowercase()
                val index = stack.indexOfLast { it.tag == tag }
                if (index > 0) while (stack.size > index) stack.removeAt(stack.lastIndex)
            } else if (!token.startsWith("<!")) {
                val tag = token.drop(1).takeWhile { it.isLetterOrDigit() }.lowercase()
                if (tag.isEmpty()) return@forEach
                val close = when (tag) { "td", "th" -> setOf("td", "th"); "tr" -> setOf("tr"); "option" -> setOf("option"); else -> emptySet() }
                val index = stack.indexOfLast { it.tag in close }
                if (index > 0) while (stack.size > index) stack.removeAt(stack.lastIndex)
                val attrs = attributes.findAll(token.drop(1 + tag.length).removeSuffix(">" )).associate { a ->
                    a.groupValues[1].lowercase() to decode(a.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty())
                }
                require(++count <= 60000 && stack.size < 100) { "教室页面结构过于复杂" }
                val node = Node(tag, attrs); stack.last().children += node
                if (tag !in setOf("br", "img", "input", "meta", "link", "hr", "area", "base", "wbr", "col") && !token.endsWith("/>")) stack += node
            }
        }
        return root
    }
}

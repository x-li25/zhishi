package com.zhishi.schedule.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.zhishi.schedule.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.*

/** Metadata reads and explicitly confirmed submissions. Answers and attachments are never fetched for AI. */
class HomeworkClient(private val transport: CampusTransport) {
    private data class Course(val id: String, val name: String)
    private data class Part(val items: List<CourseHomework>, val warnings: List<String>)
    private val semaphore = Semaphore(4)

    suspend fun submit(item: CourseHomework, text: String, attachment: HomeworkUpload?, acceptReplacement: Boolean) {
        HomeworkSubmissionRules.validate(item, text, attachment, acceptReplacement)
        val fresh = read(HomeworkQuery(HomeworkFilter.ALL, item.courseName))
        require(fresh.complete) { "作业状态未完整读取，请刷新并核对后再提交" }
        val current = fresh.items.firstOrNull { it.key == item.key } ?: error("当前学期找不到这份作业，请刷新后核对")
        require(current.title == item.title && current.courseName == item.courseName && current.status == item.status &&
            current.submittedAt == item.submittedAt) { "学校作业状态已变化，请返回刷新并重新确认" }
        HomeworkSubmissionRules.validate(current, text, attachment, acceptReplacement)
        val csrf = csrfToken()
        currentCoroutineContext().ensureActive()
        val url = "${CampusUrls.LEARN}/b/wlxt/kczy/zy/student/tjzy".toHttpUrl().newBuilder().addQueryParameter("_csrf", csrf).build().toString()
        val raw = transport.submitHomework(url, HomeworkSubmissionPayload(current.id, HomeworkSubmissionRules.html(text), attachment))
        val root = try { Json.parseToJsonElement(raw) as? JsonObject ?: throw HomeworkSubmissionUncertain() }
            catch (_: Exception) { throw HomeworkSubmissionUncertain() }
        when ((root["result"] as? JsonPrimitive)?.contentOrNull) {
            "success" -> Unit
            "failed", "failure", "fail", "error" -> throw HomeworkSubmissionRejected("学校未接受本次提交，请核对截止时间、作业要求或重新登录")
            else -> throw HomeworkSubmissionUncertain()
        }
    }

    suspend fun verifyLogin() {
        val csrf = csrfToken()
        val url = "${CampusUrls.LEARN}/b/kc/zhjw_v_code_xnxq/getCurrentAndNextSemester".toHttpUrl()
            .newBuilder().addQueryParameter("_csrf", csrf).build().toString()
        val root = parseObject(transport.get(url))
        require(root["message"]?.jsonPrimitive?.content == "success" &&
            (root["result"] as? JsonObject)?.let { text(it, "id").isNotBlank() } == true) { LOGIN_MESSAGE }
    }

    private suspend fun csrfToken(): String {
        val home = transport.get(CampusUrls.LEARN_HOME)
        require(!Regex("sm2publicKey|login_timeout|name=[\"']i_user[\"']", RegexOption.IGNORE_CASE).containsMatchIn(home)) { LOGIN_MESSAGE }
        return Regex("(?:[?&]|&amp;)_csrf=([A-Za-z0-9_-]{8,256})").find(home)?.groupValues?.get(1)
            ?: error(LOGIN_MESSAGE)
    }

    suspend fun read(query: HomeworkQuery = HomeworkQuery(), progress: (String) -> Unit = {}): HomeworkSnapshot = coroutineScope {
        require(query.course.length <= 100) { "课程名称过长" }
        progress("正在连接网络学堂…")
        val csrf = csrfToken()
        fun url(path: String) = "${CampusUrls.LEARN}$path".toHttpUrl().newBuilder().addQueryParameter("_csrf", csrf).build().toString()
        val semester = parseObject(transport.get(url("/b/kc/zhjw_v_code_xnxq/getCurrentAndNextSemester")))
        require(semester["message"]?.jsonPrimitive?.content == "success") { "无法读取网络学堂当前学期" }
        val term = semester["result"] as? JsonObject ?: error("当前学期信息缺失")
        val semesterId = text(term, "id").also { require(it.isNotBlank() && it.length <= 100) { "当前学期编号无效" } }
        val semesterName = text(term, "xnxqmc").ifBlank { semesterId }.take(100)
        val courseUrl = CampusUrls.LEARN.toHttpUrl().newBuilder().addPathSegments("b/wlxt/kc/v_wlkc_xs_xkb_kcb_extend/student/loadCourseBySemesterId")
            .addPathSegment(semesterId).addPathSegment("zh").addQueryParameter("_csrf", csrf).build().toString()
        val courseData = parseObject(transport.get(courseUrl))
        require(courseData["message"]?.jsonPrimitive?.content == "success") { "无法读取当前学期课程" }
        val rawCourses = courseData["resultList"] as? JsonArray ?: error("学校返回的课程列表格式已变化")
        val courses = rawCourses.map { value ->
            val o = value as? JsonObject ?: error("课程资料无效")
            Course(text(o, "wlkcid"), decodeTitle(text(o, "kcm").ifBlank { text(o, "zywkcm") })).also {
                require(it.id.isNotBlank() && it.id.length <= 128 && it.name.isNotBlank() && it.name.length <= 300) { "课程资料不完整" }
            }
        }.distinctBy { it.id }.filter { query.course.isBlank() || it.name.contains(query.course.trim(), ignoreCase = true) }
        val warnings = mutableListOf<String>()
        if (courses.size > 60) warnings += "课程超过60门，仅读取前60门，请按课程名称查询"
        val statuses = HomeworkStatus.entries.filter(query.status::accepts)
        val selected = courses.take(60)
        // Bound concurrency. A failed course remains explicitly unknown; it never becomes an empty successful list.
        val parts = selected.flatMap { course -> statuses.map { status ->
            async {
                semaphore.withPermit {
                    progress("正在读取课程作业…")
                    try { readPart(course, status, csrf) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { Part(emptyList(), listOf("${course.name}的${status.label}作业未能完整读取，请重新登录或刷新")) }
                }
            }
        } }.awaitAll()
        currentCoroutineContext().ensureActive()
        warnings += parts.flatMap { it.warnings }
        val all = parts.flatMap { it.items }
        val duplicates = all.groupBy { it.key }
        if (duplicates.any { (_, rows) -> rows.map { it.status }.distinct().size > 1 }) warnings += "读取期间部分作业状态发生变化，请刷新核对"
        val items = duplicates.values.map { rows -> rows.maxBy { it.status.ordinal } }
            .sortedWith(compareBy<CourseHomework> { it.deadline ?: Instant.MAX }.thenBy { it.courseName }.thenBy { it.title })
        if (items.size > 5000) warnings += "作业超过5000条，清单有省略，请按课程查询"
        HomeworkSnapshot(semesterId, semesterName, Instant.now(), query, items.take(5000), warnings.isEmpty(),
            warnings.distinct(), selected.size, courses.size,
            runCatching { LocalDate.parse(text(term, "kssj").take(10)) }.getOrNull(),
            runCatching { LocalDate.parse(text(term, "jssj").take(10)) }.getOrNull())
    }

    private suspend fun readPart(course: Course, status: HomeworkStatus, csrf: String): Part {
        val suffix = when (status) {
            HomeworkStatus.UNSUBMITTED -> "zyListWj"
            HomeworkStatus.SUBMITTED -> "zyListYjwg"
            HomeworkStatus.GRADED -> "zyListYpg"
        }
        val url = "${CampusUrls.LEARN}/b/wlxt/kczy/zy/student/$suffix".toHttpUrl().newBuilder().addQueryParameter("_csrf", csrf).build().toString()
        val items = mutableListOf<CourseHomework>()
        val warnings = mutableListOf<String>()
        var offset = 0
        repeat(20) {
            currentCoroutineContext().ensureActive()
            val filters = buildJsonArray {
                add(buildJsonObject { put("name", "wlkcid"); put("value", course.id) })
                add(buildJsonObject { put("name", "iDisplayStart"); put("value", offset) })
                add(buildJsonObject { put("name", "iDisplayLength"); put("value", 200) })
            }
            val root = parseObject(transport.postForm(url, mapOf("aoData" to filters.toString())))
            require(root["result"]?.jsonPrimitive?.content == "success") { "作业查询失败" }
            val obj = root["object"] as? JsonObject ?: error("作业列表缺失")
            val array = obj["aaData"] as? JsonArray ?: error("作业列表格式已变化")
            require(array.size <= 5000) { "作业列表过长" }
            val page = array.map { parseItem(it as? JsonObject ?: error("作业格式无效"), course, status) }
            require(page.map { it.key }.distinct().size == page.size) { "作业列表中存在重复项" }
            require(page.none { next -> items.any { it.key == next.key } }) { "学校未正确返回下一页" }
            items += page
            val rawTotal = obj["iTotalDisplayRecords"] ?: obj["iTotalRecords"] ?: root["iTotalDisplayRecords"] ?: root["iTotalRecords"]
            val total = (rawTotal as? JsonPrimitive)?.intOrNull
            require(rawTotal == null || total != null && total >= items.size) { "作业分页数量异常" }
            if (total != null && items.size >= total || total == null && page.size < 200) return Part(items, warnings)
            if (page.isEmpty()) return Part(items, listOf("${course.name}的${status.label}作业缺少部分分页，请刷新"))
            if (total == null) return Part(items, listOf("${course.name}的${status.label}作业较多且未提供总数，清单可能不完整"))
            offset += page.size
        }
        warnings += "${course.name}的${status.label}作业达到分页上限，清单有省略"
        return Part(items, warnings)
    }

    private fun parseItem(o: JsonObject, course: Course, status: HomeworkStatus): CourseHomework {
        val id = text(o, "xszyid"); val title = text(o, "bt")
        require(id.isNotBlank() && id.length <= 128 && title.isNotBlank() && title.length <= 1500) { "作业编号或标题无效" }
        require(text(o, "wlkcid").let { it.isBlank() || it == course.id }) { "作业课程不匹配" }
        val mode = (o["zytjfs"] as? JsonPrimitive)?.intOrNull
        val completion = (o["zywcfs"] as? JsonPrimitive)?.intOrNull
        // A formatted companion can fill a missing/unparseable value, never override a valid primary time.
        fun time(key: String): Instant? = schoolTime(text(o, key)) ?: schoolTime(text(o, "${key}Str"))
        return CourseHomework(id, course.id, course.name, decodeTitle(title), status,
            time("jzsj"),
            if (status == HomeworkStatus.UNSUBMITTED) null else time("tjsj") ?: if (text(o, "sfsc") == "是") null else time("scsj"),
            time("bjjzsj"),
            when (text(o, "sfbj")) { "是" -> true; "否" -> false; else -> null },
            when (mode) { 2 -> true; 0 -> false; else -> null },
            when (completion) { 2 -> true; 1 -> false; else -> null })
    }

    companion object {
        const val LOGIN_MESSAGE = "请先在学校登录窗口打开网络学堂，完成认证后再读取作业"
        fun forAndroid(context: Context) = HomeworkClient(CampusHttpTransport(CookieManager.getInstance(), WebSettings.getDefaultUserAgent(context)))
        private fun text(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        private fun parseObject(raw: String): JsonObject = try { Json.parseToJsonElement(raw) as? JsonObject ?: error("格式错误") }
            catch (_: Exception) { error("网络学堂未返回有效数据，请重新登录") }
        internal fun schoolTime(raw: String): Instant? {
            if (raw.isBlank() || raw.length > 160) return null
            val value = raw.trim().replace('\u00a0', ' ').replace('：', ':').replace(Regex("\\s+"), " ")
            // Only whole, modern epoch values of these magnitudes are accepted. A bare date or zero
            // must never become an invented deadline, and fractional milliseconds are not rounded.
            val epoch = if (NUMERIC_TIME.matches(value)) runCatching { value.toBigDecimal().longValueExact() }.getOrNull() else null
            if (epoch != null) return when (epoch) {
                in 1_000_000_000_000L..9_999_999_999_999L -> Instant.ofEpochMilli(epoch)
                in 1_000_000_000L..9_999_999_999L -> Instant.ofEpochSecond(epoch)
                else -> null
            }
            runCatching { Instant.parse(value) }.getOrNull()?.let { return it }
            runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()?.let { return it }
            val normalized = value.replace(Regex("^(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s*"), "\$1-\$2-\$3 ")
            val match = SCHOOL_DATE_TIME.matchEntire(normalized) ?: return null
            return runCatching {
                val parts = match.groupValues
                val local = LocalDateTime.of(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(),
                    parts[4].toInt(), parts[5].toInt(), parts[6].ifEmpty { "0" }.toInt(),
                    parts[7].removePrefix(".").padEnd(9, '0').toInt())
                if (parts[8].isNotEmpty()) local.atOffset(ZoneOffset.of(parts[8].trim().uppercase())).toInstant()
                else local.atZone(CourseHomework.SCHOOL_ZONE).toInstant()
            }.getOrNull()
        }
        private val SCHOOL_DATE_TIME = Regex("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[T ](\\d{1,2}):(\\d{1,2})(?::(\\d{1,2})(\\.\\d{1,9})?)?(\\s*(?:[Zz]|[+-]\\d{2}:?\\d{2}))?$")
        private val NUMERIC_TIME = Regex("^[1-9]\\d{0,15}(?:\\.\\d{1,15})?(?:[eE][+-]?\\d{1,2})?$")
        private fun decodeTitle(raw: String): String = Regex("&(?:lt|gt|quot|apos|nbsp|amp|#(?:[0-9]+|[xX][0-9a-fA-F]+));").replace(raw) { match ->
            when (match.value) {
                "&lt;" -> "<"; "&gt;" -> ">"; "&quot;" -> "\""; "&apos;" -> "'"; "&nbsp;" -> " "; "&amp;" -> "&"
                else -> {
                    val number = match.value.removePrefix("&#").removeSuffix(";")
                    val code = if (number.startsWith("x", true)) number.drop(1).toIntOrNull(16) else number.toIntOrNull()
                    if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else match.value
                }
            }
        }
    }
}

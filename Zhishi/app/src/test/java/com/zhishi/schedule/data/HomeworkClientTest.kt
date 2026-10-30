package com.zhishi.schedule.data

import com.zhishi.schedule.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HomeworkClientTest {
    class Fixture(private val page: (String, Int) -> String = { _, _ -> """{"result":"success","object":{"aaData":[]}}""" }) : CampusTransport {
        val gets = mutableListOf<String>()
        val posts = mutableListOf<Pair<String, Map<String, String>>>()
        var home = """<a href="/f/wlxt/index/course/student/?foo=1&amp;_csrf=fixture-csrf-123456">课程</a>"""
        var courseJson = """{"message":"success","resultList":[{"wlkcid":"course-1","kcm":"概率论"}]}"""
        override suspend fun get(url: String): String {
            gets += url
            return when {
                url == CampusUrls.LEARN_HOME -> home
                url.contains("getCurrentAndNextSemester") -> """{"message":"success","result":{"id":"2026-2027-1","xnxqmc":"秋季学期","kssj":"2026-09-14","jssj":"2027-01-17"}}"""
                url.contains("loadCourseBySemesterId") -> courseJson
                else -> error("Unexpected request")
            }
        }
        override suspend fun postForm(url: String, fields: Map<String, String>): String {
            posts += url to fields
            val filters = Json.parseToJsonElement(fields.getValue("aoData")).jsonArray.map { it.jsonObject }
            check(filters.single { it["name"]!!.jsonPrimitive.content == "wlkcid" }["value"]!!.jsonPrimitive.content == "course-1")
            return page(url.toHttpUrl().pathSegments.last(), filters.single { it["name"]!!.jsonPrimitive.content == "iDisplayStart" }["value"]!!.jsonPrimitive.int)
        }
    }

    @Test fun `reads three school statuses and metadata without fetching answers attachments or grades`() = runBlocking {
        val fixture = Fixture { source, _ -> response(item(source)) }
        val report = HomeworkClient(fixture).read()
        assertTrue(report.complete)
        assertEquals(3, report.items.size)
        assertEquals(HomeworkStatus.entries.toSet(), report.items.map { it.status }.toSet())
        assertEquals(Instant.parse("2026-10-28T15:59:00Z"), report.items.first().deadline)
        assertEquals("概率论", report.items.first().courseName)
        assertEquals("作业 <1>", report.items.first().title)
        assertTrue(report.items.first().onlineSubmission == true)
        assertEquals(3, fixture.posts.size)
        assertTrue(fixture.posts.all { "_csrf=fixture-csrf-123456" in it.first })
        assertTrue((fixture.gets + fixture.posts.map { it.first }).none { Regex("/detail|viewCj|downloadFile|tjzy|/cj/").containsMatchIn(it) })
    }

    @Test fun `unsubmitted query only calls the required status and filters courses before reading`() = runBlocking {
        val fixture = Fixture { source, _ -> response(item(source)) }
        val result = HomeworkClient(fixture).read(HomeworkQuery(HomeworkFilter.UNSUBMITTED, "概率"))
        assertEquals(1, result.items.size)
        assertEquals(listOf("zyListWj"), fixture.posts.map { it.first.toHttpUrl().pathSegments.last() })
        val none = Fixture()
        assertTrue(HomeworkClient(none).read(HomeworkQuery(course = "不存在课程")).items.isEmpty())
        assertTrue(none.posts.isEmpty())
    }

    @Test fun `one failed status remains unknown instead of claiming the entire list is complete`() = runBlocking {
        val report = HomeworkClient(Fixture { source, _ -> if (source == "zyListWj") "<html>登录</html>" else response(item(source)) }).read()
        assertFalse(report.complete)
        assertEquals(2, report.items.size)
        assertTrue(report.warnings.any { "未提交" in it })
    }

    @Test fun `expired login stops before semester or course requests`() = runBlocking {
        val fixture = Fixture().apply { home = "<html>sm2publicKey</html>" }
        assertTrue(runCatching { HomeworkClient(fixture).read() }.isFailure)
        assertEquals(1, fixture.gets.size)
        assertTrue(fixture.posts.isEmpty())
    }

    @Test fun `pagination returns all rows and repeated first page is never accepted as complete`() = runBlocking {
        val fixture = Fixture { _, offset -> response(item("id-$offset"), total = 2) }
        val result = HomeworkClient(fixture).read(HomeworkQuery(HomeworkFilter.UNSUBMITTED))
        assertTrue(result.complete); assertEquals(2, result.items.size); assertEquals(2, fixture.posts.size)
        val repeated = HomeworkClient(Fixture { _, _ -> response(item("same"), total = 2) }).read(HomeworkQuery(HomeworkFilter.UNSUBMITTED))
        assertFalse(repeated.complete)
        assertTrue(repeated.warnings.isNotEmpty())
    }

    @Test fun `offline group homework and unknown timestamps retain uncertainty`() = runBlocking {
        val row = item("offline").replace("\"zytjfs\":2", "\"zytjfs\":0").replace("\"zywcfs\":1", "\"zywcfs\":2")
            .replace("2026-10-28 23:59:00", "未定")
        val homework = HomeworkClient(Fixture { _, _ -> response(row) }).read(HomeworkQuery(HomeworkFilter.UNSUBMITTED)).items.single()
        assertEquals(false, homework.onlineSubmission); assertEquals(true, homework.groupWork)
        assertNull(homework.deadline); assertFalse(homework.overdue(Instant.now()))
        assertTrue(homework.submissionNote.contains("线下"))
    }

    @Test fun `cancellation propagates rather than becoming partial success`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val client = HomeworkClient(CampusTransport { started.complete(Unit); awaitCancellation() })
        val job = launch { client.read(); fail("must not complete") }
        started.await(); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }

    @Test fun `missing data arrays and foreign course rows cannot become empty successful lists`() = runBlocking {
        listOf("""{"result":"success","object":{}}""", response(item("foreign").replace("course-1", "other-course"))).forEach { raw ->
            val report = HomeworkClient(Fixture { _, _ -> raw }).read(HomeworkQuery(HomeworkFilter.UNSUBMITTED))
            assertFalse(report.complete)
        }
    }

    @Test fun `explicit offset times and unzoned school times resolve to the same instant`() {
        assertEquals(HomeworkClient.schoolTime("2026-10-28 23:59:00"), HomeworkClient.schoolTime("2026-10-28T23:59:00+08:00"))
        assertNull(HomeworkClient.schoolTime("2026-10-28"))
    }

    @Test fun `numeric JSON date fields support epoch milliseconds and seconds in all three status lists`() = runBlocking {
        val deadline = Instant.parse("2026-10-28T15:59:00Z")
        val late = deadline.plusSeconds(86_400)
        val fixture = Fixture { source, _ ->
            val base = Json.parseToJsonElement(item(source)).jsonObject
            response(JsonObject(base + mapOf(
                "jzsj" to JsonPrimitive(deadline.toEpochMilli()),
                "bjjzsj" to JsonPrimitive(late.epochSecond))).toString())
        }
        val result = HomeworkClient(fixture).read()
        assertTrue(result.complete)
        assertEquals(3, result.items.size)
        assertTrue(result.items.all { it.deadline == deadline && it.lateDeadline == late })
        assertEquals(3, fixture.posts.size) // Parsing adds no detail/answer/attachment requests.
    }

    @Test fun `formatted companion is a fallback and cannot replace an already valid deadline`() = runBlocking {
        val deadline = Instant.parse("2026-10-28T15:59:00Z")
        val base = Json.parseToJsonElement(item("format")).jsonObject
        suspend fun read(value: JsonElement) = HomeworkClient(Fixture { _, _ -> response(JsonObject(base + mapOf(
            "jzsj" to value, "jzsjStr" to JsonPrimitive("2026/10/29 23:59:00"))).toString()) })
            .read(HomeworkQuery(HomeworkFilter.UNSUBMITTED)).items.single().deadline
        assertEquals(deadline, read(JsonPrimitive(deadline.toEpochMilli())))
        assertEquals(deadline.plusSeconds(86_400), read(JsonNull))
        assertEquals(deadline.plusSeconds(86_400), read(JsonPrimitive("unrecognized")))
    }

    @Test fun `common school display formats and explicit offsets preserve the exact deadline`() {
        val expected = Instant.parse("2026-10-28T15:59:00Z")
        listOf("2026/10/28 23:59:00", "2026-10-28 23:59", "2026年10月28日 23:59",
            "2026-10-28 23：59：00", " 2026-10-28  23:59:00 ",
            "2026-10-28T23:59:00+0800", "2026-10-28 23:59:00 +08:00",
            "2026-10-28T15:59:00Z", expected.toEpochMilli().toString(), expected.epochSecond.toString(),
            "${expected.toEpochMilli()}.0").forEach { raw ->
            assertEquals(raw, expected, HomeworkClient.schoolTime(raw))
        }
        assertEquals(expected, HomeworkClient.schoolTime("${expected.toEpochMilli()}e0"))
        assertEquals(Instant.ofEpochMilli(1_793_202_000_000), HomeworkClient.schoolTime("1.793202E12"))
    }

    @Test fun `single digit components fractional seconds and timezone offsets are parsed strictly`() {
        assertEquals(Instant.parse("2026-01-02T00:09:10.123456789Z"), HomeworkClient.schoolTime("2026/1/2 8:9:10.123456789+0800"))
        assertEquals(Instant.parse("2026-10-29T04:59:00Z"), HomeworkClient.schoolTime("2026-10-28 23:59:00-0500"))
    }

    @Test fun `missing date only invalid and fractional epoch values remain unknown rather than guessed`() {
        listOf("", "null", "未定", "无截止时间", "2026-10-28", "20261028", "0", "-1",
            "2026-02-30 23:59", "2026-10-28 24:59:00", "2026-10-28 23:60:00",
            "2026-10-28 23:59:00+2500", "01/02/2026 23:59", "1793203140000.5",
            "999999999999999999999999", "1e2147483647").forEach { assertNull(it, HomeworkClient.schoolTime(it)) }
    }

    @Test fun `unsubmitted work never interprets favorite timestamp as submission time`() = runBlocking {
        val raw = item("fav").replace("\"scsj\":null", "\"scsj\":\"2026-10-28 12:00:00\",\"sfsc\":\"是\"")
        val report = HomeworkClient(Fixture { _, _ -> response(raw) }).read()
        assertTrue(report.items.all { it.submittedAt == null })
    }

    companion object {
        fun item(id: String) = """{"xszyid":"$id","wlkcid":"course-1","bt":"作业 &lt;1&gt;","jzsj":"2026-10-28 23:59:00","scsj":null,"bjjzsj":"2026-10-29 23:59:00","zytjfs":2,"zywcfs":1,"sfbj":"否"}"""
        fun response(row: String, total: Int? = null) = """{"result":"success","object":{"aaData":[$row]${total?.let { ",\"iTotalDisplayRecords\":$it" } ?: ""}}}"""
    }
}

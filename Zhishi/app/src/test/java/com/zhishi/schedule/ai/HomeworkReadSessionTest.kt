package com.zhishi.schedule.ai

import com.zhishi.schedule.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HomeworkReadSessionTest {
    private val now = Instant.parse("2026-10-28T04:00:00Z")
    private val item = CourseHomework("1", "c1", "概率论", "第一次作业", HomeworkStatus.UNSUBMITTED, Instant.parse("2026-10-28T15:59:00Z"), onlineSubmission = true)
    private fun snapshot(query: HomeworkQuery, rows: List<CourseHomework>, complete: Boolean = true) = HomeworkSnapshot("2026-1", "秋季", now, query, rows, complete,
        semesterStart = LocalDate.of(2026, 9, 14), semesterEnd = LocalDate.of(2027, 1, 17))
    private fun request(text: String) = Json.parseToJsonElement(text).jsonArray
    private fun result(data: JsonObject) = data["homework_results"]!!.jsonArray.single().jsonObject

    @Test fun `local unnecessary label is exposed separately from unchanged school submission state`() = runBlocking {
        val session = HomeworkReadSession { query -> snapshot(query, listOf(item.copy(priorityMark = HomeworkPriority.NOT_REQUIRED))) }
        val row = result(session.read(request("[{}]")))["items"]!!.jsonArray.single().jsonObject
        assertEquals("unsubmitted", row["status"]!!.jsonPrimitive.content)
        assertEquals("not_required", row["local_category"]!!.jsonPrimitive.content)
        assertTrue(row["user_says_submission_unnecessary"]!!.jsonPrimitive.boolean)
    }

    @Test fun `date query filters school metadata and caches scope for pagination without uploading entire semester`() = runBlocking {
        var calls = 0
        val session = HomeworkReadSession { query ->
            calls++; snapshot(query, listOf(item, item.copy(id = "2", deadline = item.deadline!!.plusSeconds(86400))))
        }
        val r = result(session.read(request("""[{"status":"unsubmitted","start_date":"2026-10-28","end_date":"2026-10-28"}]""")))
        assertEquals(1, r["items"]!!.jsonArray.size)
        assertTrue(r["complete"]!!.jsonPrimitive.boolean)
        session.read(request("""[{"status":"unsubmitted","start_date":"2026-10-29","end_date":"2026-10-29"}]"""))
        assertEquals(1, calls)
    }

    @Test fun `unknown deadlines remain visible and cannot establish that nothing is due`() = runBlocking {
        val session = HomeworkReadSession { query -> snapshot(query, listOf(item.copy(deadline = null))) }
        val r = result(session.read(request("""[{"start_date":"2026-10-28","end_date":"2026-10-28"}]""")))
        assertEquals(1, r["unknown_deadline_count"]!!.jsonPrimitive.int)
        assertFalse(r["complete"]!!.jsonPrimitive.boolean)
        assertEquals(1, r["items"]!!.jsonArray.size)
    }

    @Test fun `partial data and paginated replies keep explicit completeness and next offset`() = runBlocking {
        val session = HomeworkReadSession { query -> snapshot(query, listOf(item, item.copy(id = "2")), false) }
        val r = result(session.read(request("""[{"limit":1}]""")))
        assertFalse(r["complete"]!!.jsonPrimitive.boolean)
        assertFalse(r["list_complete"]!!.jsonPrimitive.boolean)
        assertEquals(1, r["next_offset"]!!.jsonPrimitive.int)
        assertTrue(session.warnings.isNotEmpty())
    }

    @Test fun `invalid requests fail before school access and null reader is explicit`() = runBlocking {
        var calls = 0
        val session = HomeworkReadSession { query -> calls++; snapshot(query, listOf(item)) }
        listOf("""[{"status":"complete"}]""", """[{"limit":1000}]""", """[{"submit":true}]""", """[1]""", """[{"start_date":"2026-10-28"}]""").forEach {
            assertTrue(result(session.read(request(it))).containsKey("error"))
        }
        assertEquals(0, calls)
        assertTrue(result(HomeworkReadSession(null).read(request("[{}]"))).containsKey("error"))
    }

    @Test fun `dates outside current semester do not imply no homework`() = runBlocking {
        val session = HomeworkReadSession { query -> snapshot(query, emptyList()) }
        val r = result(session.read(request("""[{"start_date":"2027-03-01","end_date":"2027-03-10"}]""")))
        assertTrue(r.containsKey("error")); assertFalse(r["complete"]!!.jsonPrimitive.boolean)
    }

    @Test fun `cancelling homework lookup propagates cancellation`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val session = HomeworkReadSession { started.complete(Unit); awaitCancellation() }
        val job = launch { session.read(request("[{}]")); fail("must not complete") }
        started.await(); job.cancelAndJoin(); assertTrue(job.isCancelled)
    }
}

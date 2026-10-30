package com.zhishi.schedule.data

import com.zhishi.schedule.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeworkSubmissionClientTest {
    class Fixture : CampusTransport {
        var status = HomeworkStatus.UNSUBMITTED
        var posts = 0
        var response = """{"result":"success"}"""
        var uncertain = false
        var lastPayload: HomeworkSubmissionPayload? = null
        var beforePost: suspend () -> Unit = {}
        private val metadata = HomeworkClientTest.Fixture { source, _ ->
            val wanted = when (status) {
                HomeworkStatus.UNSUBMITTED -> "zyListWj"
                HomeworkStatus.SUBMITTED -> "zyListYjwg"
                HomeworkStatus.GRADED -> "zyListYpg"
            }
            if (source == wanted) HomeworkClientTest.response(HomeworkClientTest.item("assignment"))
            else """{"result":"success","object":{"aaData":[]}}"""
        }
        override suspend fun get(url: String) = metadata.get(url)
        override suspend fun postForm(url: String, fields: Map<String, String>) = metadata.postForm(url, fields)
        override suspend fun submitHomework(url: String, payload: HomeworkSubmissionPayload): String {
            assertTrue(url.startsWith("${CampusUrls.LEARN}/b/wlxt/kczy/zy/student/tjzy?_csrf="))
            posts++; lastPayload = payload; beforePost()
            if (uncertain) throw HomeworkSubmissionUncertain()
            if (response.contains("success")) status = HomeworkStatus.SUBMITTED
            return response
        }
    }

    @Test fun `submission revalidates assignment and escapes literal text before sending`() = runBlocking {
        val fixture = Fixture(); val client = HomeworkClient(fixture)
        val item = client.read().items.single()
        client.submit(item, "中文 <script>\n第二行 &", null, false)
        assertEquals(1, fixture.posts)
        assertEquals("assignment", fixture.lastPayload!!.studentHomeworkId)
        assertEquals("<p>中文 &lt;script&gt;<br>第二行 &amp;</p>", fixture.lastPayload!!.contentHtml)
        assertNull(fixture.lastPayload!!.attachment)
    }

    @Test fun `changed status before write prevents overwriting a newer submission`() = runBlocking {
        val fixture = Fixture(); val client = HomeworkClient(fixture)
        val item = client.read().items.single()
        fixture.status = HomeworkStatus.SUBMITTED
        assertTrue(runCatching { client.submit(item, "new", null, false) }.isFailure)
        assertEquals(0, fixture.posts)
    }

    @Test fun `resubmission requires explicit replacement acceptance`() = runBlocking {
        val fixture = Fixture().apply { status = HomeworkStatus.SUBMITTED }; val client = HomeworkClient(fixture)
        val item = client.read().items.single()
        assertTrue(runCatching { client.submit(item, "new", null, false) }.isFailure)
        assertEquals(0, fixture.posts)
        client.submit(item, "new", null, true)
        assertEquals(1, fixture.posts)
    }

    @Test fun `offline and graded work cannot reach the submit endpoint`() = runBlocking {
        val fixture = Fixture(); val client = HomeworkClient(fixture)
        val item = client.read().items.single()
        listOf(item.copy(onlineSubmission = false), item.copy(status = HomeworkStatus.GRADED)).forEach {
            assertTrue(runCatching { client.submit(it, "content", null, true) }.isFailure)
        }
        assertEquals(0, fixture.posts)
    }

    @Test fun `school rejection is distinct from missing or invalid success response`() = runBlocking {
        val fixture = Fixture(); val client = HomeworkClient(fixture)
        val item = client.read().items.single()
        fixture.response = """{"result":"failed"}"""
        assertTrue(runCatching { client.submit(item, "content", null, false) }.exceptionOrNull() is HomeworkSubmissionRejected)
        fixture.response = "<html>login timeout</html>"
        assertTrue(runCatching { client.submit(item, "content", null, false) }.exceptionOrNull() is HomeworkSubmissionUncertain)
        assertEquals(2, fixture.posts)
    }
}

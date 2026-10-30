package com.zhishi.schedule.data

import com.zhishi.schedule.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeworkPriorityStoreTest {
    private val now = Instant.parse("2026-10-29T08:00:00Z")
    private val item = CourseHomework("student-homework-id", "course", "课程", "作业", HomeworkStatus.UNSUBMITTED, deadline = now.plusSeconds(86400))
    private fun snapshot(row: CourseHomework = item, term: String = "2026-1") = HomeworkSnapshot(term, "秋季", now, HomeworkQuery(), listOf(row), true)
    private fun store() = HomeworkPriorityStore(RuntimeEnvironment.getApplication())

    @Test fun `manual choice survives a new store and fresh server metadata without changing school status`() {
        store().save("2026-1", item, HomeworkPriority.NOT_REQUIRED)
        val refreshed = store().decorate(snapshot()).items.single()
        assertEquals(HomeworkPriority.NOT_REQUIRED, refreshed.priority(now))
        assertEquals(HomeworkStatus.UNSUBMITTED, refreshed.status)
        assertEquals(item.deadline, refreshed.deadline)
    }
    @Test fun `labels are isolated by semester course and student homework identity`() {
        store().save("2026-1", item, HomeworkPriority.NOT_REQUIRED)
        listOf(snapshot(term = "2027-1"), snapshot(item.copy(courseId = "other-course")),
            snapshot(item.copy(id = "other-student-homework-id"))).forEach {
            assertNull(store().decorate(it).items.single().priorityMark)
        }
    }
    @Test fun `return to normal clears manual urgency and enables later automatic upgrade`() {
        store().save("2026-1", item, HomeworkPriority.URGENT)
        store().save("2026-1", item, HomeworkPriority.NORMAL)
        val restored = store().decorate(snapshot()).items.single()
        assertNull(restored.priorityMark)
        assertEquals(HomeworkPriority.URGENT, restored.priority(now))
        assertEquals(HomeworkPriority.NORMAL, restored.priority(now.minusSeconds(10 * 86400)))
    }
}

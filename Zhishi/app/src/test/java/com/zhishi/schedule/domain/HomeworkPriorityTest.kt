package com.zhishi.schedule.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HomeworkPriorityTest {
    private val now = Instant.parse("2026-10-29T08:00:00Z")
    private val item = CourseHomework("student-item", "course", "课程", "作业", HomeworkStatus.UNSUBMITTED)

    @Test fun `exact three day boundary is inclusive and crosses without another school read`() {
        val due = item.copy(deadline = now.plusSeconds(3 * 86400))
        assertEquals(HomeworkPriority.NORMAL, due.priority(now.minusSeconds(1)))
        assertEquals(HomeworkPriority.URGENT, due.priority(now))
        assertEquals(HomeworkPriority.URGENT, due.priority(now.plusSeconds(1)))
    }
    @Test fun `unnecessary overrides automatic urgency including overdue assignments`() {
        val due = item.copy(deadline = now.minusSeconds(86400), priorityMark = HomeworkPriority.NOT_REQUIRED)
        assertEquals(HomeworkPriority.NOT_REQUIRED, due.priority(now))
        assertFalse(due.automaticallyUrgent(now))
        assertEquals(HomeworkStatus.UNSUBMITTED, due.status)
    }
    @Test fun `manual urgent works for unknown and far away deadlines`() {
        assertEquals(HomeworkPriority.URGENT, item.copy(priorityMark = HomeworkPriority.URGENT).priority(now))
        assertEquals(HomeworkPriority.URGENT, item.copy(deadline = now.plusSeconds(10 * 86400), priorityMark = HomeworkPriority.URGENT).priority(now))
    }
    @Test fun `normal remains subject to urgency and overdue required work stays urgent`() {
        assertEquals(HomeworkPriority.NORMAL, item.copy(priorityMark = HomeworkPriority.NORMAL).priority(now))
        assertEquals(HomeworkPriority.URGENT, item.copy(deadline = now.minusSeconds(1), priorityMark = HomeworkPriority.NORMAL).priority(now))
    }
    @Test fun `unknown time is not guessed and an explicit late deadline may be used`() {
        assertEquals(HomeworkPriority.NORMAL, item.priority(now))
        assertFalse(item.automaticallyUrgent(now))
        assertEquals(HomeworkPriority.URGENT, item.copy(lateDeadline = now.plusSeconds(86400)).priority(now))
    }
    @Test fun `submitted or graded assignments leave the unsubmitted categories`() {
        HomeworkStatus.entries.filter { it != HomeworkStatus.UNSUBMITTED }.forEach { status ->
            val row = item.copy(status = status, deadline = now.minusSeconds(1), priorityMark = HomeworkPriority.URGENT)
            assertNull(row.priority(now)); assertFalse(row.automaticallyUrgent(now))
        }
    }
}

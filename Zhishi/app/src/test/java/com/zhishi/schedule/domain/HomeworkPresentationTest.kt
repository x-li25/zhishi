package com.zhishi.schedule.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HomeworkPresentationTest {
    private val now = Instant.parse("2026-10-30T08:00:00Z")
    private fun item(id: String, course: String, name: String, status: HomeworkStatus = HomeworkStatus.SUBMITTED) =
        CourseHomework(id, course, name, "作业$id", status, now.plusSeconds(86400))

    @Test fun `course groups keep matching identities together and preserve every assignment`() {
        val rows = listOf(item("1", "b", "B"), item("2", "a", "A"), item("3", "b", "B", HomeworkStatus.GRADED))
        val groups = groupHomeworkByCourse(rows)
        assertEquals(listOf("a", "b"), groups.map { it.courseId })
        assertEquals(listOf("1", "3"), groups.last().items.map { it.id })
        assertEquals(rows.toSet(), groups.flatMap { it.items }.toSet())
    }
    @Test fun `same display name does not merge different courses and an empty list has no groups`() {
        val groups = groupHomeworkByCourse(listOf(item("1", "a", "同名课程"), item("2", "b", "同名课程")))
        assertEquals(2, groups.size)
        assertEquals(listOf("a", "b"), groups.map { it.courseId })
        assertTrue(groupHomeworkByCourse(emptyList()).isEmpty())
    }
    @Test fun `remaining days include hours so more than three days cannot look urgent`() {
        assertEquals("还剩3天", homeworkDeadlineRemaining(now.plusSeconds(3 * 86400), now))
        assertEquals("还剩3天2小时", homeworkDeadlineRemaining(now.plusSeconds(3 * 86400 + 7200), now))
        val due = item("1", "a", "A", HomeworkStatus.UNSUBMITTED).copy(deadline = now.plusSeconds(3 * 86400 + 60))
        assertEquals("还剩3天1分钟", homeworkDeadlineRemaining(due.deadline, now))
        assertEquals(HomeworkPriority.NORMAL, due.priority(now))
        assertEquals(HomeworkPriority.URGENT, due.priority(now.plusSeconds(60)))
    }
    @Test fun `subday and subminute countdown never show zero days or negative values`() {
        assertEquals("还剩23小时59分钟", homeworkDeadlineRemaining(now.plusSeconds(86340), now))
        assertEquals("还剩1分钟", homeworkDeadlineRemaining(now.plusSeconds(60), now))
        assertEquals("还剩不足1分钟", homeworkDeadlineRemaining(now.plusNanos(1), now))
        assertEquals("还剩不足1分钟", homeworkDeadlineRemaining(now.plusSeconds(59), now))
    }
    @Test fun `past deadline text does not imply that submitted homework was late`() {
        assertEquals("已到截止时间", homeworkDeadlineRemaining(now, now))
        assertEquals("已截止不足1分钟", homeworkDeadlineRemaining(now.minusNanos(1), now))
        assertEquals("已截止2天", homeworkDeadlineRemaining(now.minusSeconds(2 * 86400), now))
        assertFalse(homeworkDeadlineRemaining(now.minusSeconds(86400), now)!!.contains("逾期"))
    }
    @Test fun `unknown deadline has no made up countdown and offsets are the same instant`() {
        assertNull(homeworkDeadlineRemaining(null, now))
        assertEquals("已到截止时间", homeworkDeadlineRemaining(Instant.parse("2026-10-30T16:00:00+08:00"), now))
    }
}

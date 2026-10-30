package com.zhishi.schedule.ui.screens

import com.zhishi.schedule.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HomeworkBadgeTest {
    private val now = Instant.parse("2026-10-29T08:00:00Z")
    private fun snapshot() = HomeworkSnapshot("semester", "term", now, HomeworkQuery(), listOf(
        CourseHomework("1", "c", "course", "a", HomeworkStatus.UNSUBMITTED),
        CourseHomework("2", "c", "course", "b", HomeworkStatus.SUBMITTED),
        CourseHomework("3", "c", "course", "c", HomeworkStatus.GRADED),
        CourseHomework("4", "c", "course", "d", HomeworkStatus.UNSUBMITTED)), complete = true)

    @Test fun `badge counts only platform unsubmitted status in a complete fresh read`() {
        assertEquals(2, HomeworkState(snapshot = snapshot()).unsubmittedCount(now))
        assertEquals(0, HomeworkState(snapshot = snapshot().copy(items = emptyList())).unsubmittedCount(now))
    }
    @Test fun `unknown partial loading and failed reads never show an outstanding count`() {
        assertNull(HomeworkState().unsubmittedCount(now))
        assertNull(HomeworkState(snapshot = snapshot().copy(complete = false)).unsubmittedCount(now))
        assertNull(HomeworkState(snapshot = snapshot(), loading = true).unsubmittedCount(now))
        assertNull(HomeworkState(snapshot = snapshot(), error = "offline").unsubmittedCount(now))
    }
    @Test fun `stale and future dated snapshots are not shown as current badge counts`() {
        assertNull(HomeworkState(snapshot = snapshot()).unsubmittedCount(now.plusSeconds(901)))
        assertNull(HomeworkState(snapshot = snapshot()).unsubmittedCount(now.minusSeconds(1)))
    }
    @Test fun `local unnecessary marker is excluded from action badge but leaves school status intact`() {
        val marked = snapshot().let { it.copy(items = it.items.map { row -> if (row.id == "1") row.copy(priorityMark = HomeworkPriority.NOT_REQUIRED) else row }) }
        assertEquals(1, HomeworkState(snapshot = marked).unsubmittedCount(now))
        assertEquals(2, marked.items.count { it.status == HomeworkStatus.UNSUBMITTED })
    }
}

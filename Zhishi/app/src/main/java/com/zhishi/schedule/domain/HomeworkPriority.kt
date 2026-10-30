package com.zhishi.schedule.domain

import java.time.Duration
import java.time.Instant

enum class HomeworkPriority(val label: String) {
    URGENT("紧急"), NORMAL("一般"), NOT_REQUIRED("无需提交")
}

/** Local planning categories never change the school's submission status. */
fun CourseHomework.priority(now: Instant = Instant.now()): HomeworkPriority? {
    if (status != HomeworkStatus.UNSUBMITTED) return null
    if (priorityMark == HomeworkPriority.NOT_REQUIRED) return HomeworkPriority.NOT_REQUIRED
    if (priorityMark == HomeworkPriority.URGENT || automaticallyUrgent(now)) return HomeworkPriority.URGENT
    return HomeworkPriority.NORMAL
}

fun CourseHomework.automaticallyUrgent(now: Instant = Instant.now()): Boolean {
    if (status != HomeworkStatus.UNSUBMITTED || priorityMark == HomeworkPriority.NOT_REQUIRED) return false
    val due = deadline ?: lateDeadline ?: return false
    // Inclusive 72 hours, and overdue required work remains urgent until submitted or marked unnecessary.
    return Duration.between(now, due) <= Duration.ofDays(3)
}

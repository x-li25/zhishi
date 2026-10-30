package com.zhishi.schedule.domain

import java.text.Collator
import java.time.Duration
import java.time.Instant
import java.util.Locale

data class HomeworkCourseGroup(val courseId: String, val name: String, val items: List<CourseHomework>)

/** Group the already filtered list by identity; different courses with the same name stay separate. */
fun groupHomeworkByCourse(rows: List<CourseHomework>): List<HomeworkCourseGroup> {
    val collator = Collator.getInstance(Locale.CHINA)
    return rows.groupBy { it.courseId }.map { (id, items) -> HomeworkCourseGroup(id, items.first().courseName, items) }
        .sortedWith { a, b -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.courseId.compareTo(b.courseId) }
}

/** Elapsed deadline time is independent of whether the homework was submitted on time. */
fun homeworkDeadlineRemaining(deadline: Instant?, now: Instant): String? {
    if (deadline == null) return null
    if (deadline == now) return "已到截止时间"
    val future = deadline > now
    val duration = if (future) Duration.between(now, deadline) else Duration.between(deadline, now)
    val minutes = duration.toMinutes()
    val days = minutes / 1440
    val hours = minutes / 60 % 24
    val rest = minutes % 60
    val amount = when {
        days > 0 -> "${days}天" + when {
            hours > 0 -> "${hours}小时"
            rest > 0 -> "${rest}分钟"
            else -> ""
        }
        hours > 0 -> "${hours}小时" + if (rest > 0) "${rest}分钟" else ""
        minutes > 0 -> "${minutes}分钟"
        else -> "不足1分钟"
    }
    return (if (future) "还剩" else "已截止") + amount
}

package com.zhishi.schedule.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class HomeworkStatus(val label: String) {
    UNSUBMITTED("未提交"), SUBMITTED("已提交，待批改"), GRADED("已批改")
}

enum class HomeworkFilter { ALL, UNSUBMITTED, SUBMITTED, GRADED;
    fun accepts(status: HomeworkStatus) = when (this) {
        ALL -> true
        UNSUBMITTED -> status == HomeworkStatus.UNSUBMITTED
        SUBMITTED -> status != HomeworkStatus.UNSUBMITTED
        GRADED -> status == HomeworkStatus.GRADED
    }
}

data class HomeworkQuery(val status: HomeworkFilter = HomeworkFilter.ALL, val course: String = "")

data class CourseHomework(
    val id: String, val courseId: String, val courseName: String, val title: String,
    val status: HomeworkStatus,
    val deadline: Instant? = null, val submittedAt: Instant? = null,
    val lateDeadline: Instant? = null, val lateSubmission: Boolean? = null,
    val onlineSubmission: Boolean? = null, val groupWork: Boolean? = null,
    val priorityMark: HomeworkPriority? = null
) {
    val key: String get() = "$courseId/$id"
    fun overdue(now: Instant) = status == HomeworkStatus.UNSUBMITTED && deadline?.let { it < now } == true
    fun dueDate(): LocalDate? = deadline?.atZone(SCHOOL_ZONE)?.toLocalDate()
    val submissionNote: String get() = when (onlineSubmission) {
        false -> "线下提交，请自行核实完成情况"
        null -> "提交方式未知"
        true -> if (groupWork == true) "小组作业，显示平台记录的提交状态" else "网络学堂提交"
    }
    companion object { val SCHOOL_ZONE: ZoneId = ZoneId.of("Asia/Shanghai") }
}

data class HomeworkSnapshot(
    val semesterId: String, val semesterName: String, val fetchedAt: Instant,
    val query: HomeworkQuery, val items: List<CourseHomework>,
    val complete: Boolean, val warnings: List<String> = emptyList(),
    val coursesRead: Int = 0, val coursesTotal: Int = 0,
    val semesterStart: LocalDate? = null, val semesterEnd: LocalDate? = null
)

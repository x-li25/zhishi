package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhishi.schedule.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
internal fun AssignmentList(state: HomeworkState, showInfo: Boolean, now: Instant, onOpenDetail: (CourseHomework) -> Unit,
    onSetPriority: (CourseHomework, HomeworkPriority) -> Unit, header: @Composable () -> Unit) {
    val report = state.snapshot
    val rows = remember(report, state.filter, state.priorityFilter, state.search, now) { report?.items.orEmpty().filter { state.filter.accepts(it.status) &&
        (state.filter != HomeworkFilter.UNSUBMITTED || it.priority(now) == state.priorityFilter) &&
        (state.search.isBlank() || it.title.contains(state.search, true) || it.courseName.contains(state.search, true)) } }
    val grouped = state.filter == HomeworkFilter.SUBMITTED || state.filter == HomeworkFilter.GRADED
    val courses = remember(rows, grouped) { if (grouped) groupHomeworkByCourse(rows) else emptyList() }
    var collapsedCourses by rememberSaveable(state.filter, state.search) { mutableStateOf<List<String>>(emptyList()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "filters") { header() }
        state.error?.let { error -> item { ReadNotice(error, error = true) } }
        if (showInfo) item {
            ReadNotice((report?.semesterName?.let { "$it\n" } ?: "") +
                "下拉读取学校最新记录，点选作业查看详情或核对提交。未提交不代表尚未完成；线下、小组和补交要求请另行核实。旧清单仅供参考。")
        }
        if (report == null && !state.loading) item {
            ReadNotice("还没有读取到作业，请下拉刷新。需要重新验证时，可从右上角菜单进入学校登录。")
        }
        if (report != null) {
            item {
                Text(if (state.loading) "正在刷新…" else (if (report.complete) "${rows.size}份 · ${report.coursesRead}门课程" else "部分课程未读取") +
                    " · ${homeworkTime(report.fetchedAt)}更新 · 下拉刷新",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (report.complete) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
            if (showInfo && report.warnings.isNotEmpty()) item {
                ReadNotice(report.warnings.take(6).joinToString("\n") + if (report.warnings.size > 6) "\n另有 ${report.warnings.size - 6} 项未完整读取" else "", error = true)
            }
            if (rows.isEmpty()) item {
                ReadNotice(if (state.search.isNotBlank()) "没有匹配的作业，可以换个关键词或清空搜索。" else if (report.complete)
                    (if (state.filter == HomeworkFilter.UNSUBMITTED) "${state.priorityFilter.label}栏暂时没有作业，可切换其他分类。" else "当前筛选没有作业记录。") + "线下和未发布的任务仍需自行核实。" else "当前筛选没有已读记录，仍有课程状态未知。")
            }
        }
        if (grouped) {
            courses.forEach { course ->
                val expanded = course.courseId !in collapsedCourses
                item(key = "course:${course.courseId}") {
                    Surface(onClick = {
                        collapsedCourses = if (expanded) collapsedCourses + course.courseId else collapsedCourses - course.courseId
                    }, modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "已展开" else "已收起" },
                        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(course.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("${course.items.size} 份", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                if (expanded) items(course.items, key = { "homework:${it.key}" }) { homework ->
                    AssignmentCard(homework, state.marking, now, onOpenDetail, onSetPriority, showCourse = false)
                }
            }
        } else items(rows, key = { "homework:${it.key}" }) { homework ->
            AssignmentCard(homework, state.marking, now, onOpenDetail, onSetPriority, compact = state.filter == HomeworkFilter.UNSUBMITTED)
        }
    }
}

@Composable
private fun AssignmentCard(homework: CourseHomework, marking: Boolean, now: Instant,
    onOpenDetail: (CourseHomework) -> Unit, onSetPriority: (CourseHomework, HomeworkPriority) -> Unit, showCourse: Boolean = true, compact: Boolean = false) {
    val priority = homework.priority(now)
    Card(Modifier.fillMaxWidth().clickable(onClickLabel = "查看作业详情") { onOpenDetail(homework) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showCourse) Text(homework.courseName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(homework.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (priority != null) HomeworkPriorityMenu(homework, marking, onSetPriority)
            }
            if (priority != null && !compact) HomeworkPriorityBadge(priority)
            if (!compact || priority == HomeworkPriority.NOT_REQUIRED || homework.overdue(now)) Text("学校：" + homework.status.label + if (homework.overdue(now)) " · 已过原截止时间" else "",
                style = MaterialTheme.typography.labelMedium,
                color = if (homework.overdue(now) && priority != HomeworkPriority.NOT_REQUIRED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("截止：${homework.deadline?.let { "${homeworkTime(it)} · ${homeworkDeadlineRemaining(it, now)}" } ?: "暂未获取，请核对学校记录"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            homework.lateDeadline?.let { Text("补交截止：${homeworkTime(it)} · ${homeworkDeadlineRemaining(it, now)}", style = MaterialTheme.typography.bodySmall) }
            if (homework.onlineSubmission != true || homework.groupWork == true) Text(homework.submissionNote, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun PendingFileList(state: HomeworkState, onRestore: (PendingHomeworkFile) -> Unit,
    onRemove: (String) -> Unit, onChooseAssignment: () -> Unit, search: String = "", header: (@Composable () -> Unit)? = null) {
    val files = remember(state.pendingFiles, search) { state.pendingFiles.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (header != null) item(key = "search") { header() }
        if (state.receivingSharedFiles) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item {
            Text(if (state.pendingFiles.isEmpty()) "还没有待交文件" else "已保存在本机 · ${state.pendingFiles.size} 个文件",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("从其他软件分享文件到知时，即可保存在这里。选择作业后按分享顺序带入，也可在提交页更换附件。",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onChooseAssignment) { Text("去选择作业") }
        }
        if (search.isNotBlank() && files.isEmpty()) item { ReadNotice("没有匹配的文件，可以换个关键词或清空搜索。") }
        items(files, key = { it.id }) { entry ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleSmall)
                            Text("${entry.size / 1024} KB · " + if (entry.awaitingResult) "提交结果待核实" else "尚未上传学校", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (entry.awaitingResult) {
                        Text("上次用于“${entry.assignment.orEmpty()}”，提交结果待核实", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton({ onRestore(entry) }) { Text("核实后恢复待提交") }
                    }
                    TextButton({ onRemove(entry.id) }) { Text("移除本机副本") }
                }
            }
        }
    }
}

@Composable
private fun ReadNotice(text: String, error: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.medium,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun homeworkTime(time: Instant) = time.atZone(CourseHomework.SCHOOL_ZONE).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

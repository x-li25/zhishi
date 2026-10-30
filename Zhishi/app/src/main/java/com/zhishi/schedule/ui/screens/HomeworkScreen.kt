package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhishi.schedule.domain.*
import com.zhishi.schedule.data.CampusLoginTarget
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HomeworkCard(state: HomeworkState, onOpen: () -> Unit, date: LocalDate? = null) {
    val snapshot = state.snapshot
    val due = snapshot?.items.orEmpty().filter { date != null && it.dueDate() == date && it.status == HomeworkStatus.UNSUBMITTED }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("课程作业", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("查看 ›", color = MaterialTheme.colorScheme.primary)
            }
            Text(when {
                state.loading -> "正在读取网络学堂…"
                state.error != null -> "作业刷新未完成，请打开清单登录学校或下拉重试"
                snapshot == null -> "登录网络学堂，查看截止时间和提交状态"
                !snapshot.complete -> "部分作业状态未知，请打开后刷新核对"
                date == null -> "${snapshot.semesterName} · ${snapshot.items.size} 条作业"
                due.isNotEmpty() -> "$date 有 ${due.size} 条截止作业，平台显示未提交"
                else -> "$date 没有查到当日截止且未提交的作业"
            }, style = MaterialTheme.typography.bodyMedium)
            if (snapshot != null) {
                if (!snapshot.complete || state.error != null) Text("清单未完整或未更新，请打开后刷新核对", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (snapshot.items.any { it.deadline == null && it.status == HomeworkStatus.UNSUBMITTED }) Text("另有未提交作业截止时间未知，请查看完整清单", style = MaterialTheme.typography.bodySmall)
                Text("读取于 ${schoolTime(snapshot.fetchedAt)} · 提交状态以学校最新记录为准", style = MaterialTheme.typography.bodySmall)
            }
            if (state.receivingSharedFiles) Text("正在保存分享的附件…", style = MaterialTheme.typography.bodySmall)
            if (state.pendingFiles.isNotEmpty()) Text("待提交附件 ${state.pendingFiles.size} 个 · 下次选择作业时自动带入", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkDialogs(state: HomeworkState, model: HomeworkViewModel, graduate: Boolean = false,
    onLoginSuccess: () -> Unit = { model.closeLogin(); model.open() },
    onServiceVerified: (CampusLoginTarget) -> Unit = { if (it == CampusLoginTarget.HOMEWORK) model.refreshAfterLogin(false, false) }) {
    if (state.loginVisible) CampusLoginDialog(graduate, model::closeLogin, onLoginSuccess, learn = true, onServiceVerified = onServiceVerified)
    if (!state.visible) return
    var detail by remember { mutableStateOf<CourseHomework?>(null) }
    var restorePending by remember { mutableStateOf<PendingHomeworkFile?>(null) }
    HomeworkContent(state, model, onOpenDetail = { detail = it }, onRestorePending = { restorePending = it })
    detail?.let { item ->
        val current = state.snapshot?.items?.firstOrNull { it.key == item.key } ?: item
        val now = rememberHomeworkNow()
        AlertDialog(onDismissRequest = { detail = null }, title = { Text("作业详情") },
            text = { SelectionContainer {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(current.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (current.status == HomeworkStatus.UNSUBMITTED) HomeworkPrioritySelector(current, state.marking, model::setPriority)
                    Text("${current.courseName}\n${current.status.label}\n截止：${current.deadline?.let { "${schoolTime(it)} · ${homeworkDeadlineRemaining(it, now)}" } ?: "未知"}\n${current.submissionNote}" +
                        (current.submittedAt?.let { "\n平台时间：${schoolTime(it)}" } ?: "") +
                        (current.lateDeadline?.let { "\n补交截止：${schoolTime(it)} · ${homeworkDeadlineRemaining(it, now)}" } ?: ""))
                }
            } },
            confirmButton = {
                if (current.onlineSubmission == true && current.status != HomeworkStatus.GRADED) Button({ detail = null; model.startSubmission(current) }, enabled = !state.loading) {
                    Text(if (current.status == HomeworkStatus.UNSUBMITTED) "提交作业" else "重新提交")
                } else TextButton({ detail = null }) { Text("关闭") }
            },
            dismissButton = { TextButton({ detail = null }) { Text("返回列表") } })
    }
    restorePending?.let { entry -> AlertDialog(onDismissRequest = { restorePending = null }, title = { Text("恢复待提交附件") },
        text = { Text("请先核对“${entry.assignment.orEmpty()}”的学校提交结果。恢复后，${entry.name} 会再次用于待提交选择；此操作不会上传文件。") },
        confirmButton = { TextButton({ restorePending = null; model.restorePendingFile(entry.id) }) { Text("已核实，恢复使用") } },
        dismissButton = { TextButton({ restorePending = null }) { Text("返回核对") } }) }
    state.submission?.let { HomeworkSubmissionDialog(it, model, state.pendingFiles, state.receivingSharedFiles) }
}

private fun schoolTime(time: Instant) = time.atZone(CourseHomework.SCHOOL_ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

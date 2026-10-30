package com.zhishi.schedule.ui.screens

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.domain.*
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun HomeworkSubmissionDialog(draft: HomeworkSubmissionDraft, model: HomeworkViewModel,
    pendingFiles: List<PendingHomeworkFile> = emptyList(), receivingSharedFiles: Boolean = false) {
    var chooseSaved by remember { mutableStateOf(false) }
    var rename by rememberSaveable(draft.homework.key) { mutableStateOf(false) }
    var expandedName by rememberSaveable(draft.attachment?.file?.path) { mutableStateOf(false) }
    val now = rememberHomeworkNow()
    val context = LocalContext.current
    val locked = draft.busy || draft.pickingFile || draft.resultUncertain
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.chooseSubmissionAttachment(uri)
    }
    val readyFiles = pendingFiles.count { !it.awaitingResult }
    FullScreenPageDialog(model::closeSubmission) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(model::closeSubmission, enabled = !draft.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text(if (draft.homework.status == HomeworkStatus.UNSUBMITTED) "提交附件" else "重新提交附件",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(draft.homework.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(draft.homework.courseName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text("截止：${draft.homework.deadline?.let { "${submissionTime(it)} · ${homeworkDeadlineRemaining(it, now)}" } ?: "未知，请核对学校要求"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                draft.homework.lateDeadline?.let { Text("补交：${submissionTime(it)} · ${homeworkDeadlineRemaining(it, now)}", style = MaterialTheme.typography.bodySmall) }
            }
            Text("提交附件", style = MaterialTheme.typography.titleSmall)
            if (draft.attachment == null) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (draft.pickingFile) "正在准备附件…" else "请选择要提交的文件", style = MaterialTheme.typography.bodyMedium)
                        Text("单个非空文件，最大50MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ picker.launch(arrayOf("*/*")) }, Modifier.weight(1f), enabled = !locked) { Text("选择文件") }
                            if (readyFiles > 0) OutlinedButton({ chooseSaved = true }, Modifier.weight(1f), enabled = !locked) { Text("待交文件 $readyFiles") }
                        }
                    }
                }
            } else {
                val file = draft.attachment
                val uploadedName = runCatching { draft.uploadForSubmission()?.name }.getOrNull()
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).clickable { expandedName = !expandedName }) {
                                Text(file.name, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = if (expandedName) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                                Text(Formatter.formatShortFileSize(context, file.size), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({ expandedName = !expandedName }) { Icon(if (expandedName) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "展开或收起完整文件名") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("提交名：${uploadedName ?: "请修改文件名"}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                maxLines = if (expandedName) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                            TextButton({ rename = !rename }, enabled = !locked) { Text(if (rename) "收起" else "改名") }
                        }
                        if (rename) OutlinedTextField(draft.attachmentBaseName, model::updateSubmissionName,
                            Modifier.fillMaxWidth(), label = { Text("提交名称") }, singleLine = true, readOnly = locked,
                            supportingText = { Text("扩展名自动保留") })
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ picker.launch(arrayOf("*/*")) }, enabled = !locked) { Text("更换文件") }
                            if (readyFiles > 0) TextButton({ chooseSaved = true }, enabled = !locked) { Text("待交文件 $readyFiles") }
                            Spacer(Modifier.weight(1f))
                            IconButton(model::removeSubmissionAttachment, enabled = !locked) { Icon(Icons.Outlined.Close, "移除本次选择的附件") }
                        }
                    }
                }
            }
            if (receivingSharedFiles) Text("正在保存新分享的附件…", style = MaterialTheme.typography.bodySmall)
            if (draft.homework.overdue(now)) Text("已过原截止时间，能否补交以学校要求为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (draft.homework.groupWork == true) Text("小组作业，请确认本次提交符合小组分工。", style = MaterialTheme.typography.bodySmall)
            if (draft.homework.status != HomeworkStatus.UNSUBMITTED) {
                Text("本次仅提交附件。重交可能清空学校原有正文并替换附件，请先核对原提交。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(draft.acceptReplacement, model::acceptReplacement, enabled = !locked)
                    Text("确认覆盖这份已提交作业", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (draft.busy || draft.pickingFile) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (draft.resultUncertain) Button(model::returnToVerifySubmission, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("刷新核实提交结果") }
            else Button(model::reviewSubmission, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !locked && draft.attachment != null && (draft.homework.status == HomeworkStatus.UNSUBMITTED || draft.acceptReplacement)) {
                Text(if (draft.busy) "正在提交附件…" else "核对附件")
            }
        }
    }
    if (draft.confirming) AlertDialog(onDismissRequest = model::cancelSubmissionConfirmation, title = { Text("确认提交到网络学堂") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(draft.homework.courseName)
                Text(draft.homework.title, style = MaterialTheme.typography.titleMedium)
                Text("原文件：${draft.attachment?.name.orEmpty()}")
                Text("提交名：${draft.uploadForSubmission()?.let { "${it.name}（${Formatter.formatShortFileSize(context, it.size)}）" } ?: "未选择附件"}")
                Text("仅提交附件，不提交正文。", style = MaterialTheme.typography.bodySmall)
                if (draft.homework.status != HomeworkStatus.UNSUBMITTED) Text("可能清空原正文并替换附件，请确认已核对原提交。", color = MaterialTheme.colorScheme.error)
                if (draft.homework.groupWork == true) Text("这是小组作业，本次提交会影响学校记录的小组状态。")
            }
        }, confirmButton = { Button(model::confirmSubmission) { Text("确认提交") } },
        dismissButton = { TextButton(model::cancelSubmissionConfirmation) { Text("返回修改") } })
    if (chooseSaved) AlertDialog(onDismissRequest = { chooseSaved = false }, title = { Text("选择待交文件") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            pendingFiles.filterNot { it.awaitingResult }.forEach { entry ->
                TextButton({ chooseSaved = false; model.choosePendingAttachment(entry.id) }, Modifier.fillMaxWidth()) { Text(entry.name) }
            }
        } }, confirmButton = { TextButton({ chooseSaved = false }) { Text("关闭") } })
}

private fun submissionTime(time: Instant) = time.atZone(CourseHomework.SCHOOL_ZONE).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

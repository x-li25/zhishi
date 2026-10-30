package com.zhishi.schedule.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhishi.schedule.data.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

@Composable
fun CampusSettings(state: CampusState, model: CampusViewModel, onRequestCalendarPermission: () -> Unit,
    calendarGranted: Boolean, onLoginSuccess: () -> Unit = { model.closeLogin(); model.openPreview() },
    onServiceVerified: (CampusLoginTarget) -> Unit = { if (it == CampusLoginTarget.TIMETABLE) model.refreshAfterLogin(false, false) }) {
    var confirmImport by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("清华课表与考试", style = MaterialTheme.typography.titleMedium)
            Text("学校登录后自动刷新作业与课表；课表预览可下拉刷新，核对后再导入。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!state.graduate, { model.configure(graduate = false) }, label = { Text("本科生") }, enabled = !state.busy && !state.clearing)
                FilterChip(state.graduate, { model.configure(graduate = true) }, label = { Text("研究生") }, enabled = !state.busy && !state.clearing)
            }
            OutlinedTextField(state.start, { model.configure(start = it) }, label = { Text("起始日期 · 年-月-日") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy && !state.clearing)
            OutlinedTextField(state.end, { model.configure(end = it) }, label = { Text("结束日期 · 年-月-日") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy && !state.clearing)
            Text("默认使用已配置的 2026–2027 秋季校历，可改为其他日期。仅读教学日历中的课程、考试，不导入个人日历。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(model::login, enabled = !state.busy && !state.clearing) { Text("学校登录") }
                Button(model::openPreview, enabled = !state.clearing) { Text("查看课表") }
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress.orEmpty(), style = MaterialTheme.typography.bodySmall)
                if (!state.importing) TextButton(model::cancelRead) { Text("停止读取") }
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.previewUpdatedAt?.let { time -> Text("更新于 ${time.atZone(CampusScheduleParser.zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}" +
                if (state.previewNeedsRefresh) " · 当前为旧预览，请下拉重试" else "", style = MaterialTheme.typography.bodySmall) }
            TextButton({ confirmClear = true }, enabled = !state.busy && !state.clearing) { Text(if (state.clearing) "正在清除…" else "清除本机登录状态") }
            Text("登录信息经系统加密保存在本机，不发送给 AI。导入后，AI 可按问题读取相应日程。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (state.loginVisible) CampusLoginDialog(state.graduate, model::closeLogin, onLoginSuccess, onServiceVerified = onServiceVerified)
    if (state.previewVisible) CampusPreviewDialog(state, model, calendarGranted) {
        if (calendarGranted) confirmImport = true else onRequestCalendarPermission()
    }
    if (confirmImport) AlertDialog(onDismissRequest = { confirmImport = false }, title = { Text("导入到手机日历") },
        text = { Text("将添加所选 ${state.selected.size} 条课程与考试，使用学校返回的实际时间和地点。已有日程会保留；学校后续调整安排时，请重新读取并核对。") },
        confirmButton = { TextButton({ confirmImport = false; model.importSelected() }) { Text("确认导入") } },
        dismissButton = { TextButton({ confirmImport = false }) { Text("返回核对") } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清除本机登录状态") },
        text = { Text("下次需要重新完成学校登录和验证。已导入的日程会保留。") },
        confirmButton = { TextButton({ confirmClear = false; model.clearLogin() }) { Text("清除") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("取消") } })
}


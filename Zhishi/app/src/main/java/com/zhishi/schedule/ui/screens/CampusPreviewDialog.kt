package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhishi.schedule.data.CampusScheduleParser
import java.time.Instant
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusPreviewDialog(state: CampusState, model: CampusViewModel, calendarGranted: Boolean, onImport: () -> Unit) {
    val rows = state.preview.orEmpty()
    Dialog(onDismissRequest = model::closePreview, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(model::closePreview, enabled = !state.importing) { Text("返回") }
                    Text("课表与考试", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    Text("下拉刷新", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text("${state.start} 至 ${state.end}（北京时间）", style = MaterialTheme.typography.bodySmall)
                state.previewUpdatedAt?.let { Text("更新于 ${it.atZone(CampusScheduleParser.zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}", style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton({ model.selectAll(true) }, enabled = !state.busy && !state.previewNeedsRefresh) { Text("全选") }
                    TextButton({ model.selectAll(false) }, enabled = !state.busy && !state.previewNeedsRefresh) { Text("清空选择") }
                    if (!state.busy && (state.previewNeedsRefresh || state.preview == null)) TextButton(model::login) { Text("学校登录") }
                    if (state.busy && !state.importing) TextButton(model::cancelRead) { Text("停止读取") }
                }
                if (state.busy) Text(state.progress.orEmpty(), style = MaterialTheme.typography.bodySmall)
                if (state.importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                PullToRefreshBox(isRefreshing = state.busy && !state.importing, onRefresh = model::read,
                    modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!state.busy) state.message?.let { item { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (state.previewNeedsRefresh) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } }
                        if (state.previewNeedsRefresh && state.preview != null) item {
                            Text("当前为旧预览，刷新成功后才能导入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (state.preview == null && !state.busy) item {
                            Text("登录学校后会自动刷新。也可在此下拉，读取所选范围的课表与考试。")
                        }
                        if (state.preview != null) item {
                            Text("${rows.count { !it.exam }} 条课程 / ${rows.count { it.exam }} 条考试。学校未公布的安排不会显示。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.preview != null && rows.isEmpty()) item {
                            Text("这个范围内没有返回日程，可下拉重新读取。")
                        }
                        items(rows, key = { it.key }) { event ->
                            val imported = event.marker in state.imported
                            val enabled = !state.busy && !state.previewNeedsRefresh && !imported
                            Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { model.select(event.key) }, verticalAlignment = Alignment.Top) {
                                Checkbox(event.key in state.selected, { model.select(event.key) }, enabled = enabled)
                                Column(Modifier.weight(1f).padding(top = 10.dp)) {
                                    Text(event.title, style = MaterialTheme.typography.titleMedium)
                                    val start = Instant.ofEpochMilli(event.startMillis).atZone(CampusScheduleParser.zone)
                                    val end = Instant.ofEpochMilli(event.endMillis).atZone(CampusScheduleParser.zone)
                                    Text("${start.format(DateTimeFormatter.ofPattern("MM-dd E HH:mm"))}–${end.toLocalTime()}", style = MaterialTheme.typography.bodySmall)
                                    if (event.location.isNotBlank()) Text(event.location, style = MaterialTheme.typography.bodySmall)
                                    Text(if (imported) "已导入（保留你在日历中的修改）" else event.category.ifBlank { "课程" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                Button(onImport, Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    enabled = !state.busy && !state.previewNeedsRefresh && state.selected.isNotEmpty()) {
                    Text(if (calendarGranted) "导入 ${state.selected.size} 条" else "授权日历后导入")
                }
            }
        }
    }
}

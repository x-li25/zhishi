package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.domain.*
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeworkContent(state: HomeworkState, model: HomeworkViewModel,
    onOpenDetail: (CourseHomework) -> Unit, onRestorePending: (PendingHomeworkFile) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showFileInfo by rememberSaveable { mutableStateOf(false) }
    var showFileSearch by rememberSaveable { mutableStateOf(false) }
    var fileSearch by rememberSaveable { mutableStateOf("") }
    val assignments = state.page == HomeworkPage.ASSIGNMENTS
    val now = rememberHomeworkNow()
    val snacks = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let { message ->
            snacks.showSnackbar(message, withDismissAction = true)
            model.dismissNotice(message)
        }
    }
    FullScreenPageDialog(model::close) {
        // Keep the same action slots on both pages so the switcher never changes width.
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(model::close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Row {
                    HomeworkPage.entries.forEach { page ->
                        val selected = state.page == page
                        Surface(Modifier.weight(1f).selectable(selected, role = Role.Tab, onClick = { model.selectPage(page) }),
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                            Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(if (page == HomeworkPage.ASSIGNMENTS) "作业" else "文件" + if (state.pendingFiles.isNotEmpty()) " ${state.pendingFiles.size}" else "",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            IconButton({
                if (assignments) {
                    if (showSearch || state.search.isNotBlank()) { model.search(""); showSearch = false }
                    else showSearch = true
                } else {
                    if (showFileSearch || fileSearch.isNotBlank()) { fileSearch = ""; showFileSearch = false }
                    else showFileSearch = true
                }
            }) { Icon(Icons.Outlined.Search, if (assignments) "搜索课程或作业" else "搜索文件名") }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                DropdownMenu(menu, { menu = false }) {
                    if (state.loading) DropdownMenuItem(text = { Text("停止刷新") }, onClick = { menu = false; model.cancel() })
                    DropdownMenuItem(text = { Text("学校登录") }, onClick = { menu = false; model.login() })
                    DropdownMenuItem(text = { Text(if (assignments) { if (showInfo) "收起读取说明" else "查看读取说明" } else "文件使用说明") },
                        onClick = { menu = false; if (assignments) showInfo = !showInfo else showFileInfo = true })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (assignments) PullToRefreshBox(isRefreshing = state.loading, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
                AssignmentList(state, showInfo, now, onOpenDetail, model::setPriority, header = {
                    HomeworkFilters(state, now, model, showSearch) { model.search(""); showSearch = false }
                })
            } else PendingFileList(state, onRestorePending, model::removePendingFile,
                onChooseAssignment = { model.selectPage(HomeworkPage.ASSIGNMENTS) }, search = fileSearch,
                header = if (showFileSearch || fileSearch.isNotBlank()) {
                    { HomeworkSearchField(fileSearch, { fileSearch = it.take(100) }, "搜索文件名") {
                        fileSearch = ""; showFileSearch = false
                    } }
                } else null)
            SnackbarHost(snacks, Modifier.align(Alignment.BottomCenter).padding(8.dp))
        }
    }
    if (showFileInfo) AlertDialog(onDismissRequest = { showFileInfo = false }, title = { Text("文件使用说明") },
        text = { Text("从其他软件分享文件到知时后，文件副本会保存在本机。选择作业后按分享顺序带入，可在提交前更换附件或修改上传名称。只有核对并确认后才会上传学校；移除本机副本不会删除原文件。") },
        confirmButton = { TextButton({ showFileInfo = false }) { Text("知道了") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeworkFilters(state: HomeworkState, now: Instant, model: HomeworkViewModel, showSearch: Boolean, onCloseSearch: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(HomeworkFilter.UNSUBMITTED to "未提交", HomeworkFilter.SUBMITTED to "已提交", HomeworkFilter.GRADED to "已批改", HomeworkFilter.ALL to "全部").forEach { (value, label) ->
                FilterChip(state.filter == value, { model.filter(value) }, label = { Text(label) })
            }
        }
        if (state.filter == HomeworkFilter.UNSUBMITTED) {
            val rows = state.snapshot?.items.orEmpty().filter { it.status == HomeworkStatus.UNSUBMITTED &&
                (state.search.isBlank() || it.title.contains(state.search, true) || it.courseName.contains(state.search, true)) }
            SecondaryTabRow(selectedTabIndex = HomeworkPriority.entries.indexOf(state.priorityFilter), containerColor = MaterialTheme.colorScheme.background) {
                HomeworkPriority.entries.forEach { group ->
                    val selected = state.priorityFilter == group
                    val purple = selected && group != HomeworkPriority.NOT_REQUIRED
                    Tab(selected, { model.priorityFilter(group) }, text = {
                        Surface(shape = MaterialTheme.shapes.small,
                            color = if (purple) HomeworkPriorityPurple else MaterialTheme.colorScheme.background) {
                            Text("${group.label} ${rows.count { it.priority(now) == group }}", Modifier.padding(horizontal = if (purple) 6.dp else 0.dp, vertical = 4.dp),
                                color = if (purple) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
                }
            }
        }
        if (showSearch || state.search.isNotBlank()) HomeworkSearchField(state.search, model::search, "搜索课程或作业", onCloseSearch)
    }
}

@Composable
private fun HomeworkSearchField(value: String, onValueChange: (String) -> Unit, label: String, onClose: () -> Unit) {
    OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
        trailingIcon = { IconButton(onClose) { Icon(Icons.Outlined.Close, "清空并收起搜索") } })
}

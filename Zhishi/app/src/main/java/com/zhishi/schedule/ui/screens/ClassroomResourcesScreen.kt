package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhishi.schedule.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val classroomPeriods = listOf("08:00–09:35", "09:50–12:15", "13:30–15:05", "15:20–16:55", "17:05–18:40", "19:20–20:55")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassroomResourcesScreen(graduate: Boolean, onServiceVerified: (CampusLoginTarget) -> Unit, model: ClassroomViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.open() }
    DisposableEffect(Unit) { onDispose { model.back() } }
    fun login() { CampusSession.invalidate(); showLogin = true }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(search, { search = it.take(100) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true, label = { Text("搜索教学楼") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
        ClassroomReadStatus(state, model::refresh, ::login, model::stop)
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = model::refresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
            val rows = state.buildings.filter { search.isBlank() || it.name.contains(search, true) }.chunked(2)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!state.loading && rows.isEmpty() && state.error == null) item { Text(if (search.isBlank()) "暂未读取到教学楼，请下拉刷新。" else "没有匹配的教学楼。") }
                items(rows, key = { it.first().queryName }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { building ->
                            OutlinedCard(onClick = { model.select(building) }, Modifier.weight(1f)) {
                                Box(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(12.dp), contentAlignment = Alignment.Center) {
                                    Text(building.name, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    state.building?.let { building -> FeaturePage(building.name, model::back) { ClassroomDayTable(state, model, ::login) } }
    if (showLogin) CampusLoginDialog(graduate, { showLogin = false }, { showLogin = false; model.open(force = true) }, onServiceVerified = onServiceVerified)
}

@Composable
private fun ClassroomReadStatus(state: ClassroomState, onRefresh: () -> Unit, onLogin: () -> Unit, onStop: () -> Unit) {
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { message ->
        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(message + if (state.snapshot != null) "\n下方为上次读取的数据。" else "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Row { TextButton(onRefresh, enabled = !state.loading) { Text("重试") }; TextButton(onLogin) { Text("学校登录") } }
            }
        }
    }
    if (state.loading) TextButton(onStop) { Text("停止读取") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassroomDayTable(state: ClassroomState, model: ClassroomViewModel, onLogin: () -> Unit) {
    var picker by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<ClassroomRoom, Int?>?>(null) }
    val report = state.snapshot
    LaunchedEffect(state.date, state.building, report) { detail = null }
    val day = report?.dates?.indexOf(state.date)?.takeIf { it >= 0 }
    val weekday = "一二三四五六日"[state.date.dayOfWeek.value - 1]
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ model.selectDate(state.date.minusDays(1)) }, enabled = !state.loading && model.canSelect(state.date.minusDays(1))) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "前一天")
            }
            TextButton({ picker = true }, Modifier.weight(1f), enabled = report != null && !state.loading) {
                Text(((report?.number ?: state.requestedWeek)?.let { "第${it}周 " } ?: "") + "周$weekday ${state.date.format(DateTimeFormatter.ofPattern("MM.dd"))}",
                    style = MaterialTheme.typography.titleMedium)
            }
            IconButton({ model.selectDate(state.date.plusDays(1)) }, enabled = !state.loading && model.canSelect(state.date.plusDays(1))) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "后一天")
            }
            IconButton({ showSearch = !showSearch; if (!showSearch) search = "" }) { Icon(Icons.Outlined.Search, "搜索教室") }
        }
        if (showSearch) OutlinedTextField(search, { search = it.take(100) }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true,
            label = { Text("教室编号") }, trailingIcon = { TextButton({ model.selectDate(LocalDate.now(ZoneId.of("Asia/Shanghai"))) }, enabled = !state.loading && model.canSelect(LocalDate.now(ZoneId.of("Asia/Shanghai")))) { Text("今天") } })
        ClassroomReadStatus(state, model::refresh, onLogin, model::stop)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClassroomOccupancy.entries.forEach { status -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).background(classroomColor(status), RoundedCornerShape(2.dp)))
                Text(status.label, style = MaterialTheme.typography.labelSmall)
            } }
        }
        val updated = state.updatedAt?.atZone(ZoneId.of("Asia/Shanghai"))?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        Text(updated?.let { "${it}更新 · 下拉刷新 · 点击色块查看" } ?: "下拉刷新 · 点击色块查看详情",
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TableHeading()
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = model::refresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                if (report != null && day != null) {
                    val rooms = report.rooms.filter { search.isBlank() || it.name.contains(search, true) }
                    if (rooms.isEmpty()) item { Text(if (search.isBlank()) "学校当前未返回该楼宇的教室记录。" else "没有匹配的教室。", Modifier.padding(16.dp)) }
                    items(rooms, key = { it.name }) { room ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(room.name, Modifier.width(64.dp).clickable { detail = room to null }.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(room.capacity?.toString() ?: "—", Modifier.width(40.dp), style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.weight(1f)) {
                                repeat(6) { period ->
                                    val slot = room.slots[day * 6 + period]
                                    Box(Modifier.weight(1f).height(44.dp).padding(2.dp).background(classroomColor(slot.occupancy), RoundedCornerShape(4.dp))
                                        .clickable(onClickLabel = "查看时段详情") { detail = room to period }.semantics {
                                            contentDescription = "${room.name}，第${period + 1}大节，${slot.occupancy.label}"
                                        }, contentAlignment = Alignment.Center) {
                                        Text(slot.occupancy.symbol, style = MaterialTheme.typography.labelSmall,
                                            color = if (slot.occupancy in listOf(ClassroomOccupancy.TEACHING, ClassroomOccupancy.EXAM)) Color.White else Color(0xFF25212D))
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                    item { Text("未占用表示学校未排占用记录，开放情况以现场为准。", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    if (picker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = model.canSelect(Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()) })
        DatePickerDialog(onDismissRequest = { picker = false }, confirmButton = {
            TextButton({ pickerState.selectedDateMillis?.let { model.selectDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; picker = false }, enabled = pickerState.selectedDateMillis != null) { Text("确定") }
        }, dismissButton = { TextButton({ picker = false }) { Text("取消") } }) { DatePicker(pickerState) }
    }
    detail?.let { (room, period) -> if (day != null) AlertDialog(onDismissRequest = { detail = null }, title = { Text("${state.building?.name} ${room.name}") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("${state.date} · 容量${room.capacity?.let { "${it}人" } ?: "未知"}") }
                (period?.let { listOf(it) } ?: (0..5).toList()).forEach { index -> item {
                    val slot = room.slots[day * 6 + index]
                    Text("第${index + 1}大节  ${classroomPeriods[index]}", fontWeight = FontWeight.SemiBold)
                    Text(slot.occupancy.label)
                    if (slot.detail.isNotBlank()) Text(slot.detail) else Text("学校未提供该时段的课程或活动名称。", style = MaterialTheme.typography.bodySmall)
                } }
            }
        }, confirmButton = { TextButton({ detail = null }) { Text("关闭") } }) }
}

@Composable
private fun TableHeading() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("教室", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
        Text("容量\n(人)", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall)
        Row(Modifier.weight(1f)) { repeat(6) { period -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("${period + 1}", style = MaterialTheme.typography.labelMedium) } } }
    }
}

private fun classroomColor(status: ClassroomOccupancy): Color = when (status) {
    ClassroomOccupancy.AVAILABLE -> Color(0xFFE5E1EC)
    ClassroomOccupancy.TEACHING -> Color(0xFF4A238F)
    ClassroomOccupancy.EXAM -> Color(0xFFAE315F)
    ClassroomOccupancy.BORROWED -> Color(0xFFC8ADEB)
    ClassroomOccupancy.DISABLED -> Color(0xFFADABB1)
    ClassroomOccupancy.UNKNOWN -> Color(0xFFFFDF9E)
}

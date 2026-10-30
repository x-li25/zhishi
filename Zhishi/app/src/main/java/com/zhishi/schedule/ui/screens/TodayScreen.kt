package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.data.ChecklistStore
import com.zhishi.schedule.domain.AcademicCalendar
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.util.AppDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: AppUiState,
    onRequestCalendarPermission: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenDetail: (CalendarEvent) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onRetry: () -> Unit,
    onArrange: () -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit,
    weatherIcon: @Composable () -> Unit,
    featurePanel: @Composable () -> Unit
) {
    val day = state.daySchedule?.takeIf { it.date == state.selectedDate }
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 日期 + 当日日程合成一张卡片：紫色日期标题、分隔线、竖条日程列表
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onPreviousDay) {
                            Icon(Icons.Outlined.ChevronLeft, contentDescription = "前一天")
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton({ choosingDate = true }) {
                                    Text(
                                        text = AppDateFormat.shortDate(state.selectedDate),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(Icons.Outlined.ArrowDropDown, "选择日期")
                                }
                                weatherIcon()
                            }
                            Text(
                                text = "${AppDateFormat.weekday(state.selectedDate)} · ${AcademicCalendar.label(state.selectedDate)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (state.selectedDate != LocalDate.now()) TextButton({ onSelectDate(LocalDate.now()) }) { Text("回到今天") }
                            val events = day?.events.orEmpty()
                            Text(
                                text = when {
                                    !state.calendarPermissionGranted -> "尚未授权日历"
                                    state.loading -> "正在读取日历…"
                                    state.dayError != null -> "读取失败"
                                    day == null -> "尚未读取日程"
                                    events.isEmpty() -> "0 个日程"
                                    else -> "${events.size} 个日程"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onNextDay) {
                            Icon(Icons.Outlined.ChevronRight, contentDescription = "后一天")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    if (state.loading && day != null && state.calendarPermissionGranted) LinearProgressIndicator(Modifier.fillMaxWidth())
                    when {
                        !state.calendarPermissionGranted -> DayStatus("授权后显示手机日程", "读取安排，并在确认后写入手机日历。",
                            action = "授权日历", onAction = onRequestCalendarPermission)
                        state.dayError != null -> DayStatus("日程读取失败", state.dayError, action = "重新读取", onAction = onRetry)
                        state.loading && day == null -> DayStatus("正在读取日程…", loading = true)
                        day == null -> DayStatus("暂未读取日程", action = "读取日程", onAction = onRetry)
                        day.events.isEmpty() -> DayStatus("这一天还没有日程", "告诉助手你的安排，就可以从这里查看。",
                            action = "让助手安排", onAction = onArrange)
                        else -> Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                            day.events.forEachIndexed { index, event ->
                                val checklist = state.checklists[ChecklistStore.keyOf(event)].orEmpty()
                                EventRow(
                                    event = event,
                                    checklistDone = checklist.count { it.done },
                                    checklistTotal = checklist.size,
                                    onOpenDetail = { onOpenDetail(event) },
                                    onLongPress = { onDeleteEvent(event) }
                                )
                                if (index < day.events.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 56.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        item(key = "features") { featurePanel() }

    }
    if (choosingDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = state.selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { choosingDate = false },
            confirmButton = { TextButton({
                picker.selectedDateMillis?.let { onSelectDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                choosingDate = false
            }, enabled = picker.selectedDateMillis != null) { Text("确定") } },
            dismissButton = { TextButton({ choosingDate = false }) { Text("取消") } }) {
            DatePicker(picker, title = { Text("选择日期", Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}

@Composable
private fun DayStatus(title: String, description: String? = null, loading: Boolean = false,
    action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.data.CompletionStore
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.DaySchedule
import com.zhishi.schedule.util.AppDateFormat
import java.time.LocalDate

@Composable
fun WeekScreen(
    state: AppUiState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
    onToggleCompleted: (CalendarEvent) -> Unit
) {
    var expandedDate by remember { mutableStateOf(LocalDate.now()) }
    val weekEnd = state.weekStart.plusDays(6)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "全周日程",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "一览本周安排与完成进度",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onPreviousWeek) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一周")
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${AppDateFormat.shortDate(state.weekStart)} - ${AppDateFormat.shortDate(weekEnd)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = onCurrentWeek) {
                            Text("回到本周", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = onNextWeek) {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = "下一周")
                    }
                }
            }
        }

        if (!state.calendarPermissionGranted) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("需要日历权限", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "请到“今天”页授权日历后再查看全周日程。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (state.weekDays.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.CalendarMonth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "正在读取本周日程…",
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    }
                }
            }
        } else {
            items(state.weekDays, key = { it.date.toString() }) { day ->
                WeekDayCard(
                    day = day,
                    expanded = expandedDate == day.date,
                    completedKeys = state.completedKeys,
                    onToggleExpand = {
                        expandedDate = if (expandedDate == day.date) LocalDate.MIN else day.date
                    },
                    onToggleCompleted = onToggleCompleted
                )
            }
        }
    }
}

@Composable
private fun WeekDayCard(
    day: DaySchedule,
    expanded: Boolean,
    completedKeys: Set<String>,
    onToggleExpand: () -> Unit,
    onToggleCompleted: (CalendarEvent) -> Unit
) {
    val isToday = day.date == LocalDate.now()
    val doneCount = day.events.count { CompletionStore.keyOf(it) in completedKeys }
    val freeText = day.freeRanges
        .filter { it.durationMinutes >= 30 }
        .take(2)
        .joinToString("  ·  ") { AppDateFormat.timeRange(it.startMillis, it.endMillis, day.zoneId) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand),
        colors = if (isToday) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${AppDateFormat.shortDate(day.date)} ${AppDateFormat.weekday(day.date)}" +
                            if (isToday) " · 今天" else "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isToday) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (day.events.isEmpty()) {
                            "没有日程"
                        } else {
                            "${day.events.size} 个日程 · 已完成 $doneCount"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开"
                )
            }
            if (freeText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "空闲：$freeText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded && day.events.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Column {
                    day.events.forEachIndexed { index, event ->
                        EventRow(
                            event = event,
                            completed = CompletionStore.keyOf(event) in completedKeys,
                            onToggleCompleted = { onToggleCompleted(event) }
                        )
                        if (index < day.events.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

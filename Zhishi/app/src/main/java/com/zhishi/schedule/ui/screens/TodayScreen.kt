package com.zhishi.schedule.ui.screens

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
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.data.CompletionStore
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.ScheduleSuggestion
import com.zhishi.schedule.domain.SuggestionType
import com.zhishi.schedule.util.AppDateFormat

@Composable
fun TodayScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToggleCompleted: (CalendarEvent) -> Unit,
    onAddSuggestion: (SuggestionType, String, Long, Long) -> Unit
) {
    val day = state.daySchedule
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 日期 + 当日日程合成一张卡片：紫色日期标题、分隔线、竖条日程列表
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
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
                            Text(
                                text = AppDateFormat.date(state.selectedDate),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val events = day?.events.orEmpty()
                            val doneCount = events.count { CompletionStore.keyOf(it) in state.completedKeys }
                            Text(
                                text = when {
                                    state.loading -> "正在读取日历…"
                                    events.isEmpty() -> "0 个日程"
                                    else -> "${events.size} 个日程 · 已完成 $doneCount"
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
                    when {
                        day == null -> Text(
                            text = "正在读取日程…",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        day.events.isEmpty() -> Text(
                            text = "这一天还没有日程，可以在“识别”页用文字快速添加。",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                            day.events.forEachIndexed { index, event ->
                                EventRow(
                                    event = event,
                                    completed = CompletionStore.keyOf(event) in state.completedKeys,
                                    onToggleCompleted = { onToggleCompleted(event) }
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

        if (!state.calendarPermissionGranted) {
            item {
                PermissionCard(onRequestCalendarPermission = onRequestCalendarPermission)
            }
        }

        item {
            SectionTitle("空闲与建议")
        }

        if (day != null) {
            val freeText = day.freeRanges
                .filter { it.durationMinutes >= 20 }
                .take(4)
                .joinToString("  ·  ") { AppDateFormat.timeRange(it.startMillis, it.endMillis) }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("可自由安排", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(if (freeText.isBlank()) "今天剩余空闲时间较少" else freeText)
                    }
                }
            }

            if (day.suggestions.isEmpty()) {
                item {
                    EmptyCard(
                        icon = Icons.Outlined.School,
                        title = "暂时没有合适建议",
                        subtitle = "可以放宽学习时间范围，或减少事件之间的缓冲时间。"
                    )
                }
            } else {
                items(day.suggestions) { suggestion ->
                    SuggestionCard(
                        suggestion = suggestion,
                        onAdd = {
                            val title = if (suggestion.type == SuggestionType.MEAL) {
                                suggestion.title
                            } else {
                                "学习"
                            }
                            onAddSuggestion(
                                suggestion.type,
                                title,
                                suggestion.range.startMillis,
                                suggestion.range.endMillis
                            )
                        }
                    )
                }
            }
        }

        item {
            FilledTonalButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重新计算")
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequestCalendarPermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("需要日历权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("读取系统日历后才能计算空闲时间，并把确认过的日程写回手机日历。")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequestCalendarPermission) {
                Text("授权日历")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun EmptyCard(icon: ImageVector, title: String, subtitle: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: ScheduleSuggestion,
    onAdd: () -> Unit
) {
    val icon = when (suggestion.type) {
        SuggestionType.STUDY -> Icons.Outlined.School
        SuggestionType.MEAL -> Icons.Outlined.Restaurant
        else -> Icons.Outlined.Event
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = suggestion.title,
                    modifier = Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = AppDateFormat.timeRange(suggestion.range.startMillis, suggestion.range.endMillis),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = suggestion.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onAdd,
                    label = { Text("加入日历") }
                )
            }
        }
    }
}

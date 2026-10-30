package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.ChecklistItem
import com.zhishi.schedule.util.AppDateFormat

/** 周视图详情：固定操作栏，正文可滚动，完整显示长标题、地点和跨天时间。 */
@Composable
fun WeekEventDetailDialog(
    event: CalendarEvent,
    checklist: List<ChecklistItem>,
    saving: Boolean,
    error: String?,
    onSave: (String, String) -> Unit,
    onOpenChecklist: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by rememberSaveable(event.eventId, event.startMillis) { mutableStateOf(event.title) }
    var location by rememberSaveable(event.eventId, event.startMillis) { mutableStateOf(event.location.orEmpty()) }
    val editable = !event.allDay && !event.isRecurring
    val changed = title.trim() != event.title || location.trim() != event.location.orEmpty().trim()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    val inputColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !saving,
            dismissOnClickOutside = !saving
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(max = maxHeight),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text(if (editable) "取消" else "关闭") }
                    Text(
                        text = if (editable) "编辑日程" else "日程详情",
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (editable) {
                        TextButton(onClick = { onSave(title, location) }, enabled = !saving && changed && title.isNotBlank()) {
                            if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("保存")
                        }
                    } else {
                        Spacer(Modifier.size(64.dp, 48.dp))
                    }
                }
                HorizontalDivider()
                error?.let {
                    Text(it, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        TextField(
                            value = title, onValueChange = { title = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("日程名称") },
                            textStyle = MaterialTheme.typography.titleMedium,
                            readOnly = !editable, enabled = !saving,
                            isError = title.isBlank(),
                            supportingText = if (title.isBlank()) ({ Text("日程名称不能为空") }) else null,
                            colors = inputColors
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        TextField(
                            value = location, onValueChange = { location = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("地点") }, placeholder = { Text("未设置地点") },
                            readOnly = !editable, enabled = !saving,
                            colors = inputColors
                        )
                    }

                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (event.allDay) {
                                DetailText("日期", AppDateFormat.allDayDateRange(event.startMillis, event.endMillis))
                            } else {
                                DetailText("开始时间", AppDateFormat.fullDateTime(event.startMillis))
                                DetailText("结束时间", AppDateFormat.fullDateTime(event.endMillis))
                            }
                            if (event.isRecurring) DetailText("重复", "重复日程")
                            DetailText("提醒", event.reminderMinutes.filter { it >= 0 }.distinct().sortedDescending()
                                .joinToString("、") { if (it == 0) "准时提醒" else "提前 $it 分钟" }.ifBlank { "无提醒" })
                        }
                    }

                    if (checklist.isNotEmpty()) {
                        OutlinedButton(onClick = onOpenChecklist, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                            Text("查看任务清单（${checklist.count { it.done }}/${checklist.size}）")
                        }
                    }
                    if (!editable) {
                        Text("全天或重复日程可在此查看完整信息，编辑请到系统日历中处理。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailText(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

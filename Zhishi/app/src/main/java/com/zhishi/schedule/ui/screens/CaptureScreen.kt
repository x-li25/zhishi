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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.zhishi.schedule.domain.ParsedActionType
import com.zhishi.schedule.domain.ParsedEvent
import com.zhishi.schedule.util.AppDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CaptureScreen(
    state: AppUiState,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
    onParse: () -> Unit,
    onUpdateParsedEvent: (Int, ParsedEvent) -> Unit,
    onRemoveParsedEvent: (Int) -> Unit,
    onConfirm: () -> Unit
) {
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    val editingEvent = editingIndex?.let { state.parseResult?.events?.getOrNull(it) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "快速识别",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("用文字描述日程，AI 会先整理成可确认的日程。")
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = state.textInput,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("例如：明天晚上八点复习数学一小时") },
                        minLines = 3,
                        maxLines = 6
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    enabled = !state.parsing
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = null)
                    Text(" 清空")
                }
                Button(
                    onClick = onParse,
                    modifier = Modifier.weight(2f),
                    enabled = !state.parsing && state.textInput.isNotBlank()
                ) {
                    if (state.parsing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                        Text(" 交给 AI 识别")
                    }
                }
            }
        }

        state.parseResult?.let { result ->
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "识别结果",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(result.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (result.questions.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("需要确认", fontWeight = FontWeight.SemiBold)
                            result.questions.forEach { question ->
                                Text("• $question")
                            }
                        }
                    }
                }
            }

            if (result.warnings.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("部分操作未采纳", fontWeight = FontWeight.SemiBold)
                            result.warnings.forEach { warning ->
                                Text("• $warning", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            itemsIndexed(result.events) { index, event ->
                ParsedEventCard(
                    event = event,
                    onEdit = { editingIndex = index },
                    onRemove = { onRemoveParsedEvent(index) }
                )
            }

            item {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = result.events.isNotEmpty() && !state.parsing
                ) {
                    Text("确认并执行（${result.events.size}）")
                }
            }
        }
    }

    if (editingIndex != null && editingEvent != null) {
        EditEventDialog(
            event = editingEvent,
            onDismiss = { editingIndex = null },
            onSave = {
                onUpdateParsedEvent(editingIndex!!, it)
                editingIndex = null
            }
        )
    }
}

@Composable
private fun ParsedEventCard(
    event: ParsedEvent,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    ActionBadge(event.action)
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = AppDateFormat.timeRange(event.startMillis, event.endMillis),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onEdit, enabled = event.action != ParsedActionType.DELETE) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onRemove) {
                    Icon(if (event.action == ParsedActionType.DELETE) Icons.Outlined.Delete else Icons.Outlined.Close, contentDescription = "移除")
                }
            }

            if (event.action == ParsedActionType.UPDATE) {
                Text(
                    text = "原：${event.originalTitle}  ${formatOriginalRange(event)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (event.action == ParsedActionType.DELETE) {
                Text(
                    text = "确认后将从系统日历删除此日程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    text = "识别置信度：${"%.0f".format(event.confidence * 100)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (event.confidence < 0.75) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "置信度较低，建议核对时间后再写入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionBadge(action: ParsedActionType) {
    val (label, color) = when (action) {
        ParsedActionType.CREATE -> "新增" to MaterialTheme.colorScheme.primary
        ParsedActionType.UPDATE -> "调整" to MaterialTheme.colorScheme.secondary
        ParsedActionType.DELETE -> "删除" to MaterialTheme.colorScheme.error
    }
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun EditEventDialog(
    event: ParsedEvent,
    onDismiss: () -> Unit,
    onSave: (ParsedEvent) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }
    var title by remember(event) { mutableStateOf(event.title) }
    var startText by remember(event) { mutableStateOf(formatDateTime(event.startMillis, zone)) }
    var endText by remember(event) { mutableStateOf(formatDateTime(event.endMillis, zone)) }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (event.action == ParsedActionType.CREATE) "编辑新日程" else "编辑调整") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text("开始 yyyy-MM-dd HH:mm") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text("结束 yyyy-MM-dd HH:mm") },
                    singleLine = true
                )
                validationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val start = parseDateTime(startText, zone)
                val end = parseDateTime(endText, zone)
                when {
                    title.isBlank() -> validationError = "标题不能为空"
                    start == null -> validationError = "开始时间格式不正确"
                    end == null -> validationError = "结束时间格式不正确"
                    end <= start -> validationError = "结束时间必须晚于开始时间"
                    else -> onSave(event.copy(title = title.trim(), startMillis = start, endMillis = end))
                }
            }) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Text(" 保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun formatOriginalRange(event: ParsedEvent): String {
    val start = event.originalStartMillis ?: return ""
    val end = event.originalEndMillis ?: return ""
    return AppDateFormat.timeRange(start, end)
}

private fun formatDateTime(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

private fun parseDateTime(text: String, zone: ZoneId): Long? = runCatching {
    LocalDateTime.parse(text.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        .atZone(zone)
        .toInstant()
        .toEpochMilli()
}.getOrNull()

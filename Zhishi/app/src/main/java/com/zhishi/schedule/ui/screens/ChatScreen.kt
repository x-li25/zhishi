package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.domain.ChatMessage
import com.zhishi.schedule.domain.ChatRole
import com.zhishi.schedule.domain.ParsedActionType
import com.zhishi.schedule.domain.ParsedEvent
import com.zhishi.schedule.util.AppDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChatScreen(
    state: AppUiState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onClearChat: () -> Unit,
    onConfirmMessage: (Long) -> Unit,
    onRemoveAction: (Long, Int) -> Unit,
    onUpdateAction: (Long, Int, ParsedEvent) -> Unit
) {
    var editingTarget by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    val editingEvent = editingTarget?.let { (messageId, index) ->
        state.messages.firstOrNull { it.id == messageId }?.actions?.getOrNull(index)
    }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.parsing) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = "知时助手",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "我会记住我们的对话，你说咋安排就咋安排",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClearChat, enabled = state.messages.isNotEmpty()) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = "清空对话")
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.messages.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("试试这样跟我说：", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text("• 明天下午三点到五点在六教6A414上大学物理", style = MaterialTheme.typography.bodyMedium)
                            Text("• 把周五的复习数学挪到晚上八点", style = MaterialTheme.typography.bodyMedium)
                            Text("• 取消后天早上的例会", style = MaterialTheme.typography.bodyMedium)
                            Text("• 我这周哪天下午有空？", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            items(state.messages, key = { it.id }) { message ->
                ChatBubble(
                    message = message,
                    parsing = state.parsing,
                    onConfirm = { onConfirmMessage(message.id) },
                    onEditAction = { index -> editingTarget = message.id to index },
                    onRemoveAction = { index -> onRemoveAction(message.id, index) }
                )
            }
            if (state.parsing) {
                item {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                        ) {
                            Text(
                                text = "正在思考…",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 清单文件里是 adjustNothing：窗口不随键盘缩放，
                // 输入框只由这一处 imePadding 抬到键盘上方，避免双重抬升
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.textInput,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("告诉我你的安排…") },
                maxLines = 4
            )
            IconButton(
                onClick = onSend,
                enabled = state.textInput.isNotBlank() && !state.parsing
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "发送",
                    tint = if (state.textInput.isNotBlank() && !state.parsing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
            }
        }
    }

    if (editingTarget != null && editingEvent != null) {
        EditEventDialog(
            event = editingEvent,
            onDismiss = { editingTarget = null },
            onSave = {
                onUpdateAction(editingTarget!!.first, editingTarget!!.second, it)
                editingTarget = null
            }
        )
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    parsing: Boolean,
    onConfirm: () -> Unit,
    onEditAction: (Int) -> Unit,
    onRemoveAction: (Int) -> Unit
) {
    val isUser = message.role == ChatRole.USER
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Surface(
                color = if (isUser) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = if (isUser) {
                    RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
                } else {
                    RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
                }
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }

        if (!isUser && message.warnings.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    message.warnings.forEach { warning ->
                        Text("• $warning", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (!isUser && message.actions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    message.actions.forEachIndexed { index, event ->
                        ActionRow(
                            event = event,
                            onEdit = { onEditAction(index) },
                            onRemove = { onRemoveAction(index) }
                        )
                        if (index < message.actions.lastIndex) {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !parsing
                    ) {
                        Text("确认并执行（${message.actions.size}）")
                    }
                }
            }
        }

        if (!isUser && message.executed) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "✓ 已写入系统日历",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ActionRow(
    event: ParsedEvent,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            ActionBadge(event.action)
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = AppDateFormat.timeRange(event.startMillis, event.endMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            event.location?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (event.checklist.isNotEmpty()) {
                Text(
                    text = "清单 ${event.checklist.size} 项：" +
                        event.checklist.take(3).joinToString("、") +
                        if (event.checklist.size > 3) " 等" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
            }
        }
        IconButton(onClick = onEdit, enabled = event.action != ParsedActionType.DELETE) {
            Icon(Icons.Outlined.Edit, contentDescription = "编辑")
        }
        IconButton(onClick = onRemove) {
            Icon(
                if (event.action == ParsedActionType.DELETE) Icons.Outlined.Delete else Icons.Outlined.Close,
                contentDescription = "移除"
            )
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
    var location by remember(event) { mutableStateOf(event.location.orEmpty()) }
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
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点（可选）") },
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
                    else -> onSave(
                        event.copy(
                            title = title.trim(),
                            location = location.trim().ifBlank { null },
                            startMillis = start,
                            endMillis = end
                        )
                    )
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

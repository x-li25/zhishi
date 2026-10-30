package com.zhishi.schedule.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.StopCircle
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.data.ChatImageStore
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
    onSelectImage: (Uri) -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit,
    onStopGeneration: () -> Unit,
    onClearChat: () -> Unit,
    onConfirmMessage: (Long) -> Unit,
    onRemoveAction: (Long, Int) -> Unit,
    onUpdateAction: (Long, Int, ParsedEvent) -> Unit
) {
    var editingTarget by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    val editingEvent = editingTarget?.let { (messageId, index) ->
        state.messages.firstOrNull { it.id == messageId }?.actions?.getOrNull(index)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.messages.lastIndex.coerceAtLeast(0))
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onSelectImage)
    }
    val busy = state.parsing || state.preparingImage

    LaunchedEffect(state.messages.size, state.parsing) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
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
            IconButton(onClick = onClearChat, enabled = (state.messages.isNotEmpty() || state.selectedImageFileName != null) && !busy) {
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
                            Text("• 发图片后先识别文字，文字不足时再看原图安排日程", style = MaterialTheme.typography.bodyMedium)
                            Text("• 结合天气安排户外活动，例如“明天帮我选个适合跑步的时间”", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            items(state.messages, key = { it.id }) { message ->
                ChatBubble(
                    message = message,
                    parsing = state.parsing,
                    knownEvents = state.knownEvents,
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.parsingLabel,
                                    modifier = Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (state.canStopGeneration) {
                                    TextButton(onClick = onStopGeneration) { Text("停止生成") }
                                }
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 清单文件里是 adjustNothing：窗口不随键盘缩放，
                // 输入框只由这一处 imePadding 抬到键盘上方，避免双重抬升
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (state.preparingImage) {
                Text("正在准备图片…", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
            }
            state.selectedImageFileName?.let { name ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            ChatImagePreview(name)
                            Spacer(Modifier.height(4.dp))
                            Text("优先提取文字安排日程，也可补充“只安排周五”等要求", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = onRemoveImage, enabled = !busy) {
                            Icon(Icons.Outlined.Close, contentDescription = "移除所选图片")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { imagePicker.launch("image/*") }, enabled = !busy) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "选择日程图片")
                }
                OutlinedTextField(
                    value = state.textInput,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (state.selectedImageFileName == null) "告诉我你的安排…" else "补充安排要求（可选）…") },
                    enabled = !busy,
                    maxLines = 4
                )
                IconButton(
                    onClick = if (state.canStopGeneration) onStopGeneration else onSend,
                    enabled = state.canStopGeneration || ((state.textInput.isNotBlank() || state.selectedImageFileName != null) && !busy)
                ) {
                    Icon(
                        if (state.canStopGeneration) Icons.Outlined.StopCircle else Icons.AutoMirrored.Outlined.Send,
                        contentDescription = if (state.canStopGeneration) "停止生成" else "发送",
                        tint = if (state.canStopGeneration || ((state.textInput.isNotBlank() || state.selectedImageFileName != null) && !busy)) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
                }
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
    knownEvents: List<CalendarEvent>,
    onConfirm: () -> Unit,
    onEditAction: (Int) -> Unit,
    onRemoveAction: (Int) -> Unit
) {
    val isUser = message.role == ChatRole.USER
    var showImageDetails by remember(message.id) { mutableStateOf(false) }
    val imageDetails = message.imageAnalysis ?: message.imageText
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
                Column(modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    message.imageFileName?.let { name ->
                        ChatImagePreview(name)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (imageDetails != null) {
                        TextButton(onClick = { showImageDetails = !showImageDetails }) {
                            Text(if (showImageDetails) "收起识别结果" else if (message.imageAnalysis != null) "查看识别日程" else "查看识别文字",
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                        }
                        if (showImageDetails) {
                            Text(imageDetails.ifBlank { "未识别到日程信息" }, style = MaterialTheme.typography.bodySmall,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        if (!isUser && message.tokenUsage != null) {
            Text(
                text = "本次消耗 ${message.tokenUsage} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(if (isUser) Alignment.End else Alignment.Start)
            )
            Spacer(Modifier.height(2.dp))
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
                            knownEvents = knownEvents,
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
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue") // Kotlin 2 lint false positive: the producer assigns value below.
private fun ChatImagePreview(fileName: String) {
    val context = LocalContext.current
    val images = remember(context) { ChatImageStore(context) }
    val preview by produceState<Pair<Boolean, Bitmap?>>(false to null, fileName) {
        value = true to images.thumbnail(fileName)
    }
    val bitmap = preview.second
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "日程图片预览",
            modifier = Modifier.sizeIn(maxWidth = 260.dp, maxHeight = 160.dp))
    } else {
        Text(if (preview.first) "图片预览不可用" else "正在加载图片…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ActionRow(
    event: ParsedEvent,
    knownEvents: List<CalendarEvent>,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    // 与已有日程（不含自身和全天日程）的时间重叠检测
    val conflicts = if (event.action == ParsedActionType.DELETE) emptyList()
    else knownEvents.filter {
        it.eventId != event.targetEventId && !it.allDay &&
            it.startMillis < event.endMillis && it.endMillis > event.startMillis
    }
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
            if (conflicts.isNotEmpty()) {
                val shown = conflicts.take(2).joinToString("、") {
                    "「${it.title}」${AppDateFormat.timeRange(it.startMillis, it.endMillis)}"
                }
                Text(
                    text = "⚠ 时间重叠：" + shown + if (conflicts.size > 2) " 等 ${conflicts.size} 个日程" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
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

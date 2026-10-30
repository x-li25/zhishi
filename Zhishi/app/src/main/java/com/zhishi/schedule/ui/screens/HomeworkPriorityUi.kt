package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.zhishi.schedule.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant

internal val HomeworkPriorityPurple = Color(0xFF4A238F)

@Composable
internal fun homeworkPriorityChipColors(priority: HomeworkPriority) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = if (priority == HomeworkPriority.NOT_REQUIRED) MaterialTheme.colorScheme.surfaceContainer else HomeworkPriorityPurple,
    selectedLabelColor = if (priority == HomeworkPriority.NOT_REQUIRED) MaterialTheme.colorScheme.onSurfaceVariant else Color.White)

/** Recompute at the threshold even without refreshing the school lists. No network requests. */
@Composable
internal fun rememberHomeworkNow(): Instant {
    val owner = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { now = Instant.now(); delay(30_000) }
        }
    }
    return now
}

@Composable
internal fun HomeworkPriorityMenu(item: CourseHomework, busy: Boolean, onSet: (CourseHomework, HomeworkPriority) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, enabled = !busy) { Icon(Icons.Outlined.MoreVert, "设置作业分类") }
        DropdownMenu(open, { open = false }) {
            HomeworkPriority.entries.forEach { mark ->
                DropdownMenuItem(text = { Text("设为${mark.label}") }, onClick = { open = false; onSet(item, mark) }, enabled = !busy)
            }
        }
    }
}

@Composable
internal fun HomeworkPrioritySelector(item: CourseHomework, busy: Boolean, onSet: (CourseHomework, HomeworkPriority) -> Unit) {
    val now = rememberHomeworkNow()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("本机分类", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HomeworkPriority.entries.forEach { mark ->
                FilterChip(item.priority(now) == mark, { onSet(item, mark) }, enabled = !busy, label = { Text(mark.label) },
                    colors = homeworkPriorityChipColors(mark))
            }
        }
        Text("截止72小时内或已逾期的需提交作业会自动列为紧急；“无需提交”不会自动升级，也不会修改学校记录。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun HomeworkPriorityBadge(priority: HomeworkPriority) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.small,
        color = when (priority) {
            HomeworkPriority.URGENT, HomeworkPriority.NORMAL -> HomeworkPriorityPurple
            HomeworkPriority.NOT_REQUIRED -> colors.surfaceContainer
        }) {
        Text(priority.label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
            color = when (priority) {
                HomeworkPriority.URGENT, HomeworkPriority.NORMAL -> Color.White
                HomeworkPriority.NOT_REQUIRED -> colors.onSurfaceVariant
            })
    }
}

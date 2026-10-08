package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.util.AppDateFormat

/**
 * 日程条目：完成勾选 + 紫色竖条 + 标题/地点 + 右侧起止时间。
 * 今天页和周视图共用；完成后划线并淡化，竖条变灰。
 */
@Composable
fun EventRow(
    event: CalendarEvent,
    completed: Boolean,
    onToggleCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = completed, onCheckedChange = { onToggleCompleted() })
        Box(
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .width(4.dp)
                .height(46.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (completed) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.primary
                )
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (completed) 0.5f else 1f)
        ) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textDecoration = if (completed) TextDecoration.LineThrough else null
            )
            event.location?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .alpha(if (completed) 0.5f else 1f)
                .padding(start = 8.dp)
        ) {
            if (event.allDay) {
                Text(
                    text = "全天",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Text(
                    text = AppDateFormat.time(event.startMillis),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = AppDateFormat.time(event.endMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 独立卡片包装，用于需要单独成卡的场景。 */
@Composable
fun EventCard(
    event: CalendarEvent,
    completed: Boolean,
    onToggleCompleted: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        EventRow(
            event = event,
            completed = completed,
            onToggleCompleted = onToggleCompleted,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

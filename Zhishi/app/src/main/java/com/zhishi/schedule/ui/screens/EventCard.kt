package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
 * 日程条目：紫色竖条 + 标题/地点 + 右侧起止时间。
 * 有清单的日程显示清单进度并可点进二级页；清单全部完成时标题划线、竖条变灰。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EventRow(
    event: CalendarEvent,
    checklistDone: Int,
    checklistTotal: Int,
    rating: Int?,
    onOpenDetail: (() -> Unit)?,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasChecklist = checklistTotal > 0
    val completed = hasChecklist && checklistDone == checklistTotal
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            // 单击进清单二级页（有清单时），长按弹出删除确认；长按有涟漪画面反馈
            .combinedClickable(
                onClick = { onOpenDetail?.invoke() },
                onLongClick = onLongPress
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
            if (hasChecklist) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "清单 $checklistDone/$checklistTotal" +
                            (rating?.let { "  " + "★".repeat(it) + "☆".repeat(5 - it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary
                    )
                    // 可点进二级页的提示箭头跟在“清单”文字右侧，不占右侧时间列的位置
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "查看清单",
                        tint = if (completed) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(16.dp)
                    )
                }
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
    checklistDone: Int,
    checklistTotal: Int,
    rating: Int?,
    onOpenDetail: (() -> Unit)?,
    onLongPress: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        EventRow(
            event = event,
            checklistDone = checklistDone,
            checklistTotal = checklistTotal,
            rating = rating,
            onOpenDetail = onOpenDetail,
            onLongPress = onLongPress,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

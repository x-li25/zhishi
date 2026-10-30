package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhishi.schedule.data.ChecklistStore
import com.zhishi.schedule.domain.AcademicCalendar
import com.zhishi.schedule.domain.CalendarEvent
import com.zhishi.schedule.domain.DaySchedule
import com.zhishi.schedule.util.AppDateFormat
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** 时间轴每小时的高度。 */
private val HourHeight = 52.dp
/** 左侧时间轴宽度。 */
private val TimeColumnWidth = 44.dp

/** 日程块的马卡龙配色（背景/文字），按事件 ID 稳定取色。 */
private data class BlockColor(val bg: Color, val fg: Color)

private val WeekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private val WeekBlockColors = listOf(
    BlockColor(Color(0xFFE7E4F8), Color(0xFF5B4BC4)),
    BlockColor(Color(0xFFD6F0EC), Color(0xFF0E7C72)),
    BlockColor(Color(0xFFFDEBD3), Color(0xFFB26A00)),
    BlockColor(Color(0xFFE6DFF6), Color(0xFF7C4BB0)),
    BlockColor(Color(0xFFDBEDFA), Color(0xFF1A72A8)),
    BlockColor(Color(0xFFFFF0C2), Color(0xFF8F6D00))
)

/** 教学周次、日期区间和翻周导航。 */
@Composable
internal fun WeekNavHeader(
    weekStart: LocalDate,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit
) {
    val weekEnd = weekStart.plusDays(6)
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                text = AcademicCalendar.label(weekStart),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (AcademicCalendar.weekNumber(weekStart) != null) {
                Text(
                    text = AcademicCalendar.termName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${AppDateFormat.shortDate(weekStart)} - ${AppDateFormat.shortDate(weekEnd)}",
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

/**
 * 周视图：课表式时间网格。左侧时间轴 + 7 天列，日程按起止时间
 * 定位成彩色块。点击任意色块查看完整详情，长按删除。
 */
@Composable
fun WeekScreen(
    state: AppUiState,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onCurrentWeek: () -> Unit,
    onOpenDetail: (CalendarEvent) -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        // 顶部：标题 + 翻周
        Text(
            text = "全周日程",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        WeekNavHeader(
            weekStart = state.weekStart,
            onPreviousWeek = onPreviousWeek,
            onNextWeek = onNextWeek,
            onCurrentWeek = onCurrentWeek
        )

        if (!state.calendarPermissionGranted) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
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
            return@Column
        }

        if (state.weekDays.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text("正在读取本周日程…", modifier = Modifier.padding(16.dp))
            }
            return@Column
        }

        // 全天日程单独用小条展示，不占网格
        val allDayEvents = state.weekDays.flatMap { day ->
            day.events.filter { it.allDay }.map { day.date to it }
        }
        if (allDayEvents.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                allDayEvents.forEach { (date, event) ->
                    Card(
                        onClick = { onOpenDetail(event) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "${AppDateFormat.shortDate(date)} 全天 · ${event.title}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        WeekGrid(
            days = state.weekDays,
            state = state,
            onOpenDetail = onOpenDetail,
            onDeleteEvent = onDeleteEvent
        )
    }
}

/** 网格本体：表头（周一~周日）+ 时间轴 + 7 列日程块。 */
@Composable
private fun WeekGrid(
    days: List<DaySchedule>,
    state: AppUiState,
    onOpenDetail: (CalendarEvent) -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit
) {
    val zoneId = days.firstOrNull()?.zoneId ?: ZoneId.systemDefault()
    val timedRanges = days.flatMap { day ->
        day.events.filterNot { it.allDay }.mapNotNull { event ->
            event.rangeOn(day.date, zoneId)?.let { day.date to it }
        }
    }

    fun minutesOfDay(millis: Long, date: LocalDate): Int {
        val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        if (millis >= dayEnd) return 24 * 60
        val time = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalTime()
        return time.hour * 60 + time.minute
    }

    // 默认显示 07:00–23:00 的时间轴，遇到更早/更晚的日程自动外扩。
    val prefStart = 7 * 60
    val prefEnd = 23 * 60
    val gridStartMin = timedRanges.minOfOrNull { minutesOfDay(it.second.startMillis, it.first) }
        ?.let { minOf(prefStart, it / 60 * 60) } ?: prefStart
    val gridEndMin = timedRanges.maxOfOrNull { minutesOfDay(it.second.endMillis, it.first) }
        ?.let { maxOf(prefEnd, (it + 59) / 60 * 60) } ?: prefEnd
    val safeStart = gridStartMin.coerceIn(0, 23 * 60)
    val safeEnd = gridEndMin.coerceIn(safeStart + 60, 24 * 60)
    val hourCount = (safeEnd - safeStart) / 60
    val totalHeight = HourHeight * hourCount
    val today = LocalDate.now()

    Column(modifier = Modifier.fillMaxWidth()) {
        // 表头：星期 + 日期，今天高亮
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(TimeColumnWidth))
            days.forEach { day ->
                val isToday = day.date == today
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = WeekdayLabels[day.date.dayOfWeek.value - 1],
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${day.date.monthValue}/${day.date.dayOfMonth}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        // 网格：横向小时刻度线 + 时间轴 + 日程块
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(totalHeight)
                .drawBehind {
                    val lineColor = Color(0x14000000)
                    val startX = TimeColumnWidth.toPx()
                    val step = HourHeight.toPx()
                    for (i in 0..hourCount) {
                        drawLine(
                            color = lineColor,
                            start = Offset(startX, i * step),
                            end = Offset(size.width, i * step),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 时间轴
                Box(
                    modifier = Modifier
                        .width(TimeColumnWidth)
                        .fillMaxHeight()
                ) {
                    for (i in 0 until hourCount) {
                        Text(
                            text = "%02d:00".format((safeStart / 60) + i),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.offset(y = HourHeight * i - 7.dp)
                        )
                    }
                }
                // 每天一列
                days.forEach { day ->
                    val isToday = day.date == today
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .drawBehind {
                                drawLine(
                                    color = Color(0x14000000),
                                    start = Offset(size.width, 0f),
                                    end = Offset(size.width, size.height),
                                    strokeWidth = 1.dp.toPx()
                                )
                            }
                            .background(
                                if (isToday) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                else Color.Transparent
                            )
                    ) {
                        day.events.filterNot { it.allDay }.forEach { event ->
                            WeekEventBlock(
                                event = event,
                                date = day.date,
                                zoneId = zoneId,
                                gridStartMin = safeStart,
                                state = state,
                                onOpenDetail = onOpenDetail,
                                onDeleteEvent = onDeleteEvent
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单个日程色块：按时间定位，高度随时长，显示标题和地点。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekEventBlock(
    event: CalendarEvent,
    date: LocalDate,
    zoneId: ZoneId,
    gridStartMin: Int,
    state: AppUiState,
    onOpenDetail: (CalendarEvent) -> Unit,
    onDeleteEvent: (CalendarEvent) -> Unit
) {
    val range = event.rangeOn(date, zoneId) ?: return
    val startTime = Instant.ofEpochMilli(range.startMillis).atZone(zoneId).toLocalTime()
    val endTime = Instant.ofEpochMilli(range.endMillis).atZone(zoneId).toLocalTime()
    val startMin = startTime.hour * 60 + startTime.minute
    val dayEndMillis = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val endMin = if (range.endMillis == dayEndMillis) 24 * 60 else endTime.hour * 60 + endTime.minute
    val topMin = (startMin - gridStartMin).coerceAtLeast(0)
    val heightMin = (endMin - startMin).coerceAtLeast(15)
    val top: Dp = HourHeight * (topMin / 60f)
    val height: Dp = HourHeight * (heightMin / 60f)

    val checklist = state.checklists[ChecklistStore.keyOf(event)].orEmpty()
    val completed = checklist.isNotEmpty() && checklist.all { it.done }
    val colors = WeekBlockColors[abs((event.eventId % WeekBlockColors.size).toInt())]

    Box(
        modifier = Modifier
            .offset(y = top)
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.bg)
            .combinedClickable(
                onClick = { onOpenDetail(event) },
                onLongClick = { onDeleteEvent(event) }
            )
            .alpha(if (completed) 0.5f else 1f)
            .padding(4.dp)
    ) {
        Column {
            Text(
                text = event.title,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.fg,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            if (heightMin >= 45) {
                event.location?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = colors.fg.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

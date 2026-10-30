package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhishi.schedule.domain.AcademicCalendar
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class AppFeature(val label: String, val icon: ImageVector) {
    HOMEWORK("我的作业", Icons.AutoMirrored.Outlined.Assignment),
    TIMETABLE("课表考试", Icons.Outlined.School),
    CLASSROOM("教室资源", Icons.Outlined.MeetingRoom),
    PENDING("待交文件", Icons.Outlined.FolderOpen),
    ACADEMIC("校历", Icons.Outlined.CalendarMonth),
    WEATHER("天气", Icons.Outlined.WbSunny),
    ASSISTANT("AI 助手", Icons.Outlined.SmartToy),
    WEEK("周日程", Icons.Outlined.DateRange),
    CLOUD("云朵角落", Icons.Outlined.Cloud),
    LIBRARY("图书馆预约", Icons.Outlined.LocalLibrary),
    SETTINGS("设置", Icons.Outlined.Settings),
    // Retained for restoring an older saved destination; its entry now lives in Settings.
    ACCOUNT("账号管理", Icons.Outlined.AccountCircle)
}

@Composable
fun FeaturesPanel(pendingCount: Int, onOpen: (AppFeature) -> Unit, homeworkCount: Int? = null) {
    val columns = if (LocalDensity.current.fontScale >= 1.3f) 3 else 5
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("全部功能", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppFeature.entries.filter { it != AppFeature.ACCOUNT }.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { feature ->
                            val count = when (feature) {
                                AppFeature.PENDING -> pendingCount
                                AppFeature.HOMEWORK -> homeworkCount ?: 0
                                else -> 0
                            }
                            Column(Modifier.weight(1f).clickable(role = androidx.compose.ui.semantics.Role.Button,
                                onClickLabel = "打开${feature.label}") { onOpen(feature) }.semantics {
                                    if (count > 0) stateDescription = if (feature == AppFeature.HOMEWORK) "$count 份未提交作业" else "$count 个待交文件"
                                }.padding(vertical = 8.dp, horizontal = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                BadgedBox(badge = { if (count > 0) Badge { Text(if (count > 99) "99+" else count.toString()) } }) {
                                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                        Icon(feature.icon, null, Modifier.size(44.dp).padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Text(feature.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                                    maxLines = 2, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
fun FeaturePage(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    FullScreenPageDialog(onClose) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
fun AcademicCalendarScreen(date: LocalDate, onOpenWeek: (LocalDate) -> Unit) {
    val format = DateTimeFormatter.ofPattern("M月d日")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(AcademicCalendar.termName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("周一至周日为一周，节假日不跳周。\n点击周次查看对应日程。", Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items((1..AcademicCalendar.weekCount).toList()) { number ->
            val week = AcademicCalendar.week(number)
            val selected = AcademicCalendar.weekNumber(date) == number
            Surface(onClick = { onOpenWeek(week.start) }, shape = RoundedCornerShape(16.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("第${number}周", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text("${week.start.format(format)} — ${week.end.format(format)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { Text("本科生寒假：2027年1月18日—2月21日\n教师、研究生寒假：2027年1月21日—2月17日",
            Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

package com.zhishi.schedule.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.domain.WeatherCodes
import com.zhishi.schedule.domain.WeatherReport
import com.zhishi.schedule.domain.WeatherState
import com.zhishi.schedule.domain.daySummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WeatherOverview(
    state: WeatherState,
    date: LocalDate,
    onRefresh: () -> Unit,
    onUseLocation: () -> Unit,
    onChooseCity: () -> Unit,
    expandDetails: Boolean = false
) {
    var expanded by rememberSaveable(date.toString()) { mutableStateOf(expandDetails) }
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    val today = LocalDate.now()
    val matchesDate = date == state.selectedDate
    val report = state.report?.takeIf { date == today && matchesDate && state.location?.samePlace(it.location) == true }
    val forecast = state.forecast?.takeIf { date != today && matchesDate && state.location?.samePlace(it.location) == true }
    val summary = remember(forecast, date) { forecast?.daySummary(date) }
    val loading = !matchesDate || state.loading
    val hasWeather = report != null || summary != null
    val stale = report?.let { !it.isFresh(System.currentTimeMillis()) } ?: forecast?.let {
        System.currentTimeMillis() - it.fetchedAt.toEpochMilli() !in 0 until WeatherReport.CACHE_MILLIS
    } ?: false
    val dataZone = report?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: forecast?.zoneId ?: ZoneId.systemDefault()
    val fetchedAt = report?.fetchedAtMillis?.let(Instant::ofEpochMilli) ?: forecast?.fetchedAt
    val rainInterval = report?.intervalSeconds?.takeIf { it > 0 && it % 60 == 0 }
    val forecastCode = forecast?.let { value -> value.hours.firstOrNull {
        it.time.atZone(value.zoneId).toLocalDate() == date
    }?.weatherCode }
    val caption = when {
        date == today -> "今日 · 当前天气"
        date.isBefore(today) -> "${date.monthValue}月${date.dayOfMonth}日 · 天气"
        else -> "${date.monthValue}月${date.dayOfMonth}日 · 天气预报"
    }
    val metrics = if (report != null) listOfNotNull(
        report.windSpeedMs?.let { WeatherMetric("风速", "${weatherNumber(it)} m/s") },
        report.humidityPercent?.let { WeatherMetric("湿度", "$it%") },
        if (report.precipitationMm != null && rainInterval != null)
            WeatherMetric("近${rainInterval / 60}分钟降水", "${weatherNumber(report.precipitationMm)} mm") else null
    ) else listOfNotNull(
        summary?.maxPrecipitationProbability?.let { WeatherMetric("最大小时降水概率", "$it%") },
        summary?.maxWindSpeedMs?.let { WeatherMetric("最大风速", "${weatherNumber(it)} m/s") }
    )

    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(
            colors.primaryContainer.copy(alpha = 0.65f), colors.surface, colors.secondaryContainer.copy(alpha = 0.3f)
        ))).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = state.location != null, onClickLabel = "切换天气城市", onClick = onChooseCity)
                    .padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(caption, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    if (state.location != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(state.location.name, modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                        }
                    } else Text("这一天的天气", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(12.dp))
                Surface(shape = CircleShape, color = colors.surface.copy(alpha = 0.75f)) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = onRefresh, enabled = state.location != null && matchesDate &&
                            !date.isBefore(today) && !date.isAfter(today.plusDays(15))) {
                            Icon(Icons.Outlined.Refresh, "刷新当天的天气", Modifier.size(20.dp))
                        }
                    }
                }
            }

            if (hasWeather) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (report != null) {
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(weatherNumber(report.temperatureC), modifier = Modifier.alignByBaseline(),
                                    style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Light)
                                Text("℃", modifier = Modifier.alignByBaseline(), style = MaterialTheme.typography.titleLarge)
                            }
                            Text(WeatherCodes.description(report.weatherCode) + report.apparentTemperatureC?.let {
                                " · 体感 ${weatherNumber(it)}℃"
                            }.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        } else if (summary != null) {
                            val range = if (summary.minTemperatureC != null && summary.maxTemperatureC != null)
                                "${weatherNumber(summary.minTemperatureC)}° / ${weatherNumber(summary.maxTemperatureC)}°" else "气温未知"
                            Text(range, style = if (range.length > 13) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Medium)
                            Text("最低 / 最高（℃）", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text(summary.descriptions.take(2).joinToString(" / ") + if (summary.descriptions.size > 2) "等" else "",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    WeatherGlyph(report?.weatherCode ?: forecastCode, report?.isDay, Modifier.size(64.dp),
                        mixed = summary?.descriptions?.size?.let { it > 1 } == true)
                }

                if (metrics.isNotEmpty()) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    metrics.forEach { metric ->
                        Surface(modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(14.dp),
                            color = colors.surface.copy(alpha = 0.72f)) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(metric.value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(metric.label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    WeatherGlyph(null, null, Modifier.size(52.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(when {
                            loading -> if (state.locating) "正在获取位置" else "正在查询天气"
                            state.location == null -> "给这一天添点天气"
                            else -> "当天暂无天气数据"
                        }, style = MaterialTheme.typography.titleMedium)
                        Text(if (state.location == null) "选择活动所在城市，查看天气与预报" else "天气会跟随上方日程日期切换",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }

            if (hasWeather && loading) Text(if (state.locating) "正在获取大致位置…" else "正在更新天气…",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (matchesDate) state.error?.let { WeatherNotice(it, isError = true) }
            if (stale) WeatherNotice("当前展示缓存数据，请刷新后参考")
            if (summary?.complete == false) WeatherNotice("部分时段或指标缺失，以上为已有预报")

            if (state.location == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onChooseCity) {
                        Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("选择城市")
                    }
                    TextButton(onClick = onUseLocation, enabled = !state.locating) { Text("使用当前位置") }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                    if (fetchedAt != null) Text("获取于 ${weatherTime(fetchedAt, dataZone)}",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    state.location?.let { location ->
                        Text(location.name + location.region.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (report != null) {
                        Text("数据时间 ${weatherTime(Instant.ofEpochMilli(report.dataTimeMillis), dataZone)}（${dataZone.id}）",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Text("当前天气为气象模型估算。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } else if (summary != null) {
                        Text(summary.descriptions.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                        Text("气温为当日逐小时预报范围；降水概率取当日各小时的最大值，不代表整日下雨概率。时区：${dataZone.id}。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (state.location != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onChooseCity) { Text("切换城市") }
                        TextButton(onClick = onUseLocation, enabled = !state.locating) { Text("使用当前位置") }
                    }
                }
            }
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(onClick = { uri.openUri("https://open-meteo.com/") }, contentPadding = PaddingValues(horizontal = 2.dp)) {
                    Text("Open-Meteo", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = { uri.openUri("https://www.geonames.org/") }, contentPadding = PaddingValues(horizontal = 2.dp)) {
                    Text("GeoNames", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(if (expanded) "收起" else "天气详情", style = MaterialTheme.typography.labelMedium)
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

private data class WeatherMetric(val label: String, val value: String)

@Composable
private fun WeatherGlyph(code: Int?, isDay: Boolean?, modifier: Modifier = Modifier, mixed: Boolean = false) {
    val icon: ImageVector = if (mixed) Icons.Outlined.WbCloudy else when (code) {
        0, 1 -> if (isDay == false) Icons.Outlined.NightsStay else Icons.Outlined.WbSunny
        2, 3 -> Icons.Outlined.Cloud
        45, 48 -> Icons.Outlined.BlurOn
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Outlined.Umbrella
        71, 73, 75, 77, 85, 86 -> Icons.Outlined.AcUnit
        95, 96, 99 -> Icons.Outlined.Thunderstorm
        else -> Icons.AutoMirrored.Outlined.HelpOutline
    }
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.padding(14.dp).fillMaxSize(), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun WeatherNotice(text: String, isError: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val tint = if (isError) colors.error else colors.onSurfaceVariant
    Surface(shape = RoundedCornerShape(12.dp), color = if (isError) colors.errorContainer.copy(alpha = 0.65f) else colors.surface.copy(alpha = 0.7f)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = tint)
            Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
        }
    }
}

private fun weatherNumber(value: Double): String = String.format(Locale.CHINA, "%.1f", value).removeSuffix(".0")
private fun weatherTime(value: Instant, zone: ZoneId): String = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA).withZone(zone).format(value)

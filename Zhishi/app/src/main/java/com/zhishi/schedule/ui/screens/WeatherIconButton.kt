package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.domain.WeatherCodes
import com.zhishi.schedule.domain.WeatherState
import java.time.LocalDate

@Composable
fun WeatherIconButton(state: WeatherState, date: LocalDate, onClick: () -> Unit) {
    val report = state.report?.takeIf { date == LocalDate.now() && state.selectedDate == date && state.location?.samePlace(it.location) == true }
    val code = report?.weatherCode ?: state.forecast?.takeIf {
        state.selectedDate == date && state.location?.samePlace(it.location) == true
    }?.let { forecast -> forecast.hours.firstOrNull { it.time.atZone(forecast.zoneId).toLocalDate() == date }?.weatherCode }
    val icon = when (code) {
        0, 1 -> if (report?.isDay == false) Icons.Outlined.NightsStay else Icons.Outlined.WbSunny
        2, 3 -> Icons.Outlined.Cloud
        45, 48 -> Icons.Outlined.BlurOn
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Icons.Outlined.Umbrella
        71, 73, 75, 77, 85, 86 -> Icons.Outlined.AcUnit
        95, 96, 99 -> Icons.Outlined.Thunderstorm
        else -> if (state.location == null) Icons.Outlined.AddLocationAlt else Icons.AutoMirrored.Outlined.HelpOutline
    }
    IconButton(onClick, modifier = Modifier.semantics { contentDescription = "查看当天的天气详情" }) {
        if (state.loading || state.locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(icon, "${WeatherCodes.description(code)}，点击查看天气详情", tint = MaterialTheme.colorScheme.primary)
    }
}

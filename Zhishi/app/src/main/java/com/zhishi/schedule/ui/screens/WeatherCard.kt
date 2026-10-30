package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.domain.WeatherLocation
import com.zhishi.schedule.domain.WeatherState
import java.time.LocalDate

@Composable
fun WeatherCard(
    state: WeatherState,
    onRefresh: () -> Unit,
    onUseLocation: () -> Unit,
    onSearch: (String) -> Unit,
    onSelectCity: (WeatherLocation) -> Unit,
    onClearSearch: () -> Unit,
    date: LocalDate = state.selectedDate,
    expandDetails: Boolean = false
) {
    var choosingCity by rememberSaveable { mutableStateOf(false) }
    WeatherOverview(
        state = state,
        date = date,
        onRefresh = onRefresh,
        onUseLocation = onUseLocation,
        onChooseCity = { choosingCity = true; onClearSearch() },
        expandDetails = expandDetails
    )
    if (choosingCity) {
        AlertDialog(
            onDismissRequest = { choosingCity = false; onClearSearch() },
            title = { Text("选择天气城市") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = state.searchQuery, onValueChange = onSearch,
                        label = { Text("城市名称") }, placeholder = { Text("例如：北京、上海或 London") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    when {
                        state.searching -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        state.searchError != null -> Text(state.searchError, color = MaterialTheme.colorScheme.error)
                        state.searchQuery.trim().length < 2 -> Text("输入至少两个字符，支持中文或拼音；请选择匹配的地区。")
                        state.searchResults.isEmpty() -> Text("未找到城市，可换用完整名称或拼音搜索。")
                    }
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(state.searchResults, key = { "${it.latitude}:${it.longitude}" }) { location ->
                            Column(Modifier.fillMaxWidth().clickable {
                                onSelectCity(location); choosingCity = false
                            }.padding(vertical = 12.dp)) {
                                Text(location.name, style = MaterialTheme.typography.titleSmall)
                                Text(location.region, style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingCity = false; onClearSearch() }) { Text("关闭") } }
        )
    }
}


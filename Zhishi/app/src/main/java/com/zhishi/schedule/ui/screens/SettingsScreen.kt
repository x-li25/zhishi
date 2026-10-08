package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.domain.GlmConfig
import com.zhishi.schedule.domain.SchedulePreferences
import java.time.LocalTime

@Composable
fun SettingsScreen(
    config: GlmConfig,
    preferences: SchedulePreferences,
    testingConnection: Boolean,
    onSaveConfig: (GlmConfig) -> Unit,
    onTestConnection: (GlmConfig) -> Unit,
    onSavePreferences: (SchedulePreferences) -> Unit
) {
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var jsonMode by remember(config) { mutableStateOf(config.responseJsonMode) }

    var minStudy by remember(preferences) { mutableStateOf(preferences.minStudyMinutes.toString()) }
    var buffer by remember(preferences) { mutableStateOf(preferences.bufferMinutes.toString()) }
    var studyStart by remember(preferences) { mutableStateOf(preferences.studyStart.toString()) }
    var studyEnd by remember(preferences) { mutableStateOf(preferences.studyEnd.toString()) }
    var lunchStart by remember(preferences) { mutableStateOf(preferences.lunchStart.toString()) }
    var lunchEnd by remember(preferences) { mutableStateOf(preferences.lunchEnd.toString()) }
    var prefError by remember { mutableStateOf<String?>(null) }

    fun currentConfig() = config.copy(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        responseJsonMode = jsonMode
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("API Key 仅保存在本机，使用 Android Keystore 加密。")
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("模型接口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型名称") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("请求 JSON 输出")
                            Text(
                                "大多数模型平台支持，开启后输出更稳定。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = jsonMode, onCheckedChange = { jsonMode = it })
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onTestConnection(currentConfig()) },
                            modifier = Modifier.weight(1f),
                            enabled = !testingConnection
                        ) {
                            if (testingConnection) {
                                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("测试连接")
                            }
                        }
                        Button(
                            onClick = { onSaveConfig(currentConfig()) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存")
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("排程偏好", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactField(studyStart, { studyStart = it }, "学习开始", Modifier.weight(1f))
                        CompactField(studyEnd, { studyEnd = it }, "学习结束", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactField(lunchStart, { lunchStart = it }, "午餐开始", Modifier.weight(1f))
                        CompactField(lunchEnd, { lunchEnd = it }, "午餐结束", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactField(minStudy, { minStudy = it }, "最短学习分钟", Modifier.weight(1f))
                        CompactField(buffer, { buffer = it }, "事件缓冲（分钟）", Modifier.weight(1f))
                    }
                    prefError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val sStart = parseTimeOrNull(studyStart)
                            val sEnd = parseTimeOrNull(studyEnd)
                            val lStart = parseTimeOrNull(lunchStart)
                            val lEnd = parseTimeOrNull(lunchEnd)
                            when {
                                sStart == null || sEnd == null || lStart == null || lEnd == null ->
                                    prefError = "时间格式应为 HH:mm，例如 08:30"
                                sStart >= sEnd ->
                                    prefError = "学习开始时间必须早于学习结束时间"
                                lStart >= lEnd ->
                                    prefError = "午餐开始时间必须早于午餐结束时间"
                                else -> {
                                    prefError = null
                                    onSavePreferences(
                                        preferences.copy(
                                            studyStart = sStart,
                                            studyEnd = sEnd,
                                            lunchStart = lStart,
                                            lunchEnd = lEnd,
                                            minStudyMinutes = minStudy.toIntOrNull()?.coerceAtLeast(10)
                                                ?: preferences.minStudyMinutes,
                                            bufferMinutes = buffer.toIntOrNull()?.coerceIn(0, 60)
                                                ?: preferences.bufferMinutes
                                        )
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存排程偏好")
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("当前策略", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("AI 只负责理解与提出操作，所有时间与日历变更都需要确认后在手机本地执行。")
                }
            }
        }
    }
}

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label, maxLines = 1) },
        singleLine = true
    )
}

private fun parseTimeOrNull(value: String): LocalTime? =
    runCatching { LocalTime.parse(value.trim()) }.getOrNull()

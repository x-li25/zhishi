package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.domain.GlmConfig
import com.zhishi.schedule.domain.ModelCatalogState

private enum class ModelPickerTarget { CHAT, IMAGE }

@Composable
internal fun ApiKeyManagementScreen(
    config: GlmConfig,
    testingConnection: Boolean,
    modelCatalog: ModelCatalogState,
    onSaveConfig: (GlmConfig) -> Unit,
    onTestConnection: (GlmConfig) -> Unit,
    onFetchModels: (GlmConfig) -> Unit,
    onModelCredentialsChanged: () -> Unit,
    connectionResult: String? = null
) {
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var visionModel by remember(config) { mutableStateOf(config.visionModel) }
    var pickerTarget by remember { mutableStateOf<ModelPickerTarget?>(null) }
    var visionExpanded by remember(config.visionModel) { mutableStateOf(config.visionModel.isNotBlank()) }
    val hasCredentials = baseUrl.isNotBlank() && apiKey.isNotBlank()

    LaunchedEffect(config.baseUrl, config.apiKey) {
        onModelCredentialsChanged()
    }

    // JSON 输出模式始终开启：关掉会导致回复解析失败，不再作为开关暴露
    fun currentConfig() = config.copy(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        visionModel = visionModel,
        responseJsonMode = true
    )

    fun openPicker(target: ModelPickerTarget) {
        pickerTarget = target
        if (!modelCatalog.loaded && !modelCatalog.loading) onFetchModels(currentConfig())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("模型接口", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { if (baseUrl != it) { onModelCredentialsChanged(); baseUrl = it } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        enabled = !testingConnection,
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { if (apiKey != it) { onModelCredentialsChanged(); apiKey = it } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") },
                        enabled = !testingConnection,
                        supportingText = { Text("仅在本机保存，使用系统密钥加密。") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { pickerTarget = ModelPickerTarget.CHAT; onFetchModels(currentConfig()) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hasCredentials && !modelCatalog.loading
                    ) {
                        if (modelCatalog.loading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  正在获取模型…")
                        } else {
                            Text(if (modelCatalog.loaded) "刷新模型列表" else "获取模型列表")
                        }
                    }
                    if (modelCatalog.loaded) {
                        Text(
                            if (modelCatalog.models.isEmpty()) "接口未返回可选模型，可手动填写模型名称。"
                            else "已获取 ${modelCatalog.models.size} 个模型，点击下方箭头选择。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    modelCatalog.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("对话模型") },
                        enabled = !testingConnection,
                        trailingIcon = {
                            IconButton(onClick = { openPicker(ModelPickerTarget.CHAT) }, enabled = hasCredentials) {
                                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "从列表选择对话模型")
                            }
                        },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton({ visionExpanded = !visionExpanded }) { Text(if (visionExpanded) "收起图片模型设置" else "单独设置图片模型（可选）") }
                    if (visionExpanded) OutlinedTextField(
                        value = visionModel,
                        onValueChange = { visionModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("图片日程识别模型（可选）") },
                        enabled = !testingConnection,
                        placeholder = { Text("跟随对话模型：$model") },
                        trailingIcon = {
                            IconButton(onClick = { openPicker(ModelPickerTarget.IMAGE) }, enabled = hasCredentials) {
                                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "从列表选择图片日程识别模型")
                            }
                        },
                        supportingText = { Text("留空则跟随对话模型；所选模型需支持图片输入。") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onTestConnection(currentConfig()) },
                            modifier = Modifier.weight(1f),
                            enabled = hasCredentials && !testingConnection
                        ) {
                            if (testingConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
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
                    connectionResult?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("连接成功")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                }
            }
        }


    }

    pickerTarget?.let { target ->
        ModelPickerDialog(
            target = target,
            catalog = modelCatalog,
            selectedId = if (target == ModelPickerTarget.CHAT) model else visionModel.ifBlank { model },
            onSelect = { selected ->
                if (target == ModelPickerTarget.CHAT) model = selected else visionModel = selected
                pickerTarget = null
            },
            onRefresh = { onFetchModels(currentConfig()) },
            onDismiss = { pickerTarget = null }
        )
    }
}

@Composable
private fun ModelPickerDialog(
    target: ModelPickerTarget,
    catalog: ModelCatalogState,
    selectedId: String,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember(target) { mutableStateOf("") }
    val filtered = remember(catalog.models, query) {
        catalog.models.filter { it.id.contains(query.trim(), ignoreCase = true) ||
            it.displayName?.contains(query.trim(), ignoreCase = true) == true }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target == ModelPickerTarget.CHAT) "选择对话模型" else "选择图片日程识别模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("搜索模型") }, singleLine = true
                )
                if (target == ModelPickerTarget.IMAGE) {
                    TextButton(onClick = { onSelect("") }) { Text("跟随当前对话模型") }
                    Text("请选择支持图片输入的模型。未标明能力时，请参考服务商说明。", style = MaterialTheme.typography.bodySmall)
                }
                when {
                    catalog.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在获取模型列表…")
                    }
                    catalog.error != null -> Text(catalog.error, color = MaterialTheme.colorScheme.error)
                    catalog.loaded && catalog.models.isEmpty() -> Text("接口未返回可选模型，可关闭此窗口后手动填写。")
                    catalog.loaded && filtered.isEmpty() -> Text("没有匹配的模型")
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(filtered, key = { it.id }) { option ->
                        val enabled = target != ModelPickerTarget.IMAGE || option.supportsImageInput != false
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = option.id == selectedId, enabled = enabled, role = Role.RadioButton,
                                onClick = { onSelect(option.id) }
                            ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = option.id == selectedId, onClick = null, enabled = enabled)
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(option.id, style = MaterialTheme.typography.bodyMedium,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                option.displayName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                when (option.supportsImageInput) {
                                    true -> Text("支持图片输入", style = MaterialTheme.typography.labelSmall)
                                    false -> if (target == ModelPickerTarget.IMAGE) Text("不支持图片输入", style = MaterialTheme.typography.labelSmall)
                                    null -> Unit
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onRefresh, enabled = !catalog.loading) { Text("刷新列表") } }
    )
}

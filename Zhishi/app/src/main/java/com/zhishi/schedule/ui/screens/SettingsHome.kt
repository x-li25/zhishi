package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhishi.schedule.domain.GlmConfig
import com.zhishi.schedule.domain.ModelCatalogState

private enum class SettingsSection(val title: String) { API("API Key 管理"), ACCOUNT("账号管理"), NOTIFICATIONS("提醒通知"), HELP("使用说明") }

@Composable
fun SettingsScreen(config: GlmConfig, testingConnection: Boolean, modelCatalog: ModelCatalogState,
    notificationGranted: Boolean, onSaveConfig: (GlmConfig) -> Unit, onTestConnection: (GlmConfig) -> Unit,
    onFetchModels: (GlmConfig) -> Unit, onModelCredentialsChanged: () -> Unit,
    onRequestNotificationPermission: () -> Unit, accountContent: @Composable () -> Unit = {}, connectionResult: String? = null) {
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column {
                    SettingsRow("API Key 管理", if (config.apiKey.isBlank()) "设置服务地址、密钥与模型" else "已配置 · ${config.model}", Icons.Outlined.Key) { section = SettingsSection.API }
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    SettingsRow("账号管理", "清华账号与本机登录", Icons.Outlined.AccountCircle) { section = SettingsSection.ACCOUNT }
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    SettingsRow("提醒通知", if (notificationGranted) "已开启" else "尚未开启", Icons.Outlined.Notifications) { section = SettingsSection.NOTIFICATIONS }
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                    SettingsRow("使用说明", "日程、图片识别与作业提交", Icons.Outlined.Info) { section = SettingsSection.HELP }
                }
            }
        }
    }
    section?.let { selected -> FeaturePage(selected.title, { section = null }) {
        when (selected) {
            SettingsSection.API -> ApiKeyManagementScreen(config, testingConnection, modelCatalog,
                onSaveConfig, onTestConnection, onFetchModels, onModelCredentialsChanged, connectionResult)
            SettingsSection.ACCOUNT -> accountContent()
            SettingsSection.NOTIFICATIONS -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (notificationGranted) "通知已开启" else "通知尚未开启", style = MaterialTheme.typography.titleMedium)
                Text("日程提醒由系统日历发送，请同时保留系统日历的通知权限。", style = MaterialTheme.typography.bodyMedium)
                if (!notificationGranted) Button(onRequestNotificationPermission) { Text("开启通知权限") }
            }
            SettingsSection.HELP -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { HelpText("安排日程", "在助手中描述安排，核对后确认写入手机日历；点击日程可查看完整详情。") }
                item { HelpText("图片识别", "先用手机识别文字，文字足够时直接理解日程，必要时再使用支持图片的模型。") }
                item { HelpText("作业与附件", "作业列表下拉刷新。提交时仅选择附件，核对文件与提交名后再确认上传。") }
                item { HelpText("本机分类", "无需提交、紧急和一般是本机标记；明确截止在72小时内的需提交作业自动列为紧急。") }
            }
        }
    } }
}

@Composable
fun AccountManagementScreen(studentId: String, error: String?, busy: Boolean, onLogout: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(studentId.ifBlank { "已通过清华账号验证" }, style = MaterialTheme.typography.headlineSmall) }
        item { Text("下次打开会自动验证并复用本机登录；需要学校验证时会显示认证页面。登录信息仅在本机加密保存。") }
        item { Button(onLogout, enabled = !busy) { Text("退出登录") } }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HelpText(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

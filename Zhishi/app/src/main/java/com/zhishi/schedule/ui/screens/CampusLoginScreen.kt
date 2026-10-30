package com.zhishi.schedule.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun CampusLoginScreen(state: CampusAuthState, model: CampusAuthViewModel, graduate: Boolean, pendingCount: Int,
    onGraduateChange: (Boolean) -> Unit) {
    var studentId by remember(state.studentId) { mutableStateOf(state.studentId) }
    // Passwords deliberately do not enter SavedState / Android view hierarchy restoration.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var otherMethods by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun submit() { focus.clearFocus(); model.login(studentId, password); password = "" }
    val busy = state.checking || state.browserVisible
    // Both idle and in-flight login render the same account form. SSO never opens
    // a second progress dialog or asks the user to advance between school services.
    val form: @Composable (CampusLoginProgress?, () -> Unit, () -> Unit) -> Unit = { progress, retry, verify ->
      Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 36.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            Text("知时", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("登录清华账号，开启今天", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(52.dp))
            TextField(studentId, { studentId = it }, modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
                placeholder = { Text("学号") }, leadingIcon = { Icon(Icons.Outlined.PersonOutline, null) }, singleLine = true,
                enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                colors = TextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedContainerColor = MaterialTheme.colorScheme.background))
            Spacer(Modifier.height(24.dp))
            TextField(password, { password = it }, modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
                placeholder = { Text("密码") }, leadingIcon = { Icon(Icons.Outlined.Lock, null) }, singleLine = true,
                enabled = !busy,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton({ showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showPassword) "隐藏密码" else "显示密码")
                } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!busy) submit() }),
                colors = TextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedContainerColor = MaterialTheme.colorScheme.background))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(!graduate, { onGraduateChange(false) }, label = { Text("本科生") }, enabled = !busy)
                FilterChip(graduate, { onGraduateChange(true) }, label = { Text("研究生") }, enabled = !busy)
            }
            Spacer(Modifier.height(20.dp))
            Button({ if (progress?.needsAttention == true) retry() else submit() }, enabled = !busy || progress?.needsAttention == true,
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().height(56.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                if (busy && progress?.needsAttention != true) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在登录", style = MaterialTheme.typography.titleMedium)
                } else Text(if (progress?.needsAttention == true) "重试登录" else "登录",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (busy) Text(progress?.message ?: "正在检查本机登录…", Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (progress?.needsAttention == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { Text(it, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
            if (busy) {
                if (progress?.needsAttention == true) TextButton(verify) { Text("完成学校验证") }
                TextButton(model::cancelBrowser) { Text(if (progress?.needsAttention == true) "修改账号密码" else "取消登录") }
            } else {
                TextButton({ otherMethods = !otherMethods }) { Text(if (otherMethods) "收起其他方式" else "其他登录方式") }
                if (otherMethods) {
                    TextButton(model::officialLogin) { Text("使用学校页面验证") }
                    TextButton({ model.restore(graduate) }) { Text("重新检查已有登录") }
                }
            }
            Spacer(Modifier.height(32.dp))
            Text("您的登录信息仅保存在本机，并经操作系统安全加密。登录时仅发送至清华官方认证页面，不发送给 AI。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            if (pendingCount > 0) Text("已保存 $pendingCount 个待提交文件，登录后可在作业中选择。", Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodySmall)
        }
      }
    }
    if (state.browserVisible) CampusLoginDialog(graduate, model::cancelBrowser, model::verified, credentials = state.credentials,
        onCredentialsRejected = model::credentialsRejected, showBrowserInitially = state.manualVerification,
        inlineContent = { progress, retry, verify -> form(progress, retry, verify) })
    else form(null, {}, {})
}

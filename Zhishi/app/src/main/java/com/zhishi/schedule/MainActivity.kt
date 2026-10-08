package com.zhishi.schedule

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhishi.schedule.ui.screens.AppViewModel
import com.zhishi.schedule.ui.screens.ChatScreen
import com.zhishi.schedule.ui.screens.EventDetailScreen
import com.zhishi.schedule.ui.screens.SettingsScreen
import com.zhishi.schedule.ui.screens.TodayScreen
import com.zhishi.schedule.ui.screens.WeekScreen
import com.zhishi.schedule.ui.theme.ZhishiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZhishiTheme {
                ZhishiApp()
            }
        }
    }
}

private enum class AppTab(val label: String) {
    TODAY("今天"),
    WEEK("周"),
    ASSISTANT("助手"),
    SETTINGS("设置")
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ZhishiApp(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.TODAY) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshCalendarPermission()
    }

    fun requestCalendarPermission() {
        calendarPermissionLauncher.launch(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        )
    }

    LaunchedEffect(state.error, state.notice) {
        val message = state.error ?: state.notice
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    AppTab.TODAY -> Icons.Outlined.CalendarMonth
                                    AppTab.WEEK -> Icons.Outlined.DateRange
                                    AppTab.ASSISTANT -> Icons.Outlined.SmartToy
                                    AppTab.SETTINGS -> Icons.Outlined.Settings
                                },
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        // adjustNothing 下窗口不随键盘缩放，底部导航栏会被键盘遮住；
        // 键盘弹出时不再为它预留底部空间，否则输入框和键盘之间会空出一大截
        val imeVisible = WindowInsets.isImeVisible
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = if (imeVisible) 0.dp else innerPadding.calculateBottomPadding()
                )
        ) {
            when (selectedTab) {
                AppTab.TODAY -> TodayScreen(
                    state = state,
                    onRefresh = viewModel::refreshDay,
                    onRequestCalendarPermission = ::requestCalendarPermission,
                    onPreviousDay = { viewModel.selectDate(state.selectedDate.minusDays(1)) },
                    onNextDay = { viewModel.selectDate(state.selectedDate.plusDays(1)) },
                    onOpenDetail = viewModel::openEventDetail,
                    onDeleteEvent = viewModel::requestDeleteEvent
                )
                AppTab.WEEK -> WeekScreen(
                    state = state,
                    onPreviousWeek = viewModel::previousWeek,
                    onNextWeek = viewModel::nextWeek,
                    onCurrentWeek = viewModel::currentWeek,
                    onOpenDetail = viewModel::openEventDetail,
                    onDeleteEvent = viewModel::requestDeleteEvent
                )
                AppTab.ASSISTANT -> ChatScreen(
                    state = state,
                    onTextChange = viewModel::updateText,
                    onSend = viewModel::sendChatMessage,
                    onClearChat = viewModel::clearChat,
                    onConfirmMessage = viewModel::confirmMessageActions,
                    onRemoveAction = viewModel::removeMessageAction,
                    onUpdateAction = viewModel::updateMessageAction
                )
                AppTab.SETTINGS -> SettingsScreen(
                    config = state.glmConfig,
                    testingConnection = state.testingConnection,
                    onSaveConfig = viewModel::saveGlmConfig,
                    onTestConnection = viewModel::testGlmConnection
                )
            }

            // 清单二级页：覆盖在当前页面上，仅带清单的日程可进入
            state.detailEvent?.let { event ->
                EventDetailScreen(
                    event = event,
                    items = viewModel.checklistOf(event),
                    rating = viewModel.ratingOf(event),
                    onToggleItem = { index -> viewModel.toggleChecklistItem(event, index) },
                    onRate = { stars -> viewModel.rateEvent(event, stars) },
                    onBack = viewModel::closeEventDetail
                )
            }

            // 长按日程后的删除确认框
            state.pendingDeleteEvent?.let { event ->
                AlertDialog(
                    onDismissRequest = viewModel::cancelDeleteEvent,
                    title = { Text("删除日程") },
                    text = {
                        Text("确定删除「${event.title}」吗？这会同时移除它的清单和自评分，且无法撤销。")
                    },
                    confirmButton = {
                        TextButton(onClick = viewModel::confirmDeleteEvent) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::cancelDeleteEvent) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}

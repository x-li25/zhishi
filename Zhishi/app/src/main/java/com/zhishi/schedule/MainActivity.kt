package com.zhishi.schedule

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhishi.schedule.ui.screens.AppViewModel
import com.zhishi.schedule.ui.screens.CaptureScreen
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
    CAPTURE("识别"),
    SETTINGS("设置")
}

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
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    AppTab.TODAY -> Icons.Outlined.CalendarMonth
                                    AppTab.WEEK -> Icons.Outlined.DateRange
                                    AppTab.CAPTURE -> Icons.Outlined.EditCalendar
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                AppTab.TODAY -> TodayScreen(
                    state = state,
                    onRefresh = viewModel::refreshDay,
                    onRequestCalendarPermission = ::requestCalendarPermission,
                    onPreviousDay = { viewModel.selectDate(state.selectedDate.minusDays(1)) },
                    onNextDay = { viewModel.selectDate(state.selectedDate.plusDays(1)) },
                    onToggleCompleted = viewModel::toggleCompleted,
                    onAddSuggestion = viewModel::createSuggestedEvent
                )
                AppTab.WEEK -> WeekScreen(
                    state = state,
                    onPreviousWeek = viewModel::previousWeek,
                    onNextWeek = viewModel::nextWeek,
                    onCurrentWeek = viewModel::currentWeek,
                    onToggleCompleted = viewModel::toggleCompleted
                )
                AppTab.CAPTURE -> CaptureScreen(
                    state = state,
                    onTextChange = viewModel::updateText,
                    onClear = viewModel::clearCapture,
                    onParse = viewModel::parseWithAi,
                    onUpdateParsedEvent = viewModel::updateParsedEvent,
                    onRemoveParsedEvent = viewModel::removeParsedEvent,
                    onConfirm = viewModel::confirmParsedEvents
                )
                AppTab.SETTINGS -> SettingsScreen(
                    config = state.glmConfig,
                    preferences = state.preferences,
                    testingConnection = state.testingConnection,
                    onSaveConfig = viewModel::saveGlmConfig,
                    onTestConnection = viewModel::testGlmConnection,
                    onSavePreferences = viewModel::savePreferences
                )
            }
        }
    }
}

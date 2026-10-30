package com.zhishi.schedule

import android.Manifest
import android.content.Intent
import androidx.activity.viewModels
import com.zhishi.schedule.data.SharedHomeworkIntent
import java.util.UUID
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.zhishi.schedule.ui.screens.AppViewModel
import com.zhishi.schedule.ui.screens.ChatScreen
import com.zhishi.schedule.ui.screens.EventDetailScreen
import com.zhishi.schedule.ui.screens.SettingsScreen
import com.zhishi.schedule.ui.screens.AccountManagementScreen
import com.zhishi.schedule.ui.screens.LibraryReservationScreen
import com.zhishi.schedule.ui.screens.ClassroomResourcesScreen
import com.zhishi.schedule.ui.screens.TodayScreen
import com.zhishi.schedule.ui.screens.WeekScreen
import com.zhishi.schedule.ui.screens.WeekEventDetailDialog
import com.zhishi.schedule.ui.screens.WeatherCard
import com.zhishi.schedule.ui.screens.WeatherViewModel
import com.zhishi.schedule.ui.screens.CampusViewModel
import com.zhishi.schedule.ui.screens.CampusSettings
import com.zhishi.schedule.ui.screens.HomeworkViewModel
import com.zhishi.schedule.ui.screens.HomeworkCard
import com.zhishi.schedule.ui.screens.HomeworkDialogs
import com.zhishi.schedule.data.CampusLoginTarget
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import com.zhishi.schedule.ui.screens.CampusAuthViewModel
import com.zhishi.schedule.ui.screens.CampusLoginScreen
import com.zhishi.schedule.ui.screens.FeaturesPanel
import com.zhishi.schedule.ui.screens.AppFeature
import com.zhishi.schedule.ui.screens.FeaturePage
import com.zhishi.schedule.ui.screens.AcademicCalendarScreen
import com.zhishi.schedule.ui.screens.DayCloudCorner
import com.zhishi.schedule.ui.screens.WeatherIconButton
import com.zhishi.schedule.ui.screens.unsubmittedCount
import com.zhishi.schedule.ui.theme.ZhishiTheme

class MainActivity : ComponentActivity() {
    private val homeworkModel: HomeworkViewModel by viewModels()
    private var shareEvent: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shareEvent = savedInstanceState?.getString("homework_share_event") ?: UUID.randomUUID().toString()
        receiveShare(intent)
        enableEdgeToEdge()
        setContent {
            ZhishiTheme {
                ZhishiApp(homeworkModel = homeworkModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareEvent = UUID.randomUUID().toString()
        receiveShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("homework_share_event", shareEvent)
        super.onSaveInstanceState(outState)
    }

    private fun receiveShare(shared: Intent) {
        if (!SharedHomeworkIntent.isShare(shared)) return
        val uris = runCatching { SharedHomeworkIntent.files(shared) }.getOrDefault(emptyList())
        homeworkModel.receiveSharedFiles(shareEvent ?: UUID.randomUUID().toString(), uris)
    }
}

private enum class AppTab(val label: String) {
    TODAY("今天"),
    WEEK("周"),
    ASSISTANT("助手"),
    // Kept only for restoring the tab saved by 0.9.20; now opens Today instead.
    SETTINGS("功能")
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ZhishiApp(viewModel: AppViewModel = viewModel(), weatherModel: WeatherViewModel = viewModel(), campusModel: CampusViewModel = viewModel(), homeworkModel: HomeworkViewModel = viewModel(), authModel: CampusAuthViewModel = viewModel()) {
    val auth by authModel.state.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val weather by weatherModel.state.collectAsStateWithLifecycle()
    val campus by campusModel.state.collectAsStateWithLifecycle()
    val homework by homeworkModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.TODAY) }
    var featurePage by rememberSaveable { mutableStateOf<AppFeature?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    fun openFeature(feature: AppFeature) {
        when (feature) {
            AppFeature.HOMEWORK -> homeworkModel.open()
            AppFeature.PENDING -> homeworkModel.openPending()
            AppFeature.ASSISTANT -> selectedTab = AppTab.ASSISTANT
            AppFeature.WEEK -> selectedTab = AppTab.WEEK
            else -> featurePage = feature
        }
    }

    LaunchedEffect(auth.authenticated) {
        if (auth.authenticated) {
            campusModel.refreshAfterLogin(showPreview = false, finishLogin = false)
            homeworkModel.refreshAfterLogin(showList = false, finishLogin = false)
        } else {
            weatherModel.onBackground()
            viewModel.stopGeneration()
            featurePage = null
            selectedTab = AppTab.TODAY
            confirmLogout = false
        }
    }

    fun refreshVerifiedService(target: CampusLoginTarget) {
        when (target) {
            CampusLoginTarget.HOMEWORK -> homeworkModel.refreshAfterLogin(showList = false, finishLogin = false)
            CampusLoginTarget.TIMETABLE -> campusModel.refreshAfterLogin(showPreview = false, finishLogin = false)
        }
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshCalendarPermission()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshNotificationPermission()
    }

    val weatherLocationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) weatherModel.useCurrentLocation() else weatherModel.locationDenied()
    }

    fun requestWeatherLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            weatherModel.useCurrentLocation()
        } else {
            weatherLocationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    fun requestCalendarPermission() {
        calendarPermissionLauncher.launch(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        )
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.refreshNotificationPermission()
        }
    }

    if (!auth.authenticated) {
        CampusLoginScreen(auth, authModel, campus.graduate, homework.pendingFiles.size) { campusModel.configure(graduate = it) }
        return
    }

    // 清单二级页打开时，系统返回键先关闭二级页而不是退出 App
    BackHandler(enabled = state.detailEvent != null) {
        viewModel.closeEventDetail()
    }

    // 回到前台时自动重读日历与权限，覆盖“在系统日历里改了日程再切回来”的场景
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshCalendarPermission()
        viewModel.refreshNotificationPermission()
        weatherModel.onForeground()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { weatherModel.onBackground() }

    LaunchedEffect(selectedTab, state.selectedDate) {
        weatherModel.selectDate(state.selectedDate)
    }

    LaunchedEffect(campus.importRevision) {
        if (campus.importRevision > 0) viewModel.refreshCalendarPermission()
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
                AppTab.entries.filter { it != AppTab.SETTINGS }.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab || (selectedTab == AppTab.SETTINGS && tab == AppTab.TODAY),
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
                                    AppTab.SETTINGS -> Icons.Outlined.CalendarMonth
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
                AppTab.TODAY, AppTab.SETTINGS -> TodayScreen(
                    state = state,
                    onRequestCalendarPermission = ::requestCalendarPermission,
                    onPreviousDay = { viewModel.selectDate(state.selectedDate.minusDays(1)) },
                    onNextDay = { viewModel.selectDate(state.selectedDate.plusDays(1)) },
                    onOpenDetail = viewModel::openWeekEventDetail,
                    onSelectDate = viewModel::selectDate,
                    onRetry = viewModel::refreshDay,
                    onArrange = { selectedTab = AppTab.ASSISTANT },
                    onDeleteEvent = viewModel::requestDeleteEvent,
                    weatherIcon = { WeatherIconButton(weather, state.selectedDate) { featurePage = AppFeature.WEATHER } },
                    featurePanel = { FeaturesPanel(homework.pendingFiles.size, ::openFeature, homework.unsubmittedCount()) }
                )
                AppTab.WEEK -> WeekScreen(
                    state = state,
                    onPreviousWeek = viewModel::previousWeek,
                    onNextWeek = viewModel::nextWeek,
                    onCurrentWeek = viewModel::currentWeek,
                    onOpenDetail = viewModel::openWeekEventDetail,
                    onDeleteEvent = viewModel::requestDeleteEvent
                )
                AppTab.ASSISTANT -> ChatScreen(
                    state = state,
                    onTextChange = viewModel::updateText,
                    onSelectImage = viewModel::selectImage,
                    onRemoveImage = viewModel::removeSelectedImage,
                    onSend = viewModel::sendChatMessage,
                    onStopGeneration = viewModel::stopGeneration,
                    onClearChat = viewModel::clearChat,
                    onConfirmMessage = viewModel::confirmMessageActions,
                    onRemoveAction = viewModel::removeMessageAction,
                    onUpdateAction = viewModel::updateMessageAction
                )
            }

            featurePage?.let { page -> FeaturePage(page.label, { featurePage = null }) {
                when (page) {
                AppFeature.SETTINGS -> SettingsScreen(
                    config = state.glmConfig,
                    testingConnection = state.testingConnection,
                    modelCatalog = state.modelCatalog,
                    notificationGranted = state.notificationPermissionGranted,
                    onSaveConfig = viewModel::saveGlmConfig,
                    onTestConnection = viewModel::testGlmConnection,
                    onFetchModels = viewModel::fetchModels,
                    onModelCredentialsChanged = viewModel::invalidateModelCatalog,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    connectionResult = state.connectionResult,
                    accountContent = { AccountManagementScreen(auth.studentId, auth.error, campus.busy || campus.clearing) { confirmLogout = true } }
                )
                AppFeature.TIMETABLE -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    CampusSettings(campus, campusModel, ::requestCalendarPermission,
                        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED,
                        onLoginSuccess = {
                            campusModel.closeLogin()
                            campusModel.openPreview()
                        }, onServiceVerified = ::refreshVerifiedService)
                }
                AppFeature.WEATHER -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    WeatherCard(state = weather, date = state.selectedDate,
                        onRefresh = { weatherModel.refresh(force = true) }, onUseLocation = ::requestWeatherLocation,
                        onSearch = weatherModel::searchCities, onSelectCity = weatherModel::selectCity,
                        onClearSearch = weatherModel::clearSearch, expandDetails = true)
                }
                AppFeature.ACADEMIC -> AcademicCalendarScreen(state.selectedDate) { start ->
                    viewModel.selectWeek(start); selectedTab = AppTab.WEEK; featurePage = null
                }
                AppFeature.CLOUD -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    DayCloudCorner(state.selectedDate)
                }
                AppFeature.LIBRARY -> LibraryReservationScreen()
                AppFeature.CLASSROOM -> ClassroomResourcesScreen(campus.graduate, ::refreshVerifiedService)
                AppFeature.ACCOUNT -> AccountManagementScreen(auth.studentId, auth.error, campus.busy || campus.clearing) { confirmLogout = true }
                else -> Unit
                }
            } }

            if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false },
                title = { Text("退出清华账号") }, text = { Text("将清除本机登录信息并返回登录页。日程和待提交文件会保留。") },
                confirmButton = { TextButton({ confirmLogout = false; campusModel.clearLogin() }) { Text("退出登录") } },
                dismissButton = { TextButton({ confirmLogout = false }) { Text("取消") } })

            HomeworkDialogs(homework, homeworkModel, graduate = campus.graduate, onLoginSuccess = {
                homeworkModel.closeLogin()
                homeworkModel.open()
            }, onServiceVerified = ::refreshVerifiedService)

            // 清单二级页：覆盖在当前页面上，仅带清单的日程可进入
            state.detailEvent?.let { event ->
                EventDetailScreen(
                    event = event,
                    items = viewModel.checklistOf(event),
                    onToggleItem = { index -> viewModel.toggleChecklistItem(event, index) },
                    onAddItem = { text -> viewModel.addChecklistItem(event, text) },
                    onRemoveItem = { index -> viewModel.removeChecklistItem(event, index) },
                    onBack = viewModel::closeEventDetail
                )
            }

            state.weekDetailEvent?.let { event ->
                WeekEventDetailDialog(
                    event = event,
                    checklist = viewModel.checklistOf(event),
                    saving = state.savingEventDetails,
                    error = state.eventDetailsError,
                    onSave = viewModel::saveWeekEventDetails,
                    onOpenChecklist = {
                        viewModel.closeWeekEventDetail()
                        viewModel.openEventDetail(event)
                    },
                    onDismiss = viewModel::closeWeekEventDetail
                )
            }

            // 长按日程后的删除确认框
            state.pendingDeleteEvent?.let { event ->
                AlertDialog(
                    onDismissRequest = viewModel::cancelDeleteEvent,
                    title = { Text("删除日程") },
                    text = {
                        Text("确定删除「${event.title}」吗？这会同时移除它的清单，且无法撤销。")
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

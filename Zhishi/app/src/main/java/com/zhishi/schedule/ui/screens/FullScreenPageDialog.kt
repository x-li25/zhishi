package com.zhishi.schedule.ui.screens

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Dialogs own their window: configure its insets instead of inheriting the activity's adjustNothing. */
@Composable
@Suppress("DEPRECATION") // Window compatibility for Android 8–14; safeDrawing also handles SDK 35 edge-to-edge.
internal fun FullScreenPageDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val background = MaterialTheme.colorScheme.background
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = background.toArgb()
                if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = background.luminance() > 0.5f
                    isAppearanceLightNavigationBars = background.luminance() > 0.5f
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), content = content)
        }
    }
}

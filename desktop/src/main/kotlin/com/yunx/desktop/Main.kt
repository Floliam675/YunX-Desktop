package com.yunx.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.yunx.desktop.ui.AboutScreen
import com.yunx.desktop.ui.AccountsScreen
import com.yunx.desktop.ui.CloudDriveScreen
import com.yunx.desktop.ui.DownloadScreen
import com.yunx.desktop.ui.ResolveScreen
import com.yunx.desktop.ui.SettingsScreen
import com.yunx.desktop.ui.YunxIcons
import kotlinx.coroutines.launch

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "YunX Desktop（云析 · 网盘解析下载）",
        state = rememberWindowState(width = 1240.dp, height = 780.dp),
    ) {
        AppRoot()
    }
}

private enum class Tab(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String) {
    RESOLVE(YunxIcons.Link, "解析"),
    DOWNLOAD(YunxIcons.Download, "下载"),
    CLOUD(YunxIcons.Cloud, "云盘"),
    ACCOUNTS(YunxIcons.Account, "账号"),
    SETTINGS(YunxIcons.Settings, "设置"),
    ABOUT(YunxIcons.About, "关于")
}

@Composable
fun AppRoot() {
    val services = remember { AppServices() }
    LaunchedEffect(Unit) {
        services.taskDao.markInterruptedAsPaused()
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.RESOLVE) }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Row(Modifier.padding(pad)) {
                NavigationRail(Modifier.fillMaxHeight()) {
                    Spacer(Modifier.height(12.dp))
                    Tab.entries.forEach { t ->
                        val selected = t == tab
                        NavigationRailItem(
                            selected = selected,
                            onClick = { tab = t },
                            icon = {
                                Icon(
                                    imageVector = t.icon,
                                    contentDescription = t.label,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
                Surface(Modifier.weight(1f).fillMaxHeight()) {
                    // 页面切换动画：按导航方向左右滑入 + 淡入淡出
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val forward = targetState.ordinal > initialState.ordinal
                            val dir = if (forward) 1 else -1
                            (slideInHorizontally(tween(260)) { w -> dir * w / 8 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(260)) { w -> -dir * w / 10 } + fadeOut(tween(160)))
                        },
                        label = "tab-content",
                    ) { current ->
                        when (current) {
                            Tab.RESOLVE -> ResolveScreen(services, snackbar, scope)
                            Tab.DOWNLOAD -> DownloadScreen(services, snackbar)
                            Tab.CLOUD -> CloudDriveScreen(services, snackbar)
                            Tab.ACCOUNTS -> AccountsScreen(services, snackbar)
                            Tab.SETTINGS -> SettingsScreen(services, snackbar)
                            Tab.ABOUT -> AboutScreen(snackbar)
                        }
                    }
                }
            }
        }
    }
}

package com.yunx.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.yunx.desktop.ui.AccountsScreen
import com.yunx.desktop.ui.CloudDriveScreen
import com.yunx.desktop.ui.DownloadScreen
import com.yunx.desktop.ui.ResolveScreen
import com.yunx.desktop.ui.SettingsScreen
import com.yunx.desktop.ui.warmUpJavaFX
import kotlinx.coroutines.launch

fun main() = application {
    warmUpJavaFX()   // 后台预热 JavaFX/WebKit，加快“网页登录”打开
    Window(
        onCloseRequest = ::exitApplication,
        title = "YunX Desktop（云析 · 网盘解析下载）",
        state = rememberWindowState(width = 1240.dp, height = 780.dp),
    ) {
        AppRoot()
    }
}

private enum class Tab(val symbol: String, val label: String) {
    RESOLVE("链", "解析"),
    DOWNLOAD("载", "下载"),
    CLOUD("盘", "云盘"),
    ACCOUNTS("号", "账号"),
    SETTINGS("设", "设置")
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
                                Box(
                                    Modifier
                                        .size(30.dp)
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) { Text(t.symbol, fontSize = 15.sp) }
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
                Surface(Modifier.weight(1f).fillMaxHeight()) {
                    when (tab) {
                        Tab.RESOLVE -> ResolveScreen(services, snackbar, scope)
                        Tab.DOWNLOAD -> DownloadScreen(services, snackbar)
                        Tab.CLOUD -> CloudDriveScreen(services, snackbar)
                        Tab.ACCOUNTS -> AccountsScreen(services, snackbar)
                        Tab.SETTINGS -> SettingsScreen(services, snackbar)
                    }
                }
            }
        }
    }
}

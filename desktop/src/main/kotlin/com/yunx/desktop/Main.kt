package com.yunx.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() = application {
    val services = remember { AppServices() }
    val windowState = rememberWindowState(size = initialWindowSize(services.settings))
    Window(
        onCloseRequest = {
            // 记住窗口尺寸，下次启动沿用
            services.settings.windowWidth = windowState.size.width.value.toInt()
            services.settings.windowHeight = windowState.size.height.value.toInt()
            exitApplication()
        },
        title = "云析",
        icon = remember { loadAppIcon()?.let { BitmapPainter(it) } },
        state = windowState,
    ) {
        AppRoot(
            services = services,
            // 确认框出现时把窗口提到最前并临时置顶：此时用户正在浏览器里登录，看不到 App
            onConfirmVisible = { visible ->
                runCatching {
                    window.isAlwaysOnTop = visible
                    if (visible) window.toFront()
                }
            },
        )
    }
}

/** 窗口/任务栏图标：原项目 YunX 的 launcher 图标（AGPL-3.0，随包分发）。 */
private fun loadAppIcon(): ImageBitmap? {
    val stream = AppServices::class.java.getResourceAsStream("/yunx_icon.png") ?: return null
    return runCatching {
        org.jetbrains.skia.Image.makeFromEncoded(stream.use { it.readBytes() }).toComposeImageBitmap()
    }.getOrNull()
}

/** 记忆的窗口尺寸；若超过当前屏幕可用尺寸（如高 DPI 小屏、或分辨率被改小）则按屏幕收敛，避免窗口超出屏幕。 */
@Composable
private fun initialWindowSize(settings: SettingsStore): DpSize {
    val density = LocalDensity.current.density
    val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
    val maxWidth = (screen.width / density * 0.92f).toInt()
    val maxHeight = (screen.height / density * 0.90f).toInt()
    return DpSize(
        settings.windowWidth.coerceAtMost(maxWidth).dp,
        settings.windowHeight.coerceAtMost(maxHeight).dp,
    )
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
fun AppRoot(services: AppServices, onConfirmVisible: (Boolean) -> Unit = {}) {
    LaunchedEffect(Unit) {
        services.taskDao.markInterruptedAsPaused()
    }
    // 启动后静默检查更新：只在发现更新的正式版本、且用户没忽略过该版本时才打扰（移植自上游更新检测）
    var updateInfo by remember { mutableStateOf<com.yunx.desktop.app.ReleaseInfo?>(null) }
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { com.yunx.desktop.app.UpdateChecker.fetchLatest() }
        if (r is com.yunx.desktop.app.UpdateResult.Success &&
            com.yunx.desktop.app.UpdateChecker.compareVersions(r.release.tagName, com.yunx.desktop.ui.APP_VERSION) > 0 &&
            r.release.tagName != services.settings.skippedVersion
        ) updateInfo = r.release
    }
    updateInfo?.let { rel ->
        com.yunx.desktop.ui.UpdateAvailableDialog(
            release = rel,
            currentVersion = com.yunx.desktop.ui.APP_VERSION,
            onOpen = { url -> runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } },
            onSkip = { tag -> services.settings.skippedVersion = tag; updateInfo = null },
            onDismiss = { updateInfo = null },
        )
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by remember {
        mutableStateOf(Tab.entries[services.settings.lastTab.coerceIn(0, Tab.entries.size - 1)])
    }
    LaunchedEffect(tab) { services.settings.lastTab = tab.ordinal }
    val dark = when (services.settings.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = com.yunx.desktop.ui.yunxScheme(
            dark = dark,
            accentArgb = services.settings.accentArgb,
            baseArgb = services.settings.baseArgb,
        ),
    ) {
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
                            Tab.ABOUT -> AboutScreen(services, snackbar)
                        }
                    }
                }
            }
        }
        // 网页登录识别到登录态后，先由用户决定：保存并关闭 / 继续登录
        val confirmPending = services.loginConfirm.pending.value
        val confirmDecision = services.loginConfirm.decision.value
        val confirmVisible = confirmPending != null && confirmDecision == null
        LaunchedEffect(confirmVisible) { onConfirmVisible(confirmVisible) }
        if (confirmVisible) {
            AlertDialog(
                onDismissRequest = { /* 必须二选一 */ },
                title = { Text("已识别到登录态") },
                text = {
                    Text(
                        confirmPending!!.title + "：检测到已登录。要保存并关闭登录窗口吗？\n" +
                            "选择「继续登录」则不保存，浏览器窗口保持打开。"
                    )
                },
                confirmButton = { Button(onClick = { services.loginConfirm.answer(true) }) { Text("保存并关闭") } },
                dismissButton = { TextButton(onClick = { services.loginConfirm.answer(false) }) { Text("继续登录") } },
            )
        }
    }
}

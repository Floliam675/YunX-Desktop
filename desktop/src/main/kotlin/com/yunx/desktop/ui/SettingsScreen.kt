package com.yunx.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.desktop.AppServices
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(services: AppServices, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val tasks by services.taskDao.observeAll().collectAsState(initial = emptyList())
    var maxConc by remember { mutableIntStateOf(services.settings.maxConcurrent) }
    var retry by remember { mutableIntStateOf(services.settings.retryCount) }
    var speedMb by remember { mutableIntStateOf((services.settings.speedLimitBytes / 1024 / 1024).toInt()) }
    var dir by remember { mutableStateOf(services.settings.downloadDir) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("外观", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                        FilterChip(
                            selected = services.settings.theme == value,
                            onClick = { services.settings.setTheme(value) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // 自由调节主题色与背景色（滑杆任意调，实时生效）
        ColorAdjustCard(
            title = "主题色（自由调节）",
            hint = "调色相 / 饱和度 / 明度，按钮与高亮色实时跟随；正文对比度自动保护",
            current = services.settings.accentArgb?.let { Color(it) } ?: defaultPrimary(darkMode(services)),
            onPick = { services.settings.setAccent(it?.toArgb()) },
        )
        Spacer(Modifier.height(8.dp))
        ColorAdjustCard(
            title = "背景色（自由调节）",
            hint = "改整个界面底色（含各级卡片），前景色按对比度自动取深/浅",
            current = services.settings.baseArgb?.let { Color(it) } ?: defaultBackground(darkMode(services)),
            onPick = { services.settings.setBaseColor(it?.toArgb()) },
        )
        Spacer(Modifier.height(8.dp))

        // 各网盘分别调节分片线程数（默认：迅雷 8，其余 32）
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("各网盘下载线程数", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text("不同网盘对并发敏感度不同，可分别调节（新建任务时生效）",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                PLATFORM_THREADS.forEach { (label, key) ->
                    var value by remember(key) { mutableIntStateOf(services.settings.threadsFor(key)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = value.toFloat(),
                            onValueChange = { v -> value = v.toInt().coerceIn(1, 64); services.settings.setThreads(key, value) },
                            valueRange = 1f..64f,
                            steps = 62,
                            modifier = Modifier.weight(1f),
                        )
                        Text(value.toString(), Modifier.width(34.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        SettingSlider("最大同时下载任务数", maxConc, 1, 10) { v -> maxConc = v; services.settings.maxConcurrent = v }
        SettingSlider("失败自动重试次数", retry, 0, 10) { v -> retry = v; services.settings.retryCount = v }
        SettingSlider("全局下载限速（MB/s，0=不限）", speedMb, 0, 100) { v ->
            speedMb = v
            services.settings.speedLimitBytes = v * 1024L * 1024L
        }

        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("下载保存目录", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(if (dir == null) "默认（系统「下载」文件夹，跟随系统设置）" else "已自定义",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(services.effectiveDownloadDir().absolutePath, Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = {
                        val chooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = "选择下载目录"
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val f = chooser.selectedFile
                            services.settings.downloadDir = f.absolutePath
                            dir = f.absolutePath
                        }
                    }) { Text("选择…") }
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = { services.settings.downloadDir = null; dir = null }) { Text("默认") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("任务与数据", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { runCatching { Desktop.getDesktop().open(AppServices.dataDir()) } }) {
                        Text("打开数据目录")
                    }
                    OutlinedButton(onClick = { runCatching { Desktop.getDesktop().open(AppServices.downloadDir()) } }) {
                        Text("打开下载目录")
                    }
                    Button(onClick = {
                        scope.launch {
                            tasks.filter { it.status == 3 }.forEach { services.downloadManager.remove(it.id, deleteLocal = false) }
                            snackbar.showSnackbar("已清除" + tasks.count { it.status == 3 } + " 个已完成任务")
                        }
                    }) { Text("清除已完成") }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("YunX Desktop（云析桌面版）—— 由 Android 开源应用 YunX（AGPL-3.0，github.com/CYQawa/YunX）移植。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingSlider(title: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f))
                Text(value.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.toInt().coerceIn(min, max)) },
                valueRange = min.toFloat()..max.toFloat(),
                steps = (max - min - 1).coerceAtLeast(0),
            )
        }
    }
}

/** 设置页「各网盘下载线程数」的条目：key 为空 = 通用（未知来源） */
private val PLATFORM_THREADS = listOf(
    "通用" to "",
    "夸克" to com.yunx.app.data.download.DownloadPlatform.QUARK,
    "UC" to com.yunx.app.data.download.DownloadPlatform.UC,
    "迅雷" to com.yunx.app.data.download.DownloadPlatform.XUNLEI,
    "百度" to com.yunx.app.data.download.DownloadPlatform.BAIDU,
    "139" to com.yunx.app.data.download.DownloadPlatform.C139,
    "123" to com.yunx.app.data.download.DownloadPlatform.PAN123,
)

/** 当前是否深色（用于取默认基准色）：跟随系统时读系统主题 */
@Composable
private fun darkMode(services: AppServices): Boolean = when (services.settings.theme) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

/**
 * 颜色自由调节卡片：色相 / 饱和度 / 明度 三根滑杆，任意调色；「重置」回到默认。
 * 拖动时立即回调 onPick，配色实时生效（不点确定）。
 */
@Composable
private fun ColorAdjustCard(title: String, hint: String, current: Color, onPick: (Color?) -> Unit) {
    val init = remember { rgbToHsv(current) }
    var h by remember { mutableFloatStateOf(init[0]) }
    var s by remember { mutableFloatStateOf(init[1]) }
    var v by remember { mutableFloatStateOf(init[2]) }
    val color = hsvToColor(h, s, v)

    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).background(color, RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(10.dp))
                Text(hexOf(color), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = {
                    h = init[0]; s = init[1]; v = init[2]
                    onPick(null)                       // 回到 Material3 默认
                }) { Text("重置") }
            }
            listOf(
                Triple("色相", 0, 360f),
                Triple("饱和度", 1, 100f),
                Triple("明度", 2, 100f),
            ).forEach { (label, idx, max) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.width(52.dp), style = MaterialTheme.typography.bodyMedium)
                    val value = when (idx) { 0 -> h; 1 -> s * 100f; else -> v * 100f }
                    Slider(
                        value = value,
                        onValueChange = { nv ->
                            when (idx) { 0 -> h = nv; 1 -> s = nv / 100f; else -> v = nv / 100f }
                            onPick(hsvToColor(h, s, v))   // 实时应用
                        },
                        valueRange = 0f..max,
                        modifier = Modifier.weight(1f),
                    )
                    Text(value.toInt().toString(), Modifier.width(38.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
package com.yunx.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
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

        var themeMenu by remember { mutableStateOf(false) }   // 二级菜单：整个主题设置都在里面

        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("外观", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                val dark = darkMode(services)
                val accent = services.settings.accentArgb?.let { v -> Color(v.toLong()) } ?: defaultPrimary(dark)
                val bg = services.settings.baseArgb?.let { v -> Color(v.toLong()) } ?: defaultBackground(dark)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (services.settings.theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.size(18.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(18.dp).clip(CircleShape).background(bg)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { themeMenu = true }) { Text("设置主题…") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // 二级菜单：深浅模式 + 预选色 + 自由调节，整个主题设置都在这里，改完点「完成」即可
        if (themeMenu) {
            ThemeSettingsDialog(
                mode = services.settings.theme,
                onMode = { services.settings.setTheme(it) },
                accent = services.settings.accentArgb?.let { v -> Color(v.toLong()) },
                base = services.settings.baseArgb?.let { v -> Color(v.toLong()) },
                dark = darkMode(services),
                onAccent = { services.settings.setAccent(it?.toArgb()) },
                onBase = { services.settings.setBaseColor(it?.toArgb()) },
                onClose = { themeMenu = false },
            )
        }

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

/** 主题色预选（null 不参与：主题色总有一个值） */
private val ACCENT_PRESETS: List<Pair<String, Color?>> = listOf(
    "紫" to Color(0xFF8B7BF7),
    "蓝" to Color(0xFF4C8DFF),
    "青" to Color(0xFF2BC4C4),
    "绿" to Color(0xFF3FBF6F),
    "黄" to Color(0xFFE0A93B),
    "橙" to Color(0xFFFF8A3D),
    "红" to Color(0xFFEF5350),
    "粉" to Color(0xFFE86BA8),
    "灰" to Color(0xFF9AA0A6),
)

/** 背景色预选（第一个是「默认」= 交回 Material3） */
private val BASE_PRESETS: List<Pair<String, Color?>> = listOf(
    "默认" to null,
    "纯黑" to Color(0xFF000000),
    "深灰" to Color(0xFF1B1B1F),
    "石墨" to Color(0xFF2B2B33),
    "深蓝" to Color(0xFF1E2430),
    "米白" to Color(0xFFF5F1E8),
    "浅灰" to Color(0xFFF2F2F5),
    "冷白" to Color(0xFFEEF2F7),
)

/** 一组预选色点（点一下即应用）；color == null 的条目渲染成「默认」文字按钮 */
@Composable
private fun PresetDots(presets: List<Pair<String, Color?>>, current: Color?, onPick: (Color?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        presets.forEach { (_, color) ->
            if (color == null) {
                OutlinedButton(
                    onClick = { onPick(null) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp),
                    enabled = current != null,
                ) { Text("默认", style = MaterialTheme.typography.labelSmall) }
            } else {
                val selected = current != null && roughlyEqual(color, current)
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable { onPick(color) },
                )
            }
        }
    }
}

/**
 * 二级菜单：**整个主题设置**都收在这里 —— 深浅模式、主题色、背景色（预选 + 自由调节）。
 * 所有改动实时生效（不点确定也生效），「完成」只负责关闭；「恢复默认」清空自定义配色。
 */
@Composable
private fun ThemeSettingsDialog(
    mode: String,
    onMode: (String) -> Unit,
    accent: Color?,
    base: Color?,
    dark: Boolean,
    onAccent: (Color?) -> Unit,
    onBase: (Color?) -> Unit,
    onClose: () -> Unit,
) {
    val defAccent = defaultPrimary(dark)
    val defBase = defaultBackground(dark)
    val accentNow = accent ?: defAccent
    val baseNow = base ?: defBase

    // 滑杆自己持有 HSV 状态（不随设置回调重置），只有在点预选色/重置时才同步过去
    val ai = remember { rgbToHsv(accentNow) }
    var ah by remember { mutableFloatStateOf(ai[0]) }
    var asat by remember { mutableFloatStateOf(ai[1]) }
    var av by remember { mutableFloatStateOf(ai[2]) }
    val bi = remember { rgbToHsv(baseNow) }
    var bh by remember { mutableFloatStateOf(bi[0]) }
    var bsat by remember { mutableFloatStateOf(bi[1]) }
    var bv by remember { mutableFloatStateOf(bi[2]) }

    fun syncAccent(c: Color) {
        val h = rgbToHsv(c); ah = h[0]; asat = h[1]; av = h[2]
    }
    fun syncBase(c: Color) {
        val h = rgbToHsv(c); bh = h[0]; bsat = h[1]; bv = h[2]
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("主题设置") },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                // ---------- 深浅模式 ----------
                Text("深浅模式", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
                        FilterChip(selected = mode == value, onClick = { onMode(value) }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(14.dp))

                // ---------- 主题色 ----------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("主题色", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
                    PresetDots(ACCENT_PRESETS, accent) { c -> c?.let { syncAccent(it) }; onAccent(c) }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(hsvToColor(ah, asat, av)))
                    Spacer(Modifier.width(10.dp))
                    Text(hexOf(hsvToColor(ah, asat, av)), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { syncAccent(defAccent); onAccent(null) }) { Text("重置") }
                }
                HsvSliders(ah, asat, av) { h, s, v -> ah = h; asat = s; av = v; onAccent(hsvToColor(h, s, v)) }
                Spacer(Modifier.height(14.dp))

                // ---------- 背景色 ----------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("背景色", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(64.dp))
                    PresetDots(BASE_PRESETS, base) { c -> c?.let { syncBase(it) }; onBase(c) }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(hsvToColor(bh, bsat, bv))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text(hexOf(hsvToColor(bh, bsat, bv)), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { syncBase(defBase); onBase(null) }) { Text("重置") }
                }
                HsvSliders(bh, bsat, bv) { h, s, v -> bh = h; bsat = s; bv = v; onBase(hsvToColor(h, s, v)) }

                Spacer(Modifier.height(6.dp))
                Text("提示：正文与图标颜色会按背景亮度自动取深/浅，任意配色都保证可读",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = {
                syncAccent(defAccent); syncBase(defBase)
                onAccent(null); onBase(null)
            }) { Text("恢复默认") }
        },
    )
}
/** 色相 / 饱和度 / 明度 三根滑杆（0..360 / 0..100 / 0..100） */
@Composable
private fun HsvSliders(h: Float, s: Float, v: Float, onChange: (Float, Float, Float) -> Unit) {
    Column {
        listOf(Triple("色相", 0, 360f), Triple("饱和度", 1, 100f), Triple("明度", 2, 100f)).forEach { (label, idx, max) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(52.dp), style = MaterialTheme.typography.bodyMedium)
                val value = when (idx) { 0 -> h; 1 -> s * 100f; else -> v * 100f }
                Slider(
                    value = value,
                    onValueChange = { nv ->
                        when (idx) {
                            0 -> onChange(nv, s, v)
                            1 -> onChange(h, nv / 100f, v)
                            else -> onChange(h, s, nv / 100f)
                        }
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

package com.yunx.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var threads by remember { mutableIntStateOf(services.settings.threadsFor("")) }
    var maxConc by remember { mutableIntStateOf(services.settings.maxConcurrent) }
    var retry by remember { mutableIntStateOf(services.settings.retryCount) }
    var speedMb by remember { mutableIntStateOf((services.settings.speedLimitBytes / 1024 / 1024).toInt()) }
    var dir by remember { mutableStateOf(services.settings.downloadDir) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        SettingSlider("通用分片线程数", threads, 1, 64) { v -> threads = v; services.settings.setThreads("", v) }
        SettingSlider("最大同时下载任务数", maxConc, 1, 10) { v -> maxConc = v; services.settings.maxConcurrent = v }
        SettingSlider("失败自动重试次数", retry, 0, 10) { v -> retry = v; services.settings.retryCount = v }
        SettingSlider("全局下载限速（MB/s，0=不限）", speedMb, 0, 100) { v ->
            speedMb = v
            services.settings.speedLimitBytes = v * 1024L * 1024L
        }

        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("下载保存目录", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dir ?: AppServices.downloadDir().absolutePath, Modifier.weight(1f),
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


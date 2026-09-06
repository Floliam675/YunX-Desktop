package com.yunx.desktop.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.desktop.AppServices
import com.yunx.desktop.app.formatSize
import com.yunx.desktop.app.formatSpeedText
import java.awt.Desktop
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun DownloadScreen(services: AppServices, snackbar: SnackbarHostState) {
    val tasks by services.taskDao.observeAll().collectAsState(initial = emptyList())
    val stats by services.downloadManager.stats.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("下载管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text("共 " + tasks.size + " 个任务", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = {
                scope.launch {
                    tasks.filter { it.status == DownloadTaskEntity.STATUS_PAUSED || it.status == DownloadTaskEntity.STATUS_FAILED }
                        .forEach { services.downloadManager.start(it.id) }
                }
            }) { Text("全部恢复") }
        }
        Spacer(Modifier.height(12.dp))
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无下载任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks, key = { it.id }) { t ->
                    DownloadTaskRow(services, t, stats[t.id]?.speed, snackbar)
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(
    services: AppServices,
    t: DownloadTaskEntity,
    speedBps: Long?,
    snackbar: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val active = t.status == DownloadTaskEntity.STATUS_DOWNLOADING || t.status == DownloadTaskEntity.STATUS_PENDING
    val fraction = if (t.totalSize > 0) (t.downloadedSize.toFloat() / t.totalSize).coerceIn(0f, 1f) else 0f
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.fileName, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(statusText(t, speedBps), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (t.status == DownloadTaskEntity.STATUS_COMPLETED) {
                    Button(onClick = {
                        runCatching { Desktop.getDesktop().open(File(t.savePath).parentFile ?: File(t.savePath)) }
                    }) { Text("打开目录") }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { scope.launch { services.downloadManager.remove(t.id, deleteLocal = false) } }) { Text("删除") }
                } else {
                    when (t.status) {
                        DownloadTaskEntity.STATUS_DOWNLOADING, DownloadTaskEntity.STATUS_PENDING ->
                            TextButton(onClick = { services.downloadManager.pause(t.id) }) { Text("暂停") }
                        else ->
                            TextButton(onClick = { services.downloadManager.start(t.id) }) { Text("继续") }
                    }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { scope.launch { services.downloadManager.remove(t.id, deleteLocal = false) } }) { Text("删除") }
                }
            }
            if (active) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (t.status == DownloadTaskEntity.STATUS_FAILED && t.errorMsg.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(t.errorMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3)
            }
        }
    }
}

private fun statusText(t: DownloadTaskEntity, speedBps: Long?): String = when (t.status) {
    DownloadTaskEntity.STATUS_DOWNLOADING -> {
        val sp = speedBps ?: 0L
        val progress = if (t.totalSize > 0)
            "下载中 " + formatSize(t.downloadedSize) + " / " + formatSize(t.totalSize)
        else "下载中 " + formatSize(t.downloadedSize)
        progress + (if (sp > 0) "（" + formatSpeedText(sp) + "）" else "")
    }
    DownloadTaskEntity.STATUS_PENDING -> "等待中"
    DownloadTaskEntity.STATUS_PAUSED -> "已暂停（" + formatSize(t.downloadedSize) + "）"
    DownloadTaskEntity.STATUS_COMPLETED -> "已完成 → " + t.savePath
    DownloadTaskEntity.STATUS_FAILED -> "失败"
    else -> "未知"
}


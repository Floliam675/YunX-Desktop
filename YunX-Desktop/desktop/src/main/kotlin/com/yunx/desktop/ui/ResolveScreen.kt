package com.yunx.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.network.SharePlatform
import com.yunx.desktop.AppServices
import com.yunx.desktop.app.DriveId
import com.yunx.desktop.app.ResolvedDownload
import com.yunx.desktop.app.formatSize
import com.yunx.desktop.app.formatSpeedText
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/* ======================= 解析页 ======================= */

@Composable
fun ResolveScreen(services: AppServices, snackbar: SnackbarHostState, scope: CoroutineScope) {
    val r = services.resolver
    var link by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("分享链接解析", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        r.error?.let { e ->
            ErrorBanner(e) { r.consumeError() }
            Spacer(Modifier.height(8.dp))
        }
        r.message?.let { m ->
            InfoBanner(m) { r.consumeMessage() }
            Spacer(Modifier.height(8.dp))
        }

        if (r.share == null) {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("粘贴网盘分享链接") },
                supportingText = { Text("支持：夸克 pan.quark.cn / UC drive.uc.cn / 迅雷 pan.xunlei.com / 百度 pan.baidu.com / 139 yun.139.com / 123pan") },
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    modifier = Modifier.width(200.dp),
                    label = { Text("提取码（可选）") },
                )
                Spacer(Modifier.width(12.dp))
                Button(onClick = { r.resolve(link, pwd.ifBlank { null }) }, enabled = link.isNotBlank() && !r.loading) {
                    if (r.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("解析")
                }
            }
        } else {
            // ---------- 文件浏览 ----------
            val s = r.share!!
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { r.reset(); link = "" }) { Text("← 重新输入") }
                if (s.currentDir.isNotBlank()) {
                    TextButton(onClick = { r.goBackToRoot() }) { Text("返回根目录") }
                }
                Spacer(Modifier.weight(1f))
                PlatformChip(s.platform)
            }
            Spacer(Modifier.height(4.dp))
            Text(s.session.title, style = MaterialTheme.typography.titleMedium)
            Text("共 " + s.files.size + " 项", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            if (r.loading) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Card(Modifier.fillMaxSize(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(s.files, key = { it.fid }) { f ->
                            FileRow(
                                file = f,
                                loading = r.downloading,
                                onClick = {
                                    if (f.isdir) r.openFolder(f)
                                    else scope.launch {
                                        val dl = r.getDownloadFor(f)
                                        if (dl != null) {
                                            enqueue(services, dl)
                                            snackbar.showSnackbar("已加入下载：" + dl.fileName)
                                        }
                                    }
                                },
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

private suspend fun enqueue(services: AppServices, dl: ResolvedDownload) {
    services.downloadManager.enqueue(
        url = dl.url,
        fileName = dl.fileName,
        headers = dl.headers,
        size = dl.size,
        platform = dl.platform,
        onComplete = { dl.cleanup() },
    )
}

@Composable
private fun FileRow(file: com.yunx.app.data.network.model.ShareFile, loading: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !loading) { onClick() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (file.isdir) "📁" else "📄")
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(file.fname, maxLines = 1)
            if (file.isdir) Text("文件夹", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!file.isdir) {
            Text(formatSize(file.fsize), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("下载", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PlatformChip(p: SharePlatform) {
    val label = when (p) {
        SharePlatform.QUARK -> "夸克"; SharePlatform.UC -> "UC"
        SharePlatform.XUNLEI -> "迅雷"; SharePlatform.BAIDU -> "百度"
        SharePlatform.C139 -> "139"; SharePlatform.PAN123 -> "123"
    }
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun ErrorBanner(msg: String, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(8), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
fun InfoBanner(msg: String, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(8), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(msg, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 13.sp)
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

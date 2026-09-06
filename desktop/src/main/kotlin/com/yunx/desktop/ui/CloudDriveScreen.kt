/*
 * YunX Desktop - AGPL-3.0. Cloud-drive browsing screen (personal netdisk).
 */
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.model.ShareFile
import com.yunx.desktop.AppServices
import com.yunx.desktop.app.CloudDriveController
import com.yunx.desktop.app.DriveId
import com.yunx.desktop.app.formatSize
import kotlinx.coroutines.launch

@Composable
fun CloudDriveScreen(services: AppServices, snackbar: SnackbarHostState) {
    val cloud = remember { services.cloud }
    val logged = cloud.loggedPlatforms()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("云盘", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("登录账号后浏览你的网盘文件；文件夹支持递归下载（保持目录结构）保存到下载目录。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))

        cloud.error?.let { e ->
            ErrorBanner(e) { cloud.consumeError() }
            Spacer(Modifier.height(8.dp))
        }
        cloud.message?.let { m ->
            InfoBanner(m) { cloud.consumeMessage() }
            Spacer(Modifier.height(8.dp))
        }

        // 平台选择
        if (logged.isEmpty()) {
            Surface(shape = RoundedCornerShape(8), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text("还没有已登录的网盘账号，请先到「账号」页登录（粘贴 Cookie / Token）。",
                    Modifier.padding(14.dp))
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            logged.forEach { d ->
                val selected = cloud.platform == d
                val label = when (d) {
                    DriveId.QUARK -> "夸克"; DriveId.UC -> "UC"; DriveId.BAIDU -> "百度"
                    DriveId.C139 -> "139"; DriveId.PAN123 -> "123"; DriveId.XUNLEI -> "迅雷"
                }
                if (selected) Button(onClick = { cloud.selectPlatform(d) }) { Text(label) }
                else OutlinedButton(onClick = { cloud.selectPlatform(d) }) { Text(label) }
            }
        }
        Spacer(Modifier.height(10.dp))

        // 面包屑
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { cloud.back() }, enabled = cloud.path.isNotEmpty() && cloud.operating.not()) { Text("↑ 上一级") }
            TextButton(onClick = { cloud.loadRoot() }) { Text("根目录") }
            Spacer(Modifier.width(4.dp))
            val crumb = cloud.path
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { cloud.navigateToLevel(0) }) { Text("根") }
                crumb.forEachIndexed { i, seg ->
                    Text("/")
                    TextButton(onClick = { cloud.navigateToLevel(i + 1) }) { Text(seg.name, maxLines = 1) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (cloud.operating) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(cloud.folderProgress?.let { it } ?: "处理中…", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { cloud.cancelRecursive() }) { Text("取消") }
            }
            Spacer(Modifier.height(6.dp))
        }

        when {
            cloud.loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            cloud.platform == null -> Surface(shape = RoundedCornerShape(8), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text("选择一个网盘平台查看你的云盘文件。", Modifier.padding(14.dp))
            }
            else -> Card(Modifier.fillMaxSize(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                if (cloud.files.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("当前目录为空", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Text("共 " + cloud.files.size + " 项", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(cloud.files, key = { it.fid }) { f ->
                                CloudRow(
                                    file = f,
                                    busy = cloud.operating,
                                    onOpen = { if (f.isdir) cloud.openFolder(f) },
                                    onDownload = {
                                        if (f.isdir) cloud.downloadFolder(f)
                                        else cloud.downloadFile(f)
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
}

@Composable
private fun CloudRow(file: ShareFile, busy: Boolean, onOpen: () -> Unit, onDownload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !busy) { onOpen() }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (file.isdir) "📁" else "📄")
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(file.fname, maxLines = 1)
            if (file.isdir) Text("文件夹", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!file.isdir) {
            Text(formatSize(file.fsize), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
        }
        OutlinedButton(onClick = onDownload, enabled = !busy) {
            Text(if (file.isdir) "递归下载" else "下载")
        }
    }
}

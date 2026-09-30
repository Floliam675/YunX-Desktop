/*
 * YunX Desktop - AGPL-3.0.
 * 「发现新版本」对话框（移植自上游 UpdateSheet 的语义，桌面版不自动替换自身：
 * 只给发布页 / 安装包直链 / 镜像兜底，装不装由用户决定）。
 */
package com.yunx.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunx.desktop.app.ReleaseInfo
import com.yunx.desktop.app.UpdateChecker

/**
 * @param onOpen 用系统浏览器打开链接（发布页 / 安装包直链）
 * @param onSkip 忽略此版本（不再提示该版本，直到出现更新版本）
 */
@Composable
fun UpdateAvailableDialog(
    release: ReleaseInfo,
    currentVersion: String,
    onOpen: (String) -> Unit,
    onSkip: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val installer = UpdateChecker.installerAsset(release)
    val netdisk = UpdateChecker.netdiskUrl(release.body)
    val notes = release.body.trim().let { if (it.length > 700) it.take(700) + "\n…" else it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 " + release.tagName) },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text("当前版本 v$currentVersion　→　最新版本 ${release.tagName}",
                    style = MaterialTheme.typography.bodyMedium)
                if (release.publishedAt.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text("发布时间：" + release.publishedAt.take(10),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(notes, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Text("下载方式", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpen(release.htmlUrl) }) { Text("打开发布页") }
                    installer?.let { a ->
                        OutlinedButton(onClick = { onOpen(a.downloadUrl) }) {
                            Text(if (a.name.endsWith(".zip", true)) "下载便携版" else "下载安装包")
                        }
                        // 国内直连 GitHub 慢时的兜底通道（上游同款镜像前缀）
                        OutlinedButton(onClick = { onOpen(UpdateChecker.mirrorUrl(a.downloadUrl)) }) { Text("镜像下载") }
                    }
                }
                netdisk?.let { url ->
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { onOpen(url) }) { Text("网盘下载") }
                }
                Spacer(Modifier.height(10.dp))
                Text("提示：安装新版本不会删除你的账号与下载记录（数据在 %USERPROFILE%\\.yunx-desktop）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
        dismissButton = { TextButton(onClick = { onSkip(release.tagName) }) { Text("忽略此版本") } },
    )
}

/*
 * YunX Desktop - AGPL-3.0. 「关于」页面：只放用户需要知道的 —— 版本、开源来源与许可、
 * 生成声明、数据位置、免责声明。实现细节（技术栈/组件清单/登录实现方式）不在这里展开，
 * 第三方许可全文随包提供（THIRD_PARTY_NOTICES.md）。
 */
package com.yunx.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.desktop.AppServices
import com.yunx.desktop.app.ReleaseInfo
import com.yunx.desktop.app.UpdateChecker
import com.yunx.desktop.app.UpdateResult
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 应用版本（与打包配置保持一致） */
const val APP_VERSION = "0.4.7"

private const val REPO_URL = "https://github.com/Floliam675/YunX-Desktop"
private const val RELEASES_URL = REPO_URL + "/releases"
private const val UPSTREAM_URL = "https://github.com/CYQawa/YunX"
private const val LICENSE_URL = "https://www.gnu.org/licenses/agpl-3.0.txt"

@Composable
fun AboutScreen(services: AppServices, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<ReleaseInfo?>(null) }

    fun openExternal(url: String) {
        val ok = runCatching { Desktop.getDesktop().browse(URI(url)) }.isSuccess
        if (!ok) scope.launch { snackbar.showSnackbar("无法调用系统浏览器，请手动访问：" + url) }
    }

    fun openDir(dir: File) {
        runCatching { dir.mkdirs(); Desktop.getDesktop().open(dir) }
            .onFailure { scope.launch { snackbar.showSnackbar("无法打开目录：" + dir.absolutePath) } }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("关于", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        AboutCard("YunX Desktop（云析 · 桌面版）") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("版本 v" + APP_VERSION, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = {
                        if (checking) return@OutlinedButton
                        checking = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
                            checking = false
                            when (result) {
                                is UpdateResult.Success -> {
                                    if (UpdateChecker.compareVersions(result.release.tagName, APP_VERSION) > 0) {
                                        update = result.release
                                    } else {
                                        snackbar.showSnackbar("已是最新版本（v$APP_VERSION）")
                                    }
                                }
                                is UpdateResult.Failure -> snackbar.showSnackbar("检查更新失败：" + result.reason)
                            }
                        }
                    },
                    enabled = !checking,
                ) { Text(if (checking) "检查中…" else "检查更新") }
            }
            Spacer(Modifier.height(6.dp))
            Text("网盘分享链接解析与高速下载工具。", style = MaterialTheme.typography.bodySmall)
        }

        update?.let { rel ->
            UpdateAvailableDialog(
                release = rel,
                currentVersion = APP_VERSION,
                onOpen = { url -> openExternal(url) },
                onSkip = { tag -> services.settings.skippedVersion = tag; update = null },
                onDismiss = { update = null },
            )
        }

        Spacer(Modifier.height(12.dp))
        AboutCard("开源与来源") {
            Text("本软件基于 GNU AGPL-3.0 开源，移植自 CYQawa/YunX（AGPL-3.0）；" +
                "本版本为第三方维护的桌面移植版，非原项目官方发布，上游代码版权归原作者所有。" +
                "第三方组件许可详见随包 THIRD_PARTY_NOTICES.md。",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openExternal(REPO_URL) }) { Text("项目主页") }
                OutlinedButton(onClick = { openExternal(RELEASES_URL) }) { Text("下载新版本") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openExternal(UPSTREAM_URL) }) { Text("上游 YunX（Android）") }
                OutlinedButton(onClick = { openExternal(LICENSE_URL) }) { Text("AGPL-3.0 许可") }
            }
        }

        Spacer(Modifier.height(12.dp))
        AboutCard("数据与隐私") {
            Text("账号、下载任务与设置仅保存在本机：" + AppServices.dataDir().absolutePath,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openDir(AppServices.dataDir()) }) { Text("打开数据目录") }
                OutlinedButton(onClick = { openDir(services.effectiveDownloadDir()) }) { Text("打开下载目录") }
            }
        }

        Spacer(Modifier.height(12.dp))
        AboutCard("免责声明") {
            Text("仅供个人学习与技术交流，请遵守各网盘平台的服务条款，并自行承担使用风险。" +
                "本程序未做代码签名，首次运行可能被 SmartScreen 或杀毒软件提示；" +
                "建议用发布页提供的 SHA256 校验值核对文件完整性。",
                style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/*
 * YunX Desktop - AGPL-3.0. 「关于」页面：版本、开源来源与许可、AI 生成声明、
 * 第三方组件、数据目录与免责声明。
 */
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.desktop.AppServices
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlinx.coroutines.launch

/** 应用版本（与打包配置保持一致） */
const val APP_VERSION = "0.2.0"

private const val REPO_URL = "https://github.com/Floliam675/YunX-Desktop"
private const val RELEASES_URL = REPO_URL + "/releases"
private const val UPSTREAM_URL = "https://github.com/CYQawa/YunX"
private const val LICENSE_URL = "https://www.gnu.org/licenses/agpl-3.0.txt"

@Composable
fun AboutScreen(snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()

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
            Text("版本 v" + APP_VERSION, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(
                "网盘分享链接解析与高速下载工具。复用原 YunX（Android）的纯 Kotlin 业务逻辑" +
                    "（六家网盘的网络协议、分享解析、分片 / HLS 下载引擎），界面使用 Compose Multiplatform，" +
                    "网页登录由内嵌 Chromium(JCEF) 完成（登录后自动抓取登录态）。",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(12.dp))
        AboutCard("开源与来源") {
            Text("本软件基于 GNU AGPL-3.0 开源，移植自 CYQawa/YunX（AGPL-3.0）。" +
                "本版本为第三方维护的桌面移植版，非原项目官方发布；上游代码版权归原作者所有。",
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
        AboutCard("AI 生成声明") {
            Text("本桌面移植工程由维护者提出需求并负责验证，在 AI 编码助手 DeepSeek v4 Flash" +
                "（deepseek-v4-flash）辅助下完成代码生成、重构与排错。" +
                "复用的网盘协议等核心逻辑版权归原作者所有；本项目代码一律以 AGPL-3.0 授权。",
                style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))
        AboutCard("第三方组件") {
            Text(
                "JCEF / Chromium（BSD-3-Clause，网页登录）、jcefmaven（Apache-2.0）、" +
                    "Kotlin 与 kotlinx-coroutines（Apache-2.0）、Compose Multiplatform 与 Material3（Apache-2.0）、" +
                    "OkHttp / Okio（Apache-2.0）、org.json（JSON License）、Skiko（Apache-2.0）。" +
                    "各组件以各自许可证发布，详见仓库 THIRD_PARTY_NOTICES.md。",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(12.dp))
        AboutCard("数据与隐私") {
            Text("账号、下载任务与设置仅保存在本机：" + AppServices.dataDir().absolutePath,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Text("网页登录所用浏览器内核（首次运行自动解包）：" +
                File(System.getProperty("user.home"), ".jcef-bundle").absolutePath,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openDir(AppServices.dataDir()) }) { Text("打开数据目录") }
                OutlinedButton(onClick = { openDir(AppServices.downloadDir()) }) { Text("打开下载目录") }
            }
        }

        Spacer(Modifier.height(12.dp))
        AboutCard("免责声明") {
            Text("仅供个人学习与技术交流，请遵守各网盘平台的服务条款；网盘协议基于抓包分析，" +
                "可能随官方调整而失效。本程序未做代码签名，首次运行可能被 SmartScreen 或杀毒软件提示，" +
                "请以发布方提供的 SHA256 校验值核对文件完整性。",
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

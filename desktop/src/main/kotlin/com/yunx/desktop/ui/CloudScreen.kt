/*
 * YunX Desktop - AGPL-3.0.
 * 网盘页（合并了原「云盘」与「账号」两个页面），与上游一样是两级结构：
 *   一级：网盘列表 —— 每张卡片显示登录状态，卡上有「网页登录 / 登录 / 退出」；
 *         点卡片进入该网盘的文件浏览（未登录则先弹登录框）
 *   二级：该网盘的文件浏览（← 返回网盘列表 / 面包屑 / 刷新 / 逐项与递归下载）
 */
package com.yunx.desktop.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.Pan123Constants
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.model.ShareFile
import com.yunx.desktop.AppServices
import com.yunx.desktop.app.DriveAccount
import com.yunx.desktop.app.DriveId
import com.yunx.desktop.app.formatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun CloudScreen(services: AppServices, snackbar: SnackbarHostState) {
    val accounts by services.accountStore.accounts.collectAsState()
    val cloud = services.cloud
    val scope = rememberCoroutineScope()
    var loginDrive by remember { mutableStateOf<DriveId?>(null) }

    val opened = cloud.platform
    if (opened == null) {
        // ---------------- 一级：网盘列表 ----------------
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("网盘", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("登录后点卡片即可浏览该网盘的文件；卡上可直接登录 / 退出",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DriveId.entries.forEach { d ->
                    val acc = accounts[d.name]
                    val loggedIn = acc != null && acc.isLoggedIn()
                    PlatformCard(
                        drive = d,
                        account = acc,
                        loggedIn = loggedIn,
                        busy = cloud.operating,
                        onOpen = { if (loggedIn) cloud.selectPlatform(d) else loginDrive = d },
                        onLogin = { loginDrive = d },
                        onWebLogin = { launchWebLogin(services, d, scope, snackbar) },
                        onLogout = {
                            scope.launch {
                                services.login.logout(d.name)
                                snackbar.showSnackbar(d.label + " 已退出登录")
                            }
                        },
                    )
                }
            }
        }
    } else {
        // ---------------- 二级：该网盘的文件浏览 ----------------
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { cloud.reset() }) { Text("← 返回网盘列表") }
                Spacer(Modifier.width(4.dp))
                Text(opened.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(
                    "已登录：" + (accounts[opened.name]?.nickname ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            BrowsePane(services, snackbar, scope)
        }
    }

    loginDrive?.let { d ->
        LoginDialog(
            drive = d,
            onDismiss = { loginDrive = null },
            onSave = { values ->
                scope.launch {
                    val outcome = services.login.save(d.name, values)
                    loginDrive = null
                    snackbar.showSnackbar(outcome.message)
                }
            },
            onSendSms = { mobile ->
                scope.launch { snackbar.showSnackbar(services.login.sendXunleiSms(mobile).message) }
            },
            onSmsLogin = { mobile, code ->
                scope.launch {
                    val outcome = services.login.xunleiSmsLogin(mobile, code)
                    if (outcome.ok) loginDrive = null
                    snackbar.showSnackbar(outcome.message)
                }
            },
        )
    }
}

/** 网盘列表里的一张卡片：登录状态 + 操作；点卡片进入该网盘 */
@Composable
private fun PlatformCard(
    drive: DriveId,
    account: DriveAccount?,
    loggedIn: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onLogin: () -> Unit,
    onWebLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = !busy) { onOpen() },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(drive.label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (loggedIn) "已登录：" + account?.nickname else "未登录 · 点卡片登录后即可浏览",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (loggedIn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loggedIn) {
                    Text("浏览 ›", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 支持网页登录（Cookie / localStorage）的平台提供入口；迅雷走短信验证码
                if (drive != DriveId.XUNLEI) {
                    OutlinedButton(onClick = onWebLogin, enabled = !busy) { Text("网页登录") }
                }
                if (loggedIn) {
                    OutlinedButton(onClick = onLogout, enabled = !busy) { Text("退出") }
                } else {
                    Button(onClick = onLogin, enabled = !busy) { Text("登录") }
                }
            }
        }
    }
}

/** 二级页：文件浏览（面包屑 + 列表 + 逐项/递归下载） */
@Composable
private fun BrowsePane(services: AppServices, snackbar: SnackbarHostState, scope: CoroutineScope) {
    val cloud = services.cloud
    val err = cloud.error
    val msg = cloud.message
    if (err != null) {
        Surface(shape = RoundedCornerShape(8), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(err, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = { cloud.consumeError(); scope.launch { snackbar.showSnackbar(err) } }) { Text("知道了") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (msg != null) {
        Surface(shape = RoundedCornerShape(8), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(msg, Modifier.weight(1f))
                TextButton(onClick = { cloud.consumeMessage() }) { Text("知道了") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // 面包屑：回根目录就是点第一个「根目录」
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { cloud.back() }, enabled = cloud.path.isNotEmpty() && cloud.operating.not()) { Text("↑ 上一级") }
        TextButton(onClick = { cloud.refresh() }, enabled = cloud.operating.not()) { Text("刷新") }
        Spacer(Modifier.width(4.dp))
        val crumb = cloud.path
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { cloud.navigateToLevel(0) }) { Text("根目录") }
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
            Text(cloud.folderProgress ?: "处理中…", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { cloud.cancelRecursive() }) { Text("取消") }
        }
        Spacer(Modifier.height(6.dp))
    }

    when {
        cloud.loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        else -> Card(Modifier.fillMaxSize(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            if (cloud.files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("当前目录为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Text("共 " + cloud.files.size + " 项", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(cloud.files, key = { it.fid }) { f ->
                            CloudRow(
                                file = f,
                                busy = cloud.operating,
                                onOpen = { if (f.isdir) cloud.openFolder(f) },
                                onDownload = { if (f.isdir) cloud.downloadFolder(f) else cloud.downloadFile(f) },
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 8.dp))
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
            if (file.isdir) Text("文件夹", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!file.isdir) {
            Text(formatSize(file.fsize), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
        }
        OutlinedButton(onClick = onDownload, enabled = !busy) {
            Text(if (file.isdir) "递归下载" else "下载")
        }
    }
}

/** 网页登录参数：url / localStorage 键（null=凭证在 Cookie）/ 有效性判定 / 窗口标题 */
private data class WebLoginSpec(
    val url: String,
    val storageKey: String?,
    val isValid: (String) -> Boolean,
    val title: String,
)

private fun launchWebLogin(
    services: AppServices, d: DriveId, scope: CoroutineScope, snackbar: SnackbarHostState,
) {
    val (url, storageKey, isValid, title) = when (d) {
        DriveId.QUARK -> WebLoginSpec(QuarkConstants.LOGIN_URL, null, { c: String -> QuarkConstants.isValidCookie(c) }, "夸克网盘登录")
        DriveId.UC -> WebLoginSpec(UCConstants.LOGIN_URL, null, { c: String -> UCConstants.isValidCookie(c) }, "UC网盘登录")
        DriveId.BAIDU -> WebLoginSpec(BaiduConstants.LOGIN_URL, null, { c: String -> BaiduConstants.isValidCookie(c) }, "百度网盘登录")
        DriveId.C139 -> WebLoginSpec(C139Constants.LOGIN_URL, null, { c: String -> C139Constants.isValidCookie(c) }, "139网盘登录")
        DriveId.PAN123 -> WebLoginSpec(Pan123Constants.WEB_LOGIN_URL, Pan123Constants.LOCAL_STORAGE_TOKEN_KEY, { c: String -> c.isNotBlank() }, "123云盘登录")
        else -> return
    }
    // 借本机已安装的浏览器取登录态（Chromium 走 CDP / Firefox 走 BiDi）；失败时提示改用手动粘贴
    BrowserLogin(
        windowTitle = title,
        url = url,
        storageKey = storageKey,
        isValidCredential = isValid,
        onCredential = { credential ->
            scope.launch { snackbar.showSnackbar(services.login.save(d.name, mapOf("raw" to credential)).message) }
        },
        onStatus = { m -> scope.launch { snackbar.showSnackbar(m) } },
        onError = { m -> scope.launch { snackbar.showSnackbar(m) } },
        confirm = services.loginConfirm,
    ).show()
}

@Composable
private fun LoginDialog(
    drive: DriveId,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
    onSendSms: (String) -> Unit = { _ -> },
    onSmsLogin: (String, String) -> Unit = { _, _ -> },
) {
    var field1 by remember(drive) { mutableStateOf("") }
    var field2 by remember(drive) { mutableStateOf("") }
    var mobile by remember(drive) { mutableStateOf("") }
    var smsCode by remember(drive) { mutableStateOf("") }
    var smsMode by remember(drive) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val smsActive = drive == DriveId.XUNLEI && smsMode
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("登录" + drive.label) },
        text = {
            Column {
                if (drive == DriveId.XUNLEI) {
                    Row {
                        TextButton(onClick = { smsMode = false }) { Text("粘贴 token") }
                        TextButton(onClick = { smsMode = true }) { Text("短信验证码") }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(drive.loginHint, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                if (smsActive) {
                    OutlinedTextField(value = mobile, onValueChange = { mobile = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("手机号") })
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { onSendSms(mobile) }, enabled = mobile.isNotBlank() && !busy) { Text("发送验证码") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(value = smsCode, onValueChange = { smsCode = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("短信验证码") })
                } else {
                    OutlinedTextField(
                        value = field1, onValueChange = { field1 = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (drive == DriveId.XUNLEI) "access_token" else "Cookie / Token") },
                        minLines = 4,
                    )
                    if (drive == DriveId.XUNLEI) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(value = field2, onValueChange = { field2 = it },
                            modifier = Modifier.fillMaxWidth(), label = { Text("refresh_token（可选）") }, minLines = 2)
                    }
                }
                localError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (smsActive) {
                Button(
                    enabled = mobile.isNotBlank() && smsCode.isNotBlank() && !busy,
                    onClick = { busy = true; localError = null; onSmsLogin(mobile, smsCode) },
                ) { Text("验证并登录") }
            } else {
                Button(
                    enabled = field1.isNotBlank() && !busy,
                    onClick = {
                        busy = true; localError = null
                        val values = if (drive == DriveId.XUNLEI) mapOf("access" to field1, "refresh" to field2)
                                     else mapOf("raw" to field1)
                        onSave(values)
                    },
                ) { Text("验证并登录") }
            }
        },
        dismissButton = { TextButton(onClick = { if (!busy) onDismiss() }) { Text("取消") } },
    )
}

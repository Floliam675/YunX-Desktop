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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import com.yunx.desktop.AppServices
import com.yunx.desktop.app.DriveAccount
import com.yunx.desktop.app.DriveId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun AccountsScreen(services: AppServices, snackbar: SnackbarHostState) {
    val accounts by services.accountStore.accounts.collectAsState()
    val scope = rememberCoroutineScope()
    var loginDrive by remember { mutableStateOf<DriveId?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("网盘账号", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("在浏览器登录网页版网盘后，复制 Cookie / Token 粘贴到此登录（取代手机端 WebView 登录）。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DriveId.entries.forEach { d ->
                val acc = accounts[d.name]
                AccountRow(
                    drive = d,
                    account = acc,
                    onLogin = { loginDrive = d },
                    onWebLogin = { launchJcefLogin(services, d, scope, snackbar) },
                    onLogout = {
                        scope.launch { services.login.logout(d.name); snackbar.showSnackbar(d.label + " 已退出登录") }
                    },
                )
            }
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
                scope.launch {
                    val outcome = services.login.sendXunleiSms(mobile)
                    snackbar.showSnackbar(outcome.message)
                }
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

/** 网页登录参数：url / localStorage 键（null=凭证在 Cookie）/ 有效性判定 / 窗口标题 */
private data class WebLoginSpec(
    val url: String,
    val storageKey: String?,
    val isValid: (String) -> Boolean,
    val title: String,
)

private fun launchJcefLogin(
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
    val win = JcefWebLogin(title, url, storageKey, isValid) { credential ->
        scope.launch {
            val outcome = services.login.save(d.name, mapOf("raw" to credential))
            snackbar.showSnackbar(outcome.message)
        }
    }
    win.show()
}

@Composable
private fun AccountRow(
    drive: DriveId,
    account: DriveAccount?,
    onLogin: () -> Unit,
    onWebLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(drive.label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                if (account != null && account.isLoggedIn()) {
                    Text("已登录：" + account.nickname, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("未登录", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            // 支持网页登录（Cookie / localStorage）的平台提供「网页登录」入口
            if (drive != DriveId.XUNLEI) {
                OutlinedButton(onClick = onWebLogin) { Text("网页登录") }
                Spacer(Modifier.width(8.dp))
            }
            if (account != null && account.isLoggedIn()) {
                OutlinedButton(onClick = onLogout) { Text("退出") }
            } else {
                Button(onClick = onLogin) { Text("登录") }
            }
        }
    }
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
                    OutlinedTextField(
                        value = mobile,
                        onValueChange = { mobile = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("手机号") },
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onSendSms(mobile) }, enabled = mobile.isNotBlank() && !busy) { Text("发送验证码") }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = smsCode,
                        onValueChange = { smsCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("短信验证码") },
                    )
                } else {
                    OutlinedTextField(
                        value = field1,
                        onValueChange = { field1 = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (drive == DriveId.XUNLEI) "access_token" else "Cookie / Token") },
                        minLines = 4,
                    )
                    if (drive == DriveId.XUNLEI) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = field2,
                            onValueChange = { field2 = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("refresh_token（可选）") },
                            minLines = 2,
                        )
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
                    onClick = {
                        busy = true; localError = null
                        onSmsLogin(mobile, smsCode)
                    },
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


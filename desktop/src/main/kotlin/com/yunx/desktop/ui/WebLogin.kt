/*
 * YunX Desktop - AGPL-3.0. Web login (restores the original WebView login).
 * The JavaFX WebView is hosted in its OWN native window (JFrame) so it is fully
 * decoupled from the Compose render loop — the previous inline SwingPanel host was
 * janky (Compose/Skiko → Swing → JavaFX triple hop). Login auto-detects Cookie /
 * localStorage token and saves it.
 */
package com.yunx.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.yunx.desktop.app.DriveId
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * 启动时后台预热 JavaFX/WebKit：首次创建 JFXPanel + WebView 会初始化整个 FX/WebKit 运行时
 * （较慢），提前在后台做完，用户点击“网页登录”时窗口能立即出现、页面马上开始加载。
 */
fun warmUpJavaFX() {
    Thread {
        try {
            val panel = JFXPanel()          // 启动 JavaFX 运行时
            val done = CountDownLatch(1)
            Platform.runLater {
                Platform.setImplicitExit(false)
                val wv = WebView()          // 初始化 WebKit（重）
                wv.engine.loadContent("<html><body>warm</body></html>")
                done.countDown()
            }
            done.await(3, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }.start()
}

/**
 * 内嵌 JavaFX WebView 宿主：独立 JFrame 窗口承载，与 Compose 渲染完全解耦。
 * JS 读取在 FX 线程执行，用 CountDownLatch 同步返回（调用方在后台协程，不会死锁 FX 线程）。
 */
class JfxWebViewHolder(private val initialUrl: String, private val userAgent: String) {
    val panel = JFXPanel()
    private var webView: WebView? = null
    private var frame: JFrame? = null

    /** 在 FX 线程建 WebView，在 EDT 开独立窗口 */
    fun init() {
        Platform.runLater {
            Platform.setImplicitExit(false)
            val wv = WebView()
            wv.engine.userAgent = userAgent
            wv.engine.load(initialUrl)
            panel.scene = Scene(wv, 1000.0, 700.0)
            webView = wv
        }
        SwingUtilities.invokeLater {
            val f = JFrame("网页登录")
            f.contentPane.add(panel)
            f.setSize(1000, 700)
            f.setLocationRelativeTo(null)
            f.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            f.isVisible = true
            frame = f
        }
    }

    val ready: Boolean get() = webView != null
    val isOpen: Boolean get() = frame?.isDisplayable == true

    /** 同步执行 JS，返回字符串结果（null=JS 无值/尚未加载） */
    fun eval(script: String): String? {
        if (webView == null) return null
        val ref = AtomicReference<String?>()
        val done = CountDownLatch(1)
        Platform.runLater {
            try {
                ref.set(webView?.engine?.executeScript(script) as? String)
            } catch (_: Exception) {
                ref.set(null)
            } finally { done.countDown() }
        }
        return if (done.await(2, TimeUnit.SECONDS)) ref.get() else null
    }

    fun close() {
        SwingUtilities.invokeLater { frame?.dispose(); frame = null }
        Platform.runLater { panel.scene = null; webView = null }
    }
}

/** 网页登录凭证来源：Cookie（document.cookie）或 localStorage 里的 JWT */
data class WebLoginSource(
    val url: String,
    val cookieScript: String,
    val storageKey: String,
    val userAgent: String
)

fun webLoginSource(drive: DriveId): WebLoginSource? = when (drive) {
    DriveId.QUARK -> WebLoginSource(QuarkConstants.LOGIN_URL, "document.cookie", "", QuarkConstants.USER_AGENT)
    DriveId.UC -> WebLoginSource(UCConstants.LOGIN_URL, "document.cookie", "", UCConstants.USER_AGENT)
    DriveId.BAIDU -> WebLoginSource(BaiduConstants.LOGIN_URL, "document.cookie", "", BaiduConstants.UA_WEB)
    DriveId.C139 -> WebLoginSource(C139Constants.LOGIN_URL, "document.cookie", "", C139Constants.PC_UA)
    DriveId.PAN123 -> WebLoginSource(Pan123Constants.WEB_LOGIN_URL, "", Pan123Constants.LOCAL_STORAGE_TOKEN_KEY, Pan123Constants.WEB_UA)
    else -> null
}

/**
 * 网页登录屏：打开独立浏览器窗口，用户登录后自动检测并保存；
 * 保留「已登录，保存」手动兜底与「返回」。关闭浏览器窗口即返回。
 */
@Composable
fun WebLoginScreen(
    services: AppServices,
    drive: DriveId,
    snackbar: SnackbarHostState,
    onDone: () -> Unit
) {
    val source = remember(drive) { webLoginSource(drive) }
    val holder = remember(source) { source?.let { JfxWebViewHolder(it.url, it.userAgent) } }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(holder) {
        if (holder == null) return@LaunchedEffect
        holder.init()
        withContext(kotlinx.coroutines.Dispatchers.Default) {
            while (true) {
                delay(1800)
                if (!holder.isOpen) break                 // 用户关了浏览器窗口
                if (saving) continue
                if (!holder.ready) continue
                val cred = readCredential(drive, holder) ?: continue
                val outcome = services.login.save(drive.name, mapOf("raw" to cred))
                if (outcome.ok) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) { snackbar.showSnackbar("登录成功：" + drive.label) }
                    break
                }
            }
        }
        holder.close()
        withContext(kotlinx.coroutines.Dispatchers.Main) { onDone() }
    }

    DisposableEffect(holder) {
        onDispose { holder?.close() }
    }

    if (holder == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) { Text("该平台不支持网页登录，请在「网盘账号」页粘贴凭证。") }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("网页登录 · " + drive.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("已在新窗口打开官方登录页。在其中完成登录后会自动检测并保存；也可点「已登录，保存」手动兜底，或直接关闭浏览器窗口返回。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            val cred = readCredential(drive, holder)
            if (cred.isNullOrBlank()) { scope.launch { snackbar.showSnackbar("未检测到登录态，请先完成登录") }; return@Button }
            saving = true
            scope.launch {
                val outcome = services.login.save(drive.name, mapOf("raw" to cred))
                saving = false
                snackbar.showSnackbar(outcome.message)
                if (outcome.ok) onDone()
            }
        }, enabled = !saving) {
            if (saving) CircularProgressIndicator(Modifier.height(18.dp).padding(end = 6.dp))
            Text("已登录，保存")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { onDone() }) { Text("关闭并返回") }
    }
}

/** 读取网页登录态（Cookie 或 localStorage token） */
private fun readCredential(drive: DriveId, holder: JfxWebViewHolder): String? {
    val source = webLoginSource(drive) ?: return null
    return when {
        source.storageKey.isNotBlank() ->
            holder.eval("(function(){try{return window.localStorage.getItem('" + source.storageKey + "');}catch(e){return null;}})()")
        source.cookieScript.isNotBlank() -> holder.eval(source.cookieScript)
        else -> null
    }
}

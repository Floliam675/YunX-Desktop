/*
 * YunX Desktop - AGPL-3.0.
 * JCEF (Chromium, windowed) 网页登录窗口：加载官方登录页，登录后自动抓取登录态。
 *   抓取方式（按平台）：
 *     - Cookie 型平台（夸克/UC/百度/139）：注册 CefRequestHandler，嗅探 CEF 为每个请求
 *       自动附带的 "Cookie" 头并累计合并 —— HttpOnly Cookie（如百度 BDUSS）也随请求发送，
 *       因此能被捕获（document.cookie 读不到 HttpOnly；本 JCEF 又无按上下文的
 *       CookieManager Java API）。由各平台 isValidCookie 判定登录成功。
 *     - localStorage 型平台（123云盘 authorToken）：注入页面 JS 轮询，经
 *       CefMessageRouter(cefQuery) 回传 token。
 * CefApp 为进程级单例；浏览器窗口关闭即释放本会话 client/router（CefApp 保留复用）。
 */
package com.yunx.desktop.ui

import me.friwi.jcefmaven.CefAppBuilder
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.Timer

/** CefApp 是进程级单例，只能初始化一次（settings 只能在首次传入） */
internal object JcefRuntime {
    @Volatile
    private var app: CefApp? = null

    @Volatile
    private var startFailed = false

    fun ensureStarted(): CefApp {
        app?.let { return it }
        synchronized(this) {
            app?.let { return it }
            check(!startFailed) { "JCEF 启动失败，请重启应用后重试" }
            val existing = runCatching {
                val st = CefApp.getState()
                if (st == CefApp.CefAppState.INITIALIZED || st == CefApp.CefAppState.INITIALIZING) CefApp.getInstance() else null
            }.getOrNull()
            if (existing != null) {
                app = existing
                return existing
            }
            try {
                val builder = CefAppBuilder()
                builder.setInstallDir(File(System.getProperty("user.home"), ".jcef-bundle"))
                builder.getCefSettings().windowless_rendering_enabled = false // windowed：Chromium 原生渲染，避免 OpenGL/OSR
                val created = builder.build()
                app = created
                return created
            } catch (t: Throwable) {
                startFailed = true
                throw t
            }
        }
    }
}

/**
 * JCEF 网页登录窗口。
 * @param storageKey 非空表示该平台登录态在 localStorage（如 123 云盘 authorToken）；
 *        为空表示平台登录态是 Cookie（含 HttpOnly，经请求头嗅探捕获）。
 * @param isValidCredential 判定抓到的凭证是否已是有效登录态（纯字符串判断，勿做网络请求）。
 * @param onCredential 捕获成功后回调（Cookie 型为整串 Cookie，localStorage 型为 token）。
 */
class JcefWebLogin(
    private val windowTitle: String,
    private val url: String,
    private val storageKey: String?,
    private val isValidCredential: (String) -> Boolean,
    private val onCredential: (String) -> Unit
) {
    private val closed = AtomicBoolean(false)
    /** Cookie 型平台：累积到的 cookie 名值对（请求头带什么就收什么，含 HttpOnly） */
    private val cookiePairs = ConcurrentHashMap<String, String>()
    /** localStorage 型平台：回传的 token */
    private val lsField = AtomicReference<String?>()
    private var client: CefClient? = null
    private var browser: CefBrowser? = null
    private var router: CefMessageRouter? = null
    private var frame: JFrame? = null
    private var timer: Timer? = null

    fun show() {
        // 原生初始化/首次解包较重：后台线程做，完成后切回 EDT 建窗口，避免卡住 Compose UI
        Thread({ runCatchingInit() }, "yunx-jcef-open").apply { isDaemon = true }.start()
    }

    private fun runCatchingInit() {
        try {
            val app = JcefRuntime.ensureStarted()
            SwingUtilities.invokeLater { openUi(app) }
        } catch (t: Throwable) {
            SwingUtilities.invokeLater {
                showError("启动内置浏览器失败：" + (t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun openUi(app: CefApp) {
        if (closed.get()) return
        try {
            val c = app.createClient()
            client = c

            if (storageKey != null) {
                // localStorage 型：cefQuery 通道 + 页面轮询脚本
                val messageHandler = object : CefMessageRouterHandlerAdapter() {
                    override fun onQuery(
                        browser: CefBrowser?, frame: CefFrame?, queryId: Long, request: String?, persistent: Boolean,
                        callback: org.cef.callback.CefQueryCallback?
                    ): Boolean {
                        val req = request ?: return false
                        if (req.startsWith(LS_PREFIX)) {
                            lsField.set(req.substring(LS_PREFIX.length))
                            callback?.success("")
                            return true
                        }
                        return false
                    }
                }
                val r = CefMessageRouter.create(messageHandler)
                c.addMessageRouter(r)
                router = r

                c.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                        if (frame.isMain) runCatching { browser.executeJavaScript(lsPollerScript(), browser.url ?: "", 0) }
                    }
                })
            } else {
                // Cookie 型：双通道捕获（都包含 HttpOnly）
                //  ① 每个请求自带 Cookie 头（浏览器实际发送的）；
                //  ② 每个响应的 Set-Cookie 直读（不依赖 cookie 是否已存/是否附加）。
                val resourceHandler = object : org.cef.handler.CefResourceRequestHandlerAdapter() {
                    override fun onResourceResponse(
                        browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, response: org.cef.network.CefResponse?
                    ): Boolean {
                        val dbg = System.getProperty("yunx.jcef.debug") != null
                        try {
                            response?.getHeaderByName("Set-Cookie")?.let(::absorbSetCookie)
                            if (dbg) System.err.println("[set-cookie] " + (request?.getURL() ?: "?") + " -> " + (response?.getHeaderByName("Set-Cookie") ?: "(none)"))
                        } catch (t: Throwable) {
                            if (dbg) System.err.println("[set-cookie] error: " + t)
                        }
                        return false
                    }
                }
                c.addRequestHandler(object : CefRequestHandlerAdapter() {
                    override fun getResourceRequestHandler(
                        browser: CefBrowser, frame: CefFrame, request: CefRequest,
                        isNavigation: Boolean, isDownload: Boolean, requestInitiator: String?, disableDefaultHandling: BoolRef?
                    ): org.cef.handler.CefResourceRequestHandler? {
                        val dbg = System.getProperty("yunx.jcef.debug") != null
                        try {
                            val cookie = request.getHeaderByName("Cookie")
                            if (dbg) System.err.println("[sniff] " + request.getURL() + " cookie=" + (cookie ?: "(none)"))
                            cookie?.let(::absorbCookies)
                        } catch (t: Throwable) {
                            if (dbg) System.err.println("[sniff] error: " + t)
                        }
                        return resourceHandler
                    }
                })
            }
            // 弹窗一律改在当前窗口内导航，避免游离的裸窗口导致抓取丢失
            c.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                override fun onBeforePopup(
                    browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?
                ): Boolean {
                    if (!targetUrl.isNullOrBlank()) browser?.loadURL(targetUrl)
                    return true
                }
            })

            val b = c.createBrowser(url, false, false)
            browser = b

            val f = JFrame(windowTitle).apply {
                contentPane.add(b.getUIComponent())
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                setSize(1024, 720)
                setLocationRelativeTo(null)
            }
            f.addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent) { disposeSession() }
            })
            frame = f
            f.isVisible = true

            timer = Timer(POLL_MS) { tick() }
            timer?.start()
        } catch (t: Throwable) {
            showError("打开登录窗口失败：" + (t.message ?: t.javaClass.simpleName))
            disposeSession()
        }
    }

    /** 解析 "k=v; k2=v2" 请求 Cookie 头并累计（同名后者覆盖） */
    private fun absorbCookies(header: String) {
        for (part in header.split(";")) {
            val kv = part.trim()
            if (kv.isEmpty()) continue
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            val name = kv.substring(0, eq).trim()
            val value = kv.substring(eq + 1).trim()
            if (name.isNotEmpty() && cookiePairs.size < MAX_COOKIE_PAIRS) cookiePairs[name] = value
        }
    }

    /** 解析响应头单条 "name=value[; 属性...]" 的 Set-Cookie */
    private fun absorbSetCookie(header: String) {
        val first = header.substringBefore(';').trim()
        val eq = first.indexOf('=')
        if (eq <= 0) return
        val name = first.substring(0, eq).trim()
        val value = first.substring(eq + 1).trim()
        if (name.isNotEmpty() && cookiePairs.size < MAX_COOKIE_PAIRS) cookiePairs[name] = value
    }

    private fun joinedCookie(): String =
        cookiePairs.entries.joinToString("; ") { it.key + "=" + it.value }

    /** 每次执行都会在页面主框架上下文中运行，把 localStorage[key] 发回 Java */
    private fun lsPollerScript(): String {
        val key = storageKey ?: return ""
        return "(function(){try{var t=null;try{t=window.localStorage.getItem('" + key + "');}catch(e){}" +
            "if(t&&window.cefQuery){window.cefQuery({request:'" + LS_PREFIX + "'+t," +
            "persistent:false,onSuccess:function(){},onFailure:function(){}});}}catch(e){}})();"
    }

    private fun tick() {
        if (closed.get()) return
        if (System.getProperty("yunx.jcef.debug") != null) {
            System.err.println("[tick] storageKey=" + storageKey + " pairs=" + cookiePairs.size + " ls=" + lsField.get())
        }
        val cred: String? = if (storageKey != null) {
            runCatching { browser?.executeJavaScript(lsPollerScript(), browser?.url ?: "", 0) }
            lsField.get()
        } else {
            joinedCookie()
        }
        if (!cred.isNullOrBlank() && isValidCredential(cred)) finish(cred)
    }

    // ---------- 结束 ----------

    private fun finish(credential: String) {
        if (!closed.compareAndSet(false, true)) return
        timer?.stop()
        releaseResources()
        SwingUtilities.invokeLater { onCredential(credential) }
    }

    /** 用户关闭窗口：释放本会话 client/router（CefApp 保留，供下次登录复用） */
    private fun disposeSession() {
        if (!closed.compareAndSet(false, true)) return
        timer?.stop()
        releaseResources()
    }

    private fun releaseResources() {
        frame?.dispose()
        frame = null
        runCatching { router?.dispose() }
        router = null
        runCatching { browser?.close(true) }
        browser = null
        runCatching { client?.dispose() }
        client = null
    }

    private fun showError(msg: String) {
        if (System.getProperty("yunx.jcef.silent") != null) {
            System.err.println("[jcef-web-login] " + msg)
            return
        }
        JOptionPane.showMessageDialog(null, msg, "网页登录", JOptionPane.ERROR_MESSAGE)
    }

    companion object {
        private const val POLL_MS = 1500
        private const val LS_PREFIX = "poll:"
        private const val MAX_COOKIE_PAIRS = 300
    }
}

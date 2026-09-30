/*
 * YunX Desktop - AGPL-3.0.
 * 网页登录：借用户机器上**已安装的浏览器**完成登录，再把登录态取回来。
 *   · Chromium 系（Edge / Chrome / Brave / Vivaldi / Opera / Chromium / 360 / QQ / 搜狗）→ CDP
 *   · Firefox → WebDriver BiDi
 * 都不随包分发浏览器内核，因此不增加体积；协议实现在 LoginSession.kt。
 *
 * 安全边界：调试端口只绑 127.0.0.1 且仅在本进程存活期间开放；专用 profile 放在应用数据目录，
 * 不与用户日常浏览器配置混用；凭据只交给调用方（走既有本地加密存储），不打印、不外发。
 */
package com.yunx.desktop.ui

import com.yunx.desktop.AppServices
import com.yunx.desktop.app.LoginConfirm
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** 只在 127.0.0.1 上开放；被别的进程占用时依次顺延。 */
private val CANDIDATE_PORTS = listOf(9333, 9334, 9335, 9336)

/** 浏览器内核类型：决定用哪套调试协议。 */
private enum class Engine { CHROMIUM, FIREFOX }

private data class Browser(val name: String, val exe: File, val engine: Engine)

/** 每个浏览器用**各自的**配置目录：同一个 profile 被不同浏览器交叉打开会报版本不兼容。 */
private fun profileDirFor(exe: File): File {
    val legacy = File(AppServices.dataDir(), "edge-login-profile")
    // Edge 沿用旧目录，保证升级后原有的登录态继续可用
    if (exe.nameWithoutExtension.equals("msedge", ignoreCase = true) && legacy.isDirectory) return legacy
    return File(AppServices.dataDir(), "login-profile-" + exe.nameWithoutExtension.lowercase())
}

/** 同一时刻只允许一个登录会话：浏览器对同一 user-data-dir/-profile 是单例，起第二个会转发到第一个。 */
private val LOGIN_IN_PROGRESS = AtomicBoolean(false)

/**
 * 可用的浏览器候选（按优先级）。Chromium 系走 CDP，Firefox 走 WebDriver BiDi；
 * 两者都能满足「导航 + 取该站 cookie（含 HttpOnly）+ 读 localStorage」。
 */
private fun browserCandidates(): List<Browser> {
    val pf = System.getenv("ProgramFiles").orEmpty()
    val pf86 = System.getenv("ProgramFiles(x86)").orEmpty()
    val lad = System.getenv("LOCALAPPDATA").orEmpty()
    val list = mutableListOf<Browser>()
    fun add(name: String, engine: Engine, vararg paths: String) {
        paths.filter { it.isNotBlank() }.forEach { list += Browser(name, File(it), engine) }
    }
    // 允许手工指定（企业定制安装路径）：-Dyunx.browser=C:\...\firefox.exe
    System.getProperty("yunx.browser")?.takeIf { it.isNotBlank() }?.let {
        val exe = File(it)
        list += Browser("自定义浏览器", exe, if (exe.name.startsWith("firefox", true)) Engine.FIREFOX else Engine.CHROMIUM)
    }
    add("Microsoft Edge", Engine.CHROMIUM,
        "$pf86\\Microsoft\\Edge\\Application\\msedge.exe",
        "$pf\\Microsoft\\Edge\\Application\\msedge.exe",
        "$lad\\Microsoft\\Edge\\Application\\msedge.exe")
    add("Google Chrome", Engine.CHROMIUM,
        "$pf\\Google\\Chrome\\Application\\chrome.exe",
        "$pf86\\Google\\Chrome\\Application\\chrome.exe",
        "$lad\\Google\\Chrome\\Application\\chrome.exe")
    add("Brave", Engine.CHROMIUM,
        "$pf\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
        "$pf86\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
        "$lad\\BraveSoftware\\Brave-Browser\\Application\\brave.exe")
    add("Vivaldi", Engine.CHROMIUM,
        "$pf\\Vivaldi\\Application\\vivaldi.exe",
        "$lad\\Vivaldi\\Application\\vivaldi.exe")
    add("Opera", Engine.CHROMIUM,
        "$lad\\Programs\\Opera\\opera.exe",
        "$pf\\Opera\\opera.exe")
    add("Chromium", Engine.CHROMIUM,
        "$lad\\Chromium\\Application\\chrome.exe",
        "$pf\\Chromium\\Application\\chrome.exe")
    add("360 极速浏览器", Engine.CHROMIUM,
        "$pf86\\360\\360Chrome\\Chrome\\Application\\360chrome.exe",
        "$pf\\360\\360Chrome\\Chrome\\Application\\360chrome.exe")
    add("QQ 浏览器", Engine.CHROMIUM,
        "$pf\\Tencent\\QQBrowser\\QQBrowser.exe",
        "$pf86\\Tencent\\QQBrowser\\QQBrowser.exe")
    add("搜狗高速浏览器", Engine.CHROMIUM,
        "$pf\\SogouExplorer\\SogouExplorer.exe",
        "$pf86\\SogouExplorer\\SogouExplorer.exe")
    add("Mozilla Firefox", Engine.FIREFOX,
        "$pf\\Mozilla Firefox\\firefox.exe",
        "$pf86\\Mozilla Firefox\\firefox.exe",
        "$lad\\Mozilla Firefox\\firefox.exe")
    return list
}

private fun findBrowser(): Browser? = browserCandidates().firstOrNull { it.exe.isFile }

/**
 * 网页登录窗口。
 *
 * @param storageKey 非空 = 登录态在 localStorage（如 123 云盘 authorToken）；空 = 登录态是 Cookie。
 * @param isValidCredential 判定抓到的凭证是否已是有效登录态（纯字符串判断，勿做网络请求）。
 * @param onCredential 抓到有效凭据后回调（Cookie 型为整串 Cookie，localStorage 型为 token）。
 * @param onStatus 进度提示（打开浏览器 / 等待登录 / 关闭）。
 * @param onError 失败原因（没装可用浏览器、端口被占、启动失败等）；调用方可提示用户改用手动粘贴。
 * @param confirm 登录态确认交互；传 null 表示不与用户确认，检测到有效登录态即保存（探针/测试用）
 */
class BrowserLogin(
    private val windowTitle: String,
    private val url: String,
    private val storageKey: String?,
    private val isValidCredential: (String) -> Boolean,
    private val onCredential: (String) -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val confirm: LoginConfirm? = null,
) {
    fun show() {
        if (!LOGIN_IN_PROGRESS.compareAndSet(false, true)) {
            onError("已有一个登录窗口在等待登录，请先在浏览器里完成或关闭它")
            return
        }
        Thread({ runLogin() }, "yunx-browser-login").apply { isDaemon = true }.start()
    }

    /** 用户至少选过一次「继续登录」：窗口关闭时据此提示"未保存" */
    @Volatile
    private var declinedOnce = false

    private fun runLogin() {
        var process: Process? = null
        var session: LoginSession? = null
        var usedBrowser: File? = null
        try {
            val browser = findBrowser() ?: run {
                onError("未找到可用的浏览器（Edge / Chrome / Brave / Firefox / 360 / QQ 等任一即可）：可改为在「网盘账号」页手动粘贴 Cookie / JWT")
                return
            }
            usedBrowser = browser.exe
            val profile = profileDirFor(browser.exe).apply { mkdirs() }

            val (port, launched) = openBrowser(browser, profile)
            process = launched
            session = attach(browser, port) ?: run {
                onError("无法连接 ${browser.name} 的调试端口（$port）：请关闭 YunX 打开的浏览器窗口后重试")
                return
            }
            session.navigate(url)
            onStatus("$windowTitle：已在 ${browser.name} 中打开登录页，登录成功后会自动关闭")

            val credential = poll(session)
            when {
                credential != null -> {
                    onCredential(credential)
                    onStatus("$windowTitle：已保存登录态 ✅")
                }
                declinedOnce -> onStatus("$windowTitle：窗口已关闭，登录态未保存")
                else -> onStatus("$windowTitle：窗口已关闭，未获取到登录态")
            }
        } catch (t: Throwable) {
            onError("网页登录失败：" + (t.message ?: t.javaClass.simpleName))
        } finally {
            runCatching { session?.close() }
            runCatching { session?.awaitClose(8) }
            runCatching { (session as? JsonWsSession)?.shutdown() }
            // 浏览器没被协议关掉就按进程收尾，别留下占着 profile 的孤儿进程
            runCatching {
                process?.let { if (!it.waitFor(8, TimeUnit.SECONDS)) it.destroyForcibly() }
            }
            runCatching { usedBrowser?.let { trimProfileCache(it) } }
            LOGIN_IN_PROGRESS.set(false)
        }
    }

    /** 返回 (调试端口, 本次启动的进程；复用时为 null)。 */
    private fun openBrowser(browser: Browser, profile: File): Pair<Int, Process?> {
        // 1) 已有实例在监听？直接复用（避免浏览器单例导致的"新进程立刻退出"）
        for (port in CANDIDATE_PORTS) {
            val v = httpGetJson("http://127.0.0.1:$port/json/version")
            if (v != null && v.optString("Browser").isNotBlank()) return port to null
        }
        // 2) 挑一个空闲端口启动
        val port = CANDIDATE_PORTS.firstOrNull { httpGetJson("http://127.0.0.1:$it/json/version") == null }
            ?: CANDIDATE_PORTS.first()

        val args = when (browser.engine) {
            Engine.CHROMIUM -> listOf(
                browser.exe.absolutePath,
                "--remote-debugging-port=$port",
                "--user-data-dir=${profile.absolutePath}",
                "--no-first-run", "--no-default-browser-check", "--disable-sync",
                "--disable-component-update",           // 别让它下载组件缓存（几十 MB）
                "--disk-cache-size=8388608",            // 磁盘缓存封顶 8 MB
                "--media-cache-size=1048576",
                "--window-size=1100,860",
                "about:blank",
            )
            Engine.FIREFOX -> {
                // 关键：Release 版 Firefox 默认**不启动** Remote Agent —— 实测不写这几行端口根本不监听。
                // 写进 profile 的 user.js（Firefox 每次启动都会读），并强制只监听本机。
                File(profile, "user.js").writeText(
                    "user_pref(\"remote.enabled\", true);\n" +
                        "user_pref(\"remote.force-local\", true);\n" +
                        "user_pref(\"remote.active-protocols\", 2);\n",
                    Charsets.UTF_8,
                )
                listOf(
                    browser.exe.absolutePath,
                    "-no-remote",                            // 关键：不要接管用户正在用的 Firefox
                    "-profile", profile.absolutePath,
                    "--remote-debugging-port=$port",         // 启动 Remote Agent（WebDriver BiDi）
                    "-width", "1100", "-height", "860",
                    "about:blank",
                )
            }
        }
        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            // Chromium 用 /json/version 探活；Firefox 没有该端点（CDP 已于 FF 141 移除）→ 直接探 TCP 端口
            val ready = when (browser.engine) {
                Engine.CHROMIUM -> httpGetJson("http://127.0.0.1:$port/json/version") != null
                Engine.FIREFOX -> isPortOpen(port)
            }
            if (ready) break
            if (!proc.isAlive) break
            Thread.sleep(300)
        }
        return port to proc
    }

    private fun isPortOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500) }
        true
    }.getOrDefault(false)

    /** 按内核类型建立会话。 */
    private fun attach(browser: Browser, port: Int): LoginSession? {
        return when (browser.engine) {
            Engine.CHROMIUM -> {
                var wsUrl: String? = null
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline && wsUrl == null) {
                    val list = httpGetArray("http://127.0.0.1:$port/json/list")
                    wsUrl = (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }
                        .firstOrNull { it.optString("type") == "page" }?.optString("webSocketDebuggerUrl")
                    if (wsUrl.isNullOrBlank()) { wsUrl = null; Thread.sleep(300) }
                }
                wsUrl?.let { url ->
                    val s = CdpSession(url)
                    s.connectAndInit()
                    s
                }
            }
            Engine.FIREFOX -> {
                // Firefox 的 Remote Agent 端点是固定路径 /session（新版不再提供 /json/version）；
                // 首次探测时若被端口/会话拒绝，稍后重试即可。
                var session: LoginSession? = null
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline && session == null) {
                    session = runCatching {
                        val s = BiDiSession("ws://127.0.0.1:$port/session")
                        if (s.connectAndInit()) s else null
                    }.getOrNull()
                    if (session == null) Thread.sleep(500)
                }
                session
            }
        }
    }

    /**
     * 轮询直到拿到有效凭据（返回凭证）或被用户关窗（返回 null）。
     * 弹框期间**不阻塞**：继续检测登录态变化，并把弹框里的凭证刷新为最新值，
     * 这样用户点「保存并关闭」时保存的是当前最新的登录态。
     */
    private fun poll(session: LoginSession): String? {
        val cookiePairs = LinkedHashMap<String, String>()
        var askedFor: Int? = null
        while (!Thread.currentThread().isInterrupted) {
            Thread.sleep(POLL_MS)
            if (session.isClosed) return null
            val cred = if (storageKey != null) session.localStorage(storageKey)
                       else joinCookies(session, cookiePairs)
            if (cred.isNullOrBlank() || !isValidCredential(cred)) continue

            val gate = confirm ?: return cred        // 无确认交互：直接保存

            gate.takeIfSaveRequested()?.let { return it }   // 用户点了「保存并关闭」
            if (gate.consumeDecline()) { declinedOnce = true; continue }  // 点了「继续登录」：同一份登录态不再追问

            if (askedFor != cred.hashCode()) {
                askedFor = cred.hashCode()
                gate.offer(windowTitle, cred)        // 首次识别 / 登录态变化 → （重新）询问
            } else {
                gate.refresh(cred)                   // 弹框期间保持最新
            }
        }
        return null
    }

    /** 取该站 cookie 并拼成 "k=v; k2=v2"（含 HttpOnly —— 页面 JS 拿不到，调试协议可以）。 */
    private fun joinCookies(session: LoginSession, sink: LinkedHashMap<String, String>): String? {
        val domain = siteDomain()
        session.cookiesFor(domain, listOf(url, "https://$domain/")).forEach { c ->
            if (c.name.isEmpty()) return@forEach
            sink.remove(c.name)
            sink[c.name] = c.value
            if (sink.size > MAX_COOKIE_PAIRS) sink.remove(sink.keys.first())
        }
        return if (sink.isEmpty()) null else sink.entries.joinToString("; ") { it.key + "=" + it.value }
    }

    /** 例如 https://pan.baidu.com/ → baidu.com；https://yun.139.com/m/#/login → 139.com */
    private fun siteDomain(): String {
        val host = runCatching { URI.create(url).host.orEmpty() }.getOrDefault("")
        val parts = host.split('.').filter { it.isNotBlank() }
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    /** 登录完成后清掉浏览器缓存类目录（保留 Cookies / Local Storage / Login Data 等登录态）。 */
    private fun trimProfileCache(browser: File) {
        val chromiumCache = listOf("Cache", "Code Cache", "GPUCache", "DawnGraphiteCache", "DawnWebGPUCache",
            "GrShaderCache", "ShaderCache", "BrowserMetrics", "component_crx_cache",
            "Edge Entity Extraction", "EdgeLanguageDetectionModel")
        val firefoxCache = listOf("cache2", "startupCache", "shader-cache", "thumbnails", "safebrowsing",
            "crashes", "datareporting", "saved-telemetry-pings")
        val names = if (browser.name.startsWith("firefox", ignoreCase = true)) firefoxCache else chromiumCache
        val root = profileDirFor(browser)
        if (root.isDirectory) {
            for (name in names) File(root, name).takeIf { it.exists() }?.deleteRecursively()
            File(root, "Default").listFiles()?.forEach { child ->
                if (child.isDirectory && names.contains(child.name)) child.deleteRecursively()
            }
        }
    }

    private companion object {
        const val POLL_MS = 2500L
        const val MAX_COOKIE_PAIRS = 300
    }
}

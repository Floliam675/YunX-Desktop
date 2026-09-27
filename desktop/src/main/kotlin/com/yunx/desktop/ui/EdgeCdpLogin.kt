/*
 * YunX Desktop - AGPL-3.0.
 * 网页登录（Edge + CDP 版）：借用户已安装的 Microsoft Edge 完成登录，再通过 DevTools
 * Protocol 取回登录态。替代原先内嵌 Chromium(JCEF) 的实现 —— 包体因此省下约 139 MB。
 *
 * 为什么可行：
 *   - Edge 在 Windows 10/11 上必然存在，不需要我们随包分发浏览器；
 *   - CDP 的网络层能看到 **HttpOnly** 凭据（百度 BDUSS、夸克 grey-id、UC UDRIVE_* 等），
 *     页面 JS 的 document.cookie 看不到它们 —— 这正是旧 JCEF 方案不得不嗅探请求头的原因；
 *   - localStorage 型平台（123 云盘 authorToken）用 Runtime.evaluate 读同源存储。
 *
 * 安全边界：
 *   - 只为自有/授权账号服务，不绕过验证码、风控与访问控制；
 *   - 调试端口只绑 127.0.0.1，且仅在本进程存活期间开放；
 *   - 专用 profile 放在应用数据目录，不与用户日常浏览器配置混用；
 *   - 凭据只交给调用方（走既有 AES-GCM 本地加密存储），不打印、不外发。
 */
package com.yunx.desktop.ui

import com.yunx.desktop.AppServices
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** 只在 127.0.0.1 上开放；被别的进程占用时依次顺延。 */
private val CANDIDATE_PORTS = listOf(9333, 9334, 9335, 9336)

private val PROFILE_DIR: File
    get() = File(AppServices.dataDir(), "edge-login-profile")

/** 同一时刻只允许一个登录会话：Edge 对同一 user-data-dir 是单例，起第二个会直接转发到第一个。 */
private val LOGIN_IN_PROGRESS = AtomicBoolean(false)

private fun findEdge(): File? = listOf(
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    System.getenv("LOCALAPPDATA")?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
    System.getenv("PROGRAMFILES(X86)")?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
).filterNotNull().map(::File).firstOrNull { it.isFile }

private fun httpGetJson(url: String): JSONObject? = runCatching {
    val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build()
    val resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() == 200) JSONObject(resp.body()) else null
}.getOrNull()

private fun httpGetArray(url: String): JSONArray? = runCatching {
    val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build()
    val resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() == 200) JSONArray(resp.body()) else null
}.getOrNull()

/** 最小同步 CDP 客户端（JDK WebSocket，无第三方依赖）。 */
private class Cdp(private val wsUrl: String) {
    private val http = HttpClient.newHttpClient()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
    private val closed = CountDownLatch(1)
    private lateinit var ws: WebSocket
    private var seq = 0

    val isClosed: Boolean get() = closed.count == 0L

    fun connect() {
        ws = http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create(wsUrl), object : WebSocket.Listener {
                private val buf = StringBuilder()
                override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    buf.append(data)
                    if (last) {
                        val text = buf.toString(); buf.setLength(0)
                        runCatching {
                            val obj = JSONObject(text)
                            val id = obj.optInt("id", -1)
                            if (id > 0) pending.remove(id)?.complete(obj)
                        }
                    }
                    socket.request(1)
                    return null
                }
                override fun onClose(socket: WebSocket, code: Int, reason: String): CompletionStage<*>? {
                    closed.countDown(); return null
                }
                override fun onError(socket: WebSocket, error: Throwable) { closed.countDown() }
            }).get(15, TimeUnit.SECONDS)
    }

    /** @return 响应 JSON；连接已断开或超时返回 null（调用方据此判定窗口被用户关闭） */
    fun call(method: String, params: JSONObject = JSONObject()): JSONObject? {
        if (isClosed) return null
        val id = ++seq
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        return try {
            ws.sendText(JSONObject().put("id", id).put("method", method).put("params", params).toString(), true)
            future.get(15, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            pending.remove(id)
            null
        }
    }

    fun awaitClose(seconds: Long) { closed.await(seconds, TimeUnit.SECONDS) }
}

/**
 * 网页登录窗口（Edge + CDP）。
 *
 * @param storageKey 非空 = 登录态在 localStorage（如 123 云盘 authorToken）；空 = 登录态是 Cookie。
 * @param isValidCredential 判定抓到的凭证是否已是有效登录态（纯字符串判断，勿做网络请求）。
 * @param onCredential 抓到有效凭据后回调（Cookie 型为整串 Cookie，localStorage 型为 token）。
 * @param onStatus 进度提示（打开浏览器 / 等待登录 / 关闭）。
 * @param onError 失败原因（未装 Edge、端口被占、启动失败等）；调用方可提示用户改用手动粘贴。
 */
class EdgeCdpLogin(
    private val windowTitle: String,
    private val url: String,
    private val storageKey: String?,
    private val isValidCredential: (String) -> Boolean,
    private val onCredential: (String) -> Unit,
    private val onStatus: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    fun show() {
        if (!LOGIN_IN_PROGRESS.compareAndSet(false, true)) {
            onError("已有一个登录窗口在等待登录，请先在浏览器里完成或关闭它")
            return
        }
        Thread({ runLogin() }, "yunx-edge-login").apply { isDaemon = true }.start()
    }

    private fun runLogin() {
        var process: Process? = null
        var cdp: Cdp? = null
        try {
            val edge = findEdge() ?: run {
                onError("未找到 Microsoft Edge：可改为在「网盘账号」页手动粘贴 Cookie / JWT")
                return
            }
            val profile = PROFILE_DIR.apply { mkdirs() }

            // 复用上一次残留的实例（例如上次异常退出）或占用顺延端口
            val (port, launched) = openBrowser(edge, profile)
            process = launched
            cdp = attach(port) ?: run {
                onError("无法连接浏览器调试端口（$port）：请关闭 YunX 打开的 Edge 窗口后重试")
                return
            }
            cdp.call("Page.enable")
            cdp.call("Network.enable")
            cdp.call("Page.navigate", JSONObject().put("url", url))
            onStatus("$windowTitle：请在浏览器窗口完成登录，登录成功后会自动关闭")

            val credential = poll(cdp)
            if (credential != null) {
                onCredential(credential)
                onStatus("$windowTitle：已获取登录态 ✅")
            } else {
                onStatus("$windowTitle：窗口已关闭，未获取到登录态")
            }
        } catch (t: Throwable) {
            onError("网页登录失败：" + (t.message ?: t.javaClass.simpleName))
        } finally {
            runCatching { cdp?.call("Browser.close") }
            runCatching { cdp?.awaitClose(8) }
            // 浏览器没被 CDP 关掉就按进程收尾，别留下占着 profile 的孤儿 Edge
            runCatching {
                process?.let {
                    if (!it.waitFor(8, TimeUnit.SECONDS)) it.destroyForcibly()
                }
            }
            runCatching { trimProfileCache() }
            LOGIN_IN_PROGRESS.set(false)
        }
    }

    /** 返回 (调试端口, 本次启动的 Edge 进程；复用时为 null)。 */
    private fun openBrowser(edge: File, profile: File): Pair<Int, Process?> {
        // 1) 已有实例在监听？直接复用（避免 Edge 单例导致的"新进程立刻退出"）
        for (port in CANDIDATE_PORTS) {
            val v = httpGetJson("http://127.0.0.1:$port/json/version")
            if (v != null && v.optString("Browser").contains("Edg", ignoreCase = true)) return port to null
        }
        // 2) 挑一个空闲端口启动
        val port = CANDIDATE_PORTS.firstOrNull { httpGetJson("http://127.0.0.1:$it/json/version") == null }
            ?: CANDIDATE_PORTS.first()

        val args = listOf(
            edge.absolutePath,
            "--remote-debugging-port=$port",
            "--user-data-dir=${profile.absolutePath}",
            "--no-first-run", "--no-default-browser-check", "--disable-sync",
            "--disable-component-update",           // 别让它下载组件缓存（几十 MB）
            "--disk-cache-size=8388608",            // 磁盘缓存封顶 8 MB
            "--media-cache-size=1048576",
            "--window-size=1100,860",
            "about:blank",
        )
        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        // 等 CDP 就绪
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (httpGetJson("http://127.0.0.1:$port/json/version") != null) break
            if (!proc.isAlive) break
            Thread.sleep(250)
        }
        return port to proc
    }

    /** 连接到第一个 page target（新启或复用都适用）。 */
    private fun attach(port: Int): Cdp? {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val list = httpGetArray("http://127.0.0.1:$port/json/list")
            val page = (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }
                .firstOrNull { it.optString("type") == "page" }
            if (page != null) {
                val cdp = Cdp(page.getString("webSocketDebuggerUrl"))
                cdp.connect()
                return cdp
            }
            Thread.sleep(300)
        }
        return null
    }

    /** 轮询直到拿到有效凭据（返回凭证）或被用户关窗（返回 null）。 */
    private fun poll(cdp: Cdp): String? {
        val cookiePairs = LinkedHashMap<String, String>()
        while (!Thread.currentThread().isInterrupted) {
            Thread.sleep(POLL_MS)
            if (cdp.isClosed) return null
            val cred = if (storageKey != null) readLocalStorage(cdp, storageKey) else readCookies(cdp, cookiePairs)
            if (!cred.isNullOrBlank() && isValidCredential(cred)) return cred
        }
        return null
    }

    /** Cookie 型：取该站点全域 Cookie（含 HttpOnly —— 页面 JS 拿不到，CDP 网络层可以）。 */
    private fun readCookies(cdp: Cdp, sink: LinkedHashMap<String, String>): String? {
        val result = cdp.call("Network.getAllCookies")?.optJSONObject("result")?.optJSONArray("cookies") ?: return null
        for (i in 0 until result.length()) {
            val c = result.getJSONObject(i)
            if (!c.optString("domain").endsWith(siteDomain(), ignoreCase = true)) continue
            val name = c.optString("name")
            val value = c.optString("value")
            if (name.isNotEmpty()) {
                sink.remove(name)
                sink[name] = value
                if (sink.size > MAX_COOKIE_PAIRS) sink.remove(sink.keys.first())
            }
        }
        return if (sink.isEmpty()) null else sink.entries.joinToString("; ") { it.key + "=" + it.value }
    }

    /** localStorage 型：在页面同源上下文里读键值。 */
    private fun readLocalStorage(cdp: Cdp, key: String): String? {
        val expression = "(()=>{try{return window.localStorage.getItem(" + JSONObject.quote(key) + ")}catch(e){return null}})()"
        val resp = cdp.call("Runtime.evaluate", JSONObject().put("expression", expression).put("returnByValue", true))
        val value = resp?.optJSONObject("result")?.optJSONObject("result")?.opt("value") ?: return null
        return if (value == JSONObject.NULL) null else value.toString()
    }

    /** 例如 https://pan.baidu.com/ → baidu.com；https://yun.139.com/m/#/login → 139.com */
    private fun siteDomain(): String {
        val host = runCatching { URI.create(url).host.orEmpty() }.getOrDefault("")
        val parts = host.split('.').filter { it.isNotBlank() }
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
    }

    /** 登录完成后清掉浏览器缓存类目录（保留 Cookies / Local Storage / Login Data 等登录态）。 */
    private fun trimProfileCache() {
        val keepAlive = listOf("Cache", "Code Cache", "GPUCache", "DawnGraphiteCache", "DawnWebGPUCache",
            "GrShaderCache", "ShaderCache", "BrowserMetrics", "component_crx_cache",
            "Edge Entity Extraction", "EdgeLanguageDetectionModel")
        val root = PROFILE_DIR
        if (root.isDirectory) {
            for (name in keepAlive) File(root, name).takeIf { it.exists() }?.deleteRecursively()
            File(root, "Default").listFiles()?.forEach { child ->
                if (child.isDirectory && keepAlive.contains(child.name)) child.deleteRecursively()
            }
        }
    }

    private companion object {
        const val POLL_MS = 1500L
        const val MAX_COOKIE_PAIRS = 300
    }
}

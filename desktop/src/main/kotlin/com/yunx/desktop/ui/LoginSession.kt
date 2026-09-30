/*
 * YunX Desktop - AGPL-3.0.
 * 浏览器登录的协议层：上层只管「导航 / 取该站 cookie / 读 localStorage / 关闭」，
 * 具体协议分两套实现 ——
 *   · Chromium 系（Edge / Chrome / Brave / Vivaldi / Opera / 360 / QQ / 搜狗）：DevTools Protocol (CDP)
 *   · Firefox：WebDriver BiDi（Firefox 已移除 CDP 支持，只能用 BiDi）
 * 两套都用 JDK 自带 java.net.http 的 WebSocket，无第三方依赖。
 */
package com.yunx.desktop.ui

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
import org.json.JSONArray
import org.json.JSONObject

/** 站点 cookie 的通用表示（httpOnly 只用于日志/判定，不参与拼接）。 */
internal data class SiteCookie(val name: String, val value: String, val domain: String, val httpOnly: Boolean)

/** 浏览器登录会话：把两种协议的差异关在这扇门后面。 */
internal interface LoginSession {
    val isClosed: Boolean
    fun navigate(url: String)
    /** 取该站点的 cookie；urls 供 CDP 按 URL 过滤使用（BiDi 用 domain 过滤） */
    fun cookiesFor(domain: String, urls: List<String>): List<SiteCookie>
    /** 读页面 localStorage[key]，取不到返回 null */
    fun localStorage(key: String): String?
    fun close()
    fun awaitClose(seconds: Long)
}

// ---------------------------------------------------------------- 公共小工具

internal fun httpGetJson(url: String): JSONObject? = runCatching {
    val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build()
    val resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() == 200) JSONObject(resp.body()) else null
}.getOrNull()

internal fun httpGetArray(url: String): JSONArray? = runCatching {
    val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build()
    val resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() == 200) JSONArray(resp.body()) else null
}.getOrNull()

/** 极简请求/响应式 WebSocket 客户端：按 id 匹配响应，事件类消息忽略。 */
internal abstract class JsonWsSession(private val wsUrl: String) {
    private val http = HttpClient.newHttpClient()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
    private val closed = CountDownLatch(1)
    private lateinit var ws: WebSocket
    private var seq = 0

    val isClosed: Boolean get() = closed.count == 0L

    protected fun connect() {
        ws = http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create(wsUrl), object : WebSocket.Listener {
                private val buf = StringBuilder()
                override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    buf.append(data)
                    if (last) {
                        val text = buf.toString(); buf.setLength(0)
                        runCatching { onMessage(JSONObject(text)) }
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

    private fun onMessage(obj: JSONObject) {
        val id = obj.optInt("id", -1)
        if (id > 0) pending.remove(id)?.complete(obj) else onEvent(obj)
    }

    /** 子类可覆写处理事件（两套协议的事件都用不到，默认忽略） */
    protected open fun onEvent(obj: JSONObject) {}

    /** 发送一条请求并等待响应；连接断开/超时返回 null */
    protected fun send(payload: JSONObject, timeoutSeconds: Long = 15): JSONObject? {
        if (isClosed) return null
        val id = ++seq
        payload.put("id", id)
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        return try {
            ws.sendText(payload.toString(), true)
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            pending.remove(id)
            null
        }
    }

    fun awaitClose(seconds: Long) { closed.await(seconds, TimeUnit.SECONDS) }

    fun shutdown() { runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye") } }
}

// ---------------------------------------------------------------- CDP（Chromium 系）

/** Chromium 内核浏览器的 DevTools Protocol 会话。 */
internal class CdpSession(wsUrl: String) : JsonWsSession(wsUrl), LoginSession {

    fun connectAndInit() { connect() }

    override fun navigate(url: String) {
        // 刻意不 enable Page/Network 域：enable 后页面每 10 秒会推来几十条事件（页面繁忙时更多），
        // 而下面这些命令无须 enable 即可用。
        send(JSONObject().put("method", "Page.navigate").put("params", JSONObject().put("url", url)))
    }

    override fun cookiesFor(domain: String, urls: List<String>): List<SiteCookie> {
        val arr = JSONArray().apply { urls.forEach { put(it) } }
        val resp = send(JSONObject().put("method", "Network.getCookies").put("params", JSONObject().put("urls", arr)))
            ?: return emptyList()
        val cookies = resp.optJSONObject("result")?.optJSONArray("cookies") ?: return emptyList()
        return (0 until cookies.length()).mapNotNull { i ->
            val c = cookies.getJSONObject(i)
            val d = c.optString("domain")
            if (!d.endsWith(domain, ignoreCase = true)) null
            else SiteCookie(c.optString("name"), c.optString("value"), d, c.optBoolean("httpOnly"))
        }
    }

    override fun localStorage(key: String): String? {
        val expr = "(()=>{try{return window.localStorage.getItem(" + JSONObject.quote(key) + ")}catch(e){return null}})()"
        val resp = send(JSONObject().put("method", "Runtime.evaluate")
            .put("params", JSONObject().put("expression", expr).put("returnByValue", true))) ?: return null
        val v = resp.optJSONObject("result")?.optJSONObject("result")?.opt("value") ?: return null
        return if (v == JSONObject.NULL) null else v.toString()
    }

    override fun close() { send(JSONObject().put("method", "Browser.close"), timeoutSeconds = 5) }
}

// ---------------------------------------------------------------- WebDriver BiDi（Firefox）

/**
 * Firefox 的 WebDriver BiDi 会话。
 * 连接后必须先 `session.new` 建立会话；之后命令直接挂在会话上（消息里不带 sessionId）。
 */
internal class BiDiSession(wsUrl: String) : JsonWsSession(wsUrl), LoginSession {

    private var context: String? = null

    /** 连接 + 建立会话 + 取顶层 browsingContext */
    fun connectAndInit(): Boolean {
        connect()
        val created = send(JSONObject().put("method", "session.new")
            .put("params", JSONObject().put("capabilities", JSONObject()))) ?: return false
        if (created.has("error")) return false
        context = topContext()
        return context != null
    }

    private fun topContext(): String? {
        val tree = send(JSONObject().put("method", "browsingContext.getTree")
            .put("params", JSONObject())) ?: return null
        val contexts = tree.optJSONObject("result")?.optJSONArray("contexts") ?: return null
        return (0 until contexts.length()).map { contexts.getJSONObject(it) }
            .firstOrNull { it.optString("context").isNotBlank() }?.optString("context")
    }

    private fun ctx(): String? = context ?: topContext()?.also { context = it }

    override fun navigate(url: String) {
        val c = ctx() ?: return
        send(JSONObject().put("method", "browsingContext.navigate")
            .put("params", JSONObject().put("context", c).put("url", url).put("wait", "none")))
    }

    override fun cookiesFor(domain: String, urls: List<String>): List<SiteCookie> {
        // 刻意**不带 filter**：BiDi 的 filter.domain 语义（是否匹配子域 / 是否要带点）在各版本上不一致，
        // 而这是我们自己的专用 profile，cookie 总量很小 —— 全量取回来在本地按域过滤最稳。
        val resp = send(JSONObject().put("method", "storage.getCookies").put("params", JSONObject())) ?: return emptyList()
        val cookies = resp.optJSONObject("result")?.optJSONArray("cookies") ?: return emptyList()
        val want = domain.removePrefix(".").lowercase()
        return (0 until cookies.length()).mapNotNull { i ->
            val c = cookies.getJSONObject(i)
            val d = c.optString("domain").removePrefix(".").lowercase()
            if (!d.endsWith(want)) return@mapNotNull null
            // 注意：BiDi 的 cookie 值是 BytesValue 对象 {"type":"string","value":"…"}，
            // 早期实现直接 optString("value") 会把整个 JSON 当值写进 cookie 串（实测被服务端拒绝）。
            val name = c.opt("name").let { if (it is String) it else null }?.takeIf { it.isNotBlank() && it != "undefined" }
                ?: return@mapNotNull null
            val value = biDiValue(c.opt("value")) ?: return@mapNotNull null
            SiteCookie(name, value, c.optString("domain"), c.optBoolean("httpOnly"))
        }
    }

    override fun localStorage(key: String): String? {
        val c = ctx() ?: return null
        val expr = "(()=>{try{return window.localStorage.getItem(" + JSONObject.quote(key) + ")}catch(e){return null}})()"
        val resp = send(JSONObject().put("method", "script.evaluate")
            .put("params", JSONObject().put("expression", expr)
                .put("target", JSONObject().put("context", c))
                .put("awaitPromise", false))) ?: return null
        return biDiValue(resp.optJSONObject("result")?.opt("result"))
    }

    /** 解出 BiDi 的远程值：{"type":"string","value":…} / {"type":"base64",…} / 个别实现直接给标量 */
    private fun biDiValue(raw: Any?): String? = when (raw) {
        null, JSONObject.NULL -> null
        is String -> raw
        is JSONObject -> when (raw.optString("type")) {
            "string" -> raw.optString("value")
            "base64" -> runCatching {
                String(java.util.Base64.getDecoder().decode(raw.optString("value")), Charsets.UTF_8)
            }.getOrNull()
            else -> raw.opt("value")?.toString()
        }
        else -> raw.toString()
    }

    override fun close() { send(JSONObject().put("method", "browser.close"), timeoutSeconds = 5) }
}

/*
 * YunX Desktop - AGPL-3.0. Automated probe for the JCEF windowed web-login capture.
 * Starts a tiny local HTTP server (jdk.httpserver) and verifies BOTH capture paths:
 *   - cookie platform path (incl. HttpOnly cookie visibility via CefCookieManager)
 *   - localStorage platform path (token bridged to a marker cookie by injected JS)
 * Reuses one CefApp for two sequential login windows to prove singleton reuse.
 */
package com.yunx.desktop

import com.sun.net.httpserver.HttpServer
import com.yunx.desktop.ui.JcefWebLogin
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

fun main(args: Array<String>) {
    System.setProperty("yunx.jcef.silent", "1")
    System.setProperty("yunx.jcef.debug", "1")
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/cookie.html") { ex ->
        // 每个认证 cookie 放在独立响应里（模拟百度 BDUSS HttpOnly + 其它会话 cookie）
        val body = "<html><body>cookie probe<img src='/c2'><img src='/c3'></body></html>"
        ex.responseHeaders.add("Set-Cookie", "sid=ABC123; Path=/; HttpOnly")
        val b = body.toByteArray()
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }
    server.createContext("/c2") { ex ->
        ex.responseHeaders.add("Set-Cookie", "uid=42; Path=/")
        val b = byteArrayOf(1)
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }
    server.createContext("/c3") { ex ->
        ex.responseHeaders.add("Set-Cookie", "extra=1; Path=/")
        val b = byteArrayOf(1)
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }
    server.createContext("/ls.html") { ex ->
        val body = "<html><body><script>window.localStorage.setItem('authorToken','TOK123LS');</script>ls probe</body></html>"
        val b = body.toByteArray()
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }
    server.start()
    val base = "http://127.0.0.1:" + server.address.port

    val failures = AtomicReference<String?>()
    val results = java.util.Collections.synchronizedList(ArrayList<String>())
    val done = java.util.concurrent.CountDownLatch(2)

    fun step(name: String, body: () -> Unit) {
        try { body() } catch (t: Throwable) { failures.compareAndSet(null, name + ": " + (t.message ?: t.toString())) }
    }

    step("cookie") {
        JcefWebLogin(
            "probe-cookie", base + "/cookie.html", null,
            { c: String -> c.contains("sid=ABC123") && c.contains("uid=42") && c.contains("extra=1") },
            { c ->
                results.add("COOKIE-OK:" + c)
                done.countDown()
            }
        ).show()
    }

    step("localStorage") {
        JcefWebLogin(
            "probe-ls", base + "/ls.html", "authorToken",
            { t: String -> t == "TOK123LS" },
            { t ->
                results.add("LS-OK:" + t)
                done.countDown()
            }
        ).show()
    }

    val finished = done.await(90, java.util.concurrent.TimeUnit.SECONDS)
    server.stop(0)

    if (failures.get() != null) {
        System.err.println("[probe] FAILURE: " + failures.get())
        System.exit(1)
    }
    if (finished && results.size == 2) {
        results.forEach { println("[probe] " + it) }
        println("[probe] PASS")
        System.exit(0)
    }
    System.err.println("[probe] TIMEOUT collected=" + results)
    System.exit(2)
}

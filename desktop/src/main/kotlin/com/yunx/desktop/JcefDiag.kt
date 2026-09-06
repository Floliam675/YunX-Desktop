/*
 * YunX Desktop - AGPL-3.0. Diagnostic for JCEF windowed load + cookie store.
 */
package com.yunx.desktop

import com.sun.net.httpserver.HttpServer
import com.yunx.desktop.ui.JcefRuntime
import org.cef.CefApp
import org.cef.callback.CefCookieVisitor
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.io.File
import java.net.InetSocketAddress
import javax.swing.JFrame
import javax.swing.SwingUtilities

fun main() {
    System.setProperty("yunx.jcef.silent", "1")
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/cookie.html") { ex ->
        val body = "<html><body>cookie probe</body></html>"
        ex.responseHeaders.add("Set-Cookie", "uid=42; Path=/")
        ex.responseHeaders.add("Set-Cookie", "sid=ABC123; Path=/; HttpOnly")
        val b = body.toByteArray()
        ex.sendResponseHeaders(200, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }
    server.start()
    val base = "http://127.0.0.1:" + server.address.port
    println("[diag] base=" + base)

    try {
        val app = JcefRuntime.ensureStarted()
        println("[diag] state=" + CefApp.getState())
        SwingUtilities.invokeAndWait {
            val client = app.createClient()
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadingStateChange(b: org.cef.browser.CefBrowser, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
                    println("[diag] loading=" + isLoading)
                }
                override fun onLoadEnd(b: org.cef.browser.CefBrowser, frame: org.cef.browser.CefFrame, code: Int) {
                    println("[diag] loadEnd main=" + frame.isMain + " code=" + code + " url=" + b.url)
                }
                override fun onLoadError(b: org.cef.browser.CefBrowser, frame: org.cef.browser.CefFrame, err: org.cef.handler.CefLoadHandler.ErrorCode, txt: String, url: String) {
                    println("[diag] loadError=" + err + " txt=" + txt + " url=" + url)
                }
            })
            val b = client.createBrowser(base + "/cookie.html", false, false)
            val f = JFrame("diag")
            f.contentPane.add(b.getUIComponent())
            f.setSize(800, 500)
            f.isVisible = true
        }
        // dump cookies over time
        for (i in 0 until 6) {
            Thread.sleep(3000)
            val dump = dumpCookies()
            println("[diag] t=" + ((i + 1) * 3) + "s cookies=[" + dump + "]")
        }
        println("[diag] DONE")
        System.exit(0)
    } catch (t: Throwable) {
        println("[diag] EXCEPTION " + t)
        t.printStackTrace()
        System.exit(1)
    }
}

private fun dumpCookies(): String {
    val mgr = CefCookieManager.getGlobalManager() ?: return "(no manager)"
    val sb = StringBuilder()
    SwingUtilities.invokeAndWait {
        mgr.visitAllCookies(object : CefCookieVisitor {
            override fun visit(cookie: CefCookie, count: Int, total: Int, deleteCookie: BoolRef): Boolean {
                sb.append(cookie.name).append("=").append(cookie.value).append(" | ")
                return true
            }
        })
    }
    return sb.toString().ifEmpty { "(empty)" }
}

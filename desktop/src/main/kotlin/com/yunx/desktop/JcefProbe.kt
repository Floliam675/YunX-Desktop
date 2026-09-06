package com.yunx.desktop

import me.friwi.jcefmaven.CefAppBuilder
import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.io.File
import javax.swing.JFrame
import javax.swing.SwingUtilities

fun main() {
    val builder = CefAppBuilder()
    builder.setInstallDir(File(System.getProperty("user.home") + "/.jcef-bundle"))
    builder.getCefSettings().windowless_rendering_enabled = false
    val app = builder.build()
    println("[jcef] CefApp started")
    val client = app.createClient()
    val url = "https://pan.quark.cn/"
    val browser = client.createBrowser(url, false, false)
    val ui = browser.getUIComponent()
    val frame = JFrame("JCEF Probe")
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    frame.contentPane.add(ui)
    frame.setSize(1000, 700)
    SwingUtilities.invokeLater { frame.isVisible = true }
    Thread {
        Thread.sleep(15000)
        SwingUtilities.invokeAndWait {
            val mgr = CefCookieManager.getGlobalManager()
            val sb = StringBuilder()
            mgr.visitAllCookies(object : CefCookieVisitor {
                override fun visit(cookie: CefCookie, count: Int, total: Int, deleteCookie: BoolRef): Boolean {
                    sb.append(cookie.name).append("=").append(cookie.value).append(" ; ")
                    return true
                }
            })
            val cookieText = sb.toString().ifEmpty { "(none yet)" }
            println("[jcef] COOKIES:" + System.lineSeparator() + cookieText)
            frame.dispose()
            app.dispose()
            System.exit(0)
        }
    }.start()
}

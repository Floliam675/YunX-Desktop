/*
 * YunX Desktop - AGPL-3.0. Minimal android.util.Log stub: prints to System.out/err.
 */
@file:Suppress("unused", "PackageDirectoryMismatch")
package android.util

object Log {
    private fun tag(s: String?) = s ?: "YunX"
    fun v(tag: String?, msg: String) = println("V/" + tag(tag) + ": " + msg)
    fun d(tag: String?, msg: String) = println("D/" + tag(tag) + ": " + msg)
    fun i(tag: String?, msg: String) = println("I/" + tag(tag) + ": " + msg)
    fun w(tag: String?, msg: String) = println("W/" + tag(tag) + ": " + msg)
    fun w(tag: String?, msg: String, tr: Throwable) = println("W/" + tag(tag) + ": " + msg + " | " + tr)
    fun e(tag: String?, msg: String) = System.err.println("E/" + tag(tag) + ": " + msg)
    fun e(tag: String?, msg: String, tr: Throwable) = System.err.println("E/" + tag(tag) + ": " + msg + " | " + tr)
}

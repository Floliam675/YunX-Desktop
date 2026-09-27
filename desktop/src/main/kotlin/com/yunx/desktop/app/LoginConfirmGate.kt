/*
 * YunX Desktop - AGPL-3.0.
 * 网页登录的「是否保存」确认闸门。
 *
 * 设计要点：
 *   1) 不阻塞检测：弹框期间登录轮询照常进行，登录态一旦变化会刷新框内信息，
 *      点「保存并关闭」时保存的是**最新**的登录态（用户在浏览器里换了账号也能跟上）；
 *   2) 同一份登录态只问一次：用户选「继续登录」后，只有登录态真的变了才会再问；
 *   3) 弹框出现时由 UI 把窗口提到最前并临时置顶（用户此时正在浏览器里，看不到 App）。
 *
 * 状态读取端（Compose）与写入端（后台登录线程）分离：写入用 SwingUtilities 投递到 UI 线程。
 */
package com.yunx.desktop.app

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import javax.swing.SwingUtilities

/**
 * 登录态确认交互（由 [LoginConfirmGate] 实现）：登录侧只依赖这个接口，
 * 便于在无界面场景（探针/测试）下传 null 直接保存。
 */
interface LoginConfirm {
    /** 弹出询问；可重复调用（登录态变化时刷新） */
    fun offer(title: String, credential: String)
    /** 弹框期间登录态变了 → 刷新为最新凭证 */
    fun refresh(credential: String)
    /** 用户选了「保存并关闭」→ 返回最新凭证；否则 null */
    fun takeIfSaveRequested(): String?
    /** 用户选了「继续登录」→ 返回 true（只生效一次） */
    fun consumeDecline(): Boolean
}

class LoginConfirmGate : LoginConfirm {

    /** 待确认的登录态；credential 会随检测结果刷新为最新值 */
    data class Pending(val title: String, val credential: String)

    private val _pending = mutableStateOf<Pending?>(null)

    /** 用户选择：null=尚未选择；true=保存并关闭；false=继续登录 */
    private val _decision = mutableStateOf<Boolean?>(null)

    val pending: State<Pending?> get() = _pending
    val decision: State<Boolean?> get() = _decision

    /** 后台线程调用：弹出确认框（同一平台重复调用只刷新凭证，不会覆盖用户已做的选择） */
    override fun offer(title: String, credential: String) {
        SwingUtilities.invokeLater {
            _pending.value = Pending(title, credential)
            _decision.value = null
        }
    }

    /** 后台线程调用：登录态在弹框期间又变了，刷新为最新值 */
    override fun refresh(credential: String) {
        val cur = _pending.value ?: return
        if (cur.credential == credential) return
        SwingUtilities.invokeLater { _pending.value = cur.copy(credential = credential) }
    }

    /** UI 线程调用：用户做出选择 */
    fun answer(save: Boolean) {
        _decision.value = save
        if (!save) _pending.value = null
    }

    /** 后台线程调用：用户选了「保存并关闭」→ 取走最新凭证并收尾；否则返回 null */
    override fun takeIfSaveRequested(): String? {
        if (_decision.value != true) return null
        val credential = _pending.value?.credential
        SwingUtilities.invokeLater { _pending.value = null; _decision.value = null }
        return credential
    }

    /** 后台线程调用：用户选了「继续登录」→ 清掉本轮选择，等登录态变化再问 */
    override fun consumeDecline(): Boolean {
        if (_decision.value != false) return false
        SwingUtilities.invokeLater { _pending.value = null; _decision.value = null }
        return true
    }

    /** 新一轮登录开始前重置 */
    fun reset() {
        SwingUtilities.invokeLater { _pending.value = null; _decision.value = null }
    }
}

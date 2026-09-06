/*
 * YunX Desktop - AGPL-3.0. Share-link resolve orchestration for desktop UI.
 * Ported logic reuse: ShareLinkParser + per-drive ResolveRepositories from core.
 */
package com.yunx.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.Pan123Constants
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.XunleiConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import com.yunx.app.data.repository.BaiduResolveRepository
import com.yunx.app.data.repository.C139ResolveRepository
import com.yunx.app.data.repository.Pan123ResolveRepository
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.repository.ShareResolveRepository
import com.yunx.app.data.repository.UCResolveRepository
import com.yunx.app.data.repository.XunleiResolveRepository
import com.yunx.app.data.download.DownloadPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class DriveShare(
    val platform: SharePlatform,
    val session: ShareSession,
    val files: List<ShareFile>,
    val currentDir: String, // fid of folder being viewed (root "")
)

/** 解析好的直链 + 平台下载信息（交给 DownloadManager 入队） */
data class ResolvedDownload(
    val url: String,
    val fileName: String,
    val size: Long,
    val headers: Map<String, String>,
    val platform: String,
    val cleanupDirFid: String?,
    val cleanup: suspend () -> Unit
)

/**
 * 解析编排器：粘贴分享链接 → 会话 → 文件浏览 → 取直链。
 * 平台凭证从 [login] 提供的 DriveAccountStore 读取。
 */
class ResolveController(private val login: DriveLogin) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var platform by mutableStateOf<SharePlatform?>(null); private set
    var share by mutableStateOf<DriveShare?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var downloading by mutableStateOf(false); private set
    var rootLink by mutableStateOf(""); private set

    private var repo: ShareResolveRepository? = null

    fun reset() {
        platform = null; share = null; repo = null; error = null; message = null
    }

    fun consumeError() { error = null }
    fun consumeMessage() { message = null }

    /** 解析分享链接（自动识别平台）→ 列出根目录 */
    fun resolve(link: String, pwd: String?) {
        rootLink = link
        error = null; message = null
        val parsed = ShareLinkParser.parse(link)
        if (parsed == null) { error = "无法识别该分享链接（支持夸克/UC/迅雷/百度/139/123）"; return }
        val p = parsed.platform
        if (p != SharePlatform.PAN123 && credentialOrEmpty(p).isBlank()) {
            error = "请先登录" + platformLabel(p) + "（在网盘页粘贴 Cookie 登录）"
            return
        }
        scope.launch {
            loading = true
            try {
                val r = buildRepo(p)
                val session = r.createSession(link, pwd, credentialOrEmpty(p)).getOrThrow()
                val files = r.listFiles(session, "", credentialOrEmpty(p)).getOrThrow()
                share = DriveShare(p, session, files, "")
                platform = p
                message = "解析成功：" + session.title.ifBlank { platformLabel(p) }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally { loading = false }
        }
    }

    fun openFolder(file: ShareFile) {
        val s = share ?: return; val r = repo ?: return
        if (!file.isdir) return
        scope.launch {
            loading = true
            try {
                val files = r.listFiles(s.session, file.fid, credentialOrEmpty(s.platform)).getOrThrow()
                share = s.copy(files = files, currentDir = file.fid)
            } catch (t: Throwable) { error = t.message ?: "打开目录失败" }
            finally { loading = false }
        }
    }

    fun goBackToRoot() {
        val s = share ?: return; val r = repo ?: return
        if (s.currentDir.isBlank()) return
        scope.launch {
            loading = true
            try {
                val files = r.listFiles(s.session, "", credentialOrEmpty(s.platform)).getOrThrow()
                share = s.copy(files = files, currentDir = "")
            } catch (t: Throwable) { error = t.message ?: "返回失败" }
            finally { loading = false }
        }
    }

    /** 单个文件取直链（失败返回 null，error 已置位） */
    suspend fun getDownloadFor(file: ShareFile): ResolvedDownload? {
        val s = share ?: return null; val r = repo ?: return null
        downloading = true
        return try {
            val cookie = credentialOrEmpty(s.platform)
            val link = r.getShareDownloadLink(s.session, file, cookie).getOrThrow()
            val (url, headers) = finalUrlAndHeaders(s.platform, link, cookie)
            val cleanup: suspend () -> Unit = {
                link.cleanupDirFid?.let { fid -> runCatching { r.cleanupTempDir(fid, cookie) } }
            }
            ResolvedDownload(
                url = url,
                fileName = file.fname,
                size = link.size.takeIf { it > 0 } ?: file.fsize,
                headers = headers,
                platform = platformTag(s.platform),
                cleanupDirFid = link.cleanupDirFid,
                cleanup = cleanup,
            )
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            null
        } finally { downloading = false }
    }

    private fun finalUrlAndHeaders(
        p: SharePlatform,
        link: DownloadLink,
        credential: String
    ): Pair<String, Map<String, String>> = when (p) {
        SharePlatform.XUNLEI -> link.downloadUrl to mapOf("User-Agent" to XunleiConstants.APP_UA)
        SharePlatform.BAIDU -> link.downloadUrl to mapOf("Cookie" to credential, "User-Agent" to BaiduConstants.UA_NETDISK)
        SharePlatform.C139 -> link.downloadUrl to mapOf("User-Agent" to C139Constants.PC_UA)
        SharePlatform.PAN123 -> link.downloadUrl to mapOf("User-Agent" to Pan123Constants.WEB_UA, "Referer" to Pan123Constants.DOWNLOAD_REFERER)
        SharePlatform.UC -> link.downloadUrl to mapOf("Cookie" to credential, "User-Agent" to UCConstants.USER_AGENT, "Referer" to UCConstants.DOWNLOAD_REFERER, "Origin" to UCConstants.WEB_ORIGIN)
        SharePlatform.QUARK -> link.downloadUrl to mapOf("Cookie" to credential, "User-Agent" to QuarkConstants.API_USER_AGENT, "Referer" to QuarkConstants.DOWNLOAD_REFERER)
    }

    private fun platformTag(p: SharePlatform): String = when (p) {
        SharePlatform.XUNLEI -> DownloadPlatform.XUNLEI
        SharePlatform.BAIDU -> DownloadPlatform.BAIDU
        SharePlatform.C139 -> DownloadPlatform.C139
        SharePlatform.PAN123 -> DownloadPlatform.PAN123
        SharePlatform.UC -> DownloadPlatform.UC
        SharePlatform.QUARK -> DownloadPlatform.QUARK
    }

    private fun platformLabel(p: SharePlatform): String = when (p) {
        SharePlatform.QUARK -> "夸克网盘"
        SharePlatform.UC -> "UC网盘"
        SharePlatform.XUNLEI -> "迅雷网盘"
        SharePlatform.BAIDU -> "百度网盘"
        SharePlatform.C139 -> "139网盘"
        SharePlatform.PAN123 -> "123云盘"
    }

    private fun credentialOrEmpty(p: SharePlatform): String = when (p) {
        SharePlatform.QUARK -> login.account("QUARK")?.cookie.orEmpty()
        SharePlatform.UC -> login.account("UC")?.cookie.orEmpty()
        SharePlatform.BAIDU -> login.account("BAIDU")?.cookie.orEmpty()
        SharePlatform.C139 -> login.account("C139")?.cookie.orEmpty()
        SharePlatform.PAN123 -> login.account("PAN123")?.accessToken.orEmpty()
        SharePlatform.XUNLEI -> login.account("XUNLEI")?.accessToken.orEmpty()
    }

    /** 平台 → ResolveRepository（Xunlei/Pan123 凭证实时从 store 读取） */
    private fun buildRepo(p: SharePlatform): ShareResolveRepository {
        val r = when (p) {
            SharePlatform.QUARK -> QuarkResolveRepository(com.yunx.app.data.network.QuarkApi())
            SharePlatform.UC -> UCResolveRepository(com.yunx.app.data.network.UCApi())
            SharePlatform.BAIDU -> BaiduResolveRepository(com.yunx.app.data.network.BaiduApi())
            SharePlatform.C139 -> C139ResolveRepository(com.yunx.app.data.network.C139Api())
            SharePlatform.PAN123 -> Pan123ResolveRepository(
                com.yunx.app.data.network.Pan123Api(),
                { login.account("PAN123")?.accessToken },
            )
            SharePlatform.XUNLEI -> xunleiRepo()
        }
        repo = r
        return r
    }

    private fun xunleiRepo(): XunleiResolveRepository {
        val api = com.yunx.app.data.network.XunleiApi()
        return XunleiResolveRepository(
            api = api,
            accountProvider = { login.account("XUNLEI")?.accessToken?.takeIf { it.isNotBlank() } },
            deviceIdProvider = { com.yunx.app.data.network.XunleiDeviceFingerprint.deviceId() },
            captchaProvider = { login.account("XUNLEI")?.captchaToken.orEmpty() },
            refreshProvider = {
                val acc = login.account("XUNLEI") ?: return@XunleiResolveRepository null
                if (acc.refreshToken.isBlank()) return@XunleiResolveRepository null
                val pair = api.refreshToken(acc.refreshToken, com.yunx.app.data.network.XunleiDeviceFingerprint.deviceId())
                if (pair != null) login.updateXunleiTokens(pair.first, pair.second)
                pair
            },
        )
    }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble(); var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}

fun formatSpeedText(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return ""
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var v = bytesPerSec.toDouble(); var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}

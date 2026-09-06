/*
 * YunX Desktop - AGPL-3.0. Cloud-drive (personal netdisk) browsing & download.
 * Reuses core network APIs (listCloudFiles / getDownloadLink) per platform.
 */
package com.yunx.desktop.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.Pan123Constants
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiConstants
import com.yunx.app.data.network.XunleiDeviceFingerprint
import com.yunx.app.data.network.model.ShareFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 云盘路径段：dirToken 为平台内目录标识（夸克/UC/123/迅雷用 fid，百度用绝对路径，139 用 fileId） */
data class CloudPathSeg(val dirToken: String, val name: String)

/**
 * 云盘（个人网盘）浏览与下载编排器：登录账号后浏览根/子目录、取直链下载、文件夹递归下载。
 * 平台目录根：夸克/UC/123="0"，139="/"，百度="/"（路径），迅雷=""（空）。
 */
class CloudDriveController(
    private val login: DriveLogin,
    private val enqueue: suspend (url: String, fileName: String, headers: Map<String, String>, size: Long, platform: String) -> Long
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val quarkApi = QuarkApi()
    private val ucApi = UCApi()
    private val baiduApi = BaiduApi()
    private val c139Api = C139Api()
    private val pan123Api = Pan123Api()
    private val xunleiApi = XunleiApi()

    var platform by mutableStateOf<DriveId?>(null); private set
    var files by mutableStateOf<List<ShareFile>>(emptyList()); private set
    var path by mutableStateOf<List<CloudPathSeg>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var operating by mutableStateOf(false); private set       // 文件夹递归下载进行中
    var folderProgress by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set

    private var cancelRequested = false

    init {
        // 迅雷 pan 请求 401 时用 refresh_token 自动续期并回写
        xunleiApi.refreshTokenProvider = { deviceId ->
            val acc = login.account("XUNLEI")
            if (acc == null || acc.refreshToken.isBlank()) {
                null
            } else {
                val pair = xunleiApi.refreshToken(acc.refreshToken, deviceId)
                if (pair != null) login.updateXunleiTokens(pair.first, pair.second)
                pair
            }
        }
    }

    fun consumeError() { error = null }
    fun consumeMessage() { message = null }

    /** 已登录的可浏览平台 */
    fun loggedPlatforms(): List<DriveId> =
        DriveId.entries.filter { login.account(it.name)?.isLoggedIn() == true }

    /** 选择平台并加载根目录 */
    fun selectPlatform(id: DriveId) {
        platform = id
        loadRoot()
    }

    fun loadRoot() {
        val p = platform ?: return
        path = emptyList()
        scope.launch { doLoad(p, rootToken(p), emptyList()) }
    }

    fun openFolder(file: ShareFile) {
        val p = platform ?: return
        val seg = CloudPathSeg(dirTokenOf(p, file), file.fname)
        val newPath = path + seg
        path = newPath
        scope.launch { doLoad(p, seg.dirToken, newPath) }
    }

    /** 上一级 */
    fun back() {
        val p = platform ?: return
        val current = path
        if (current.isEmpty()) { loadRoot(); return }
        val parent = current.dropLast(1)
        path = parent
        val dirToken = parent.lastOrNull()?.dirToken ?: rootToken(p)
        scope.launch { doLoad(p, dirToken, parent) }
    }

    /** 面包屑跳到第 level 层（0=根） */
    fun navigateToLevel(level: Int) {
        val p = platform ?: return
        if (level < 0) return
        val trimmed = path.take(level)
        path = trimmed
        val dirToken = trimmed.lastOrNull()?.dirToken ?: rootToken(p)
        scope.launch { doLoad(p, dirToken, trimmed) }
    }

    /** 单文件下载 */
    fun downloadFile(file: ShareFile) {
        val p = platform ?: return
        scope.launch {
            operating = true
            try {
                val cookie = credentialFor(p)
                val (url, headers) = resolveDownload(p, file, cookie)
                enqueue(url, file.fname, headers, file.fsize, platformTag(p))
                message = "已加入下载：" + file.fname
            } catch (t: Throwable) {
                error = t.message ?: "下载失败"
            } finally { operating = false }
        }
    }

    /** 文件夹递归下载：收集全部文件保持目录结构 */
    fun downloadFolder(file: ShareFile) {
        val p = platform ?: return
        scope.launch {
            operating = true
            cancelRequested = false
            folderProgress = "正在收集文件…"
            try {
                val cookie = credentialFor(p)
                val tasks = mutableListOf<Pair<ShareFile, String>>()
                collectFolderFiles(p, dirTokenOf(p, file), file.fname, cookie, tasks, 0)
                if (tasks.isEmpty()) { message = "文件夹为空"; return@launch }
                var ok = 0; var fail = 0
                tasks.forEachIndexed { index, (f, relPath) ->
                    if (cancelRequested) return@forEachIndexed
                    folderProgress = "正在加入下载 " + (index + 1) + "/" + tasks.size
                    runCatching {
                        val (url, headers) = resolveDownload(p, f, cookie)
                        enqueue(url, relPath, headers, f.fsize, platformTag(p))
                        ok++
                    }.onFailure { fail++ }
                }
                message = if (fail > 0) "已加入 " + ok + " 个，失败 " + fail + " 个" else "已加入 " + ok + " 个下载任务"
            } catch (t: Throwable) {
                error = t.message ?: "下载文件夹失败"
            } finally {
                operating = false
                folderProgress = null
                cancelRequested = false
            }
        }
    }

    fun cancelRecursive() { cancelRequested = true }

    // ---------- 内部 ----------

    private suspend fun doLoad(p: DriveId, dirToken: String, pathSegs: List<CloudPathSeg>) {
        loading = true
        try {
            val cookie = credentialFor(p)
            files = listDir(p, dirToken, cookie)
        } catch (t: Throwable) {
            error = t.message ?: "加载失败"
        } finally { loading = false }
    }

    /** 平台目录根标识 */
    private fun rootToken(p: DriveId): String = when (p) {
        DriveId.BAIDU, DriveId.C139 -> "/"
        DriveId.XUNLEI -> ""
        else -> "0"
    }

    /** 打开文件夹所需标识：百度用 absolute path（fidToken），其余用 fid */
    private fun dirTokenOf(p: DriveId, file: ShareFile): String =
        if (p == DriveId.BAIDU) file.fidToken else file.fid

    private fun credentialFor(p: DriveId): String = when (p) {
        DriveId.QUARK -> login.account("QUARK")?.cookie.orEmpty()
        DriveId.UC -> login.account("UC")?.cookie.orEmpty()
        DriveId.BAIDU -> login.account("BAIDU")?.cookie.orEmpty()
        DriveId.C139 -> login.account("C139")?.cookie.orEmpty()
        DriveId.PAN123 -> login.account("PAN123")?.accessToken.orEmpty()
        DriveId.XUNLEI -> login.account("XUNLEI")?.accessToken.orEmpty()
    }

    private fun xunleiCreds(): Triple<String, String, String>? {
        val acc = login.account("XUNLEI") ?: return null
        if (acc.accessToken.isBlank()) return null
        val deviceId = XunleiDeviceFingerprint.deviceId()
        return Triple(acc.accessToken, deviceId, acc.captchaToken)
    }

    private suspend fun listDir(p: DriveId, dirToken: String, cookie: String): List<ShareFile> = when (p) {
        DriveId.QUARK -> quarkApi.listCloudFiles(dirToken, cookie) ?: emptyList()
        DriveId.UC -> ucApi.listCloudFiles(dirToken, cookie) ?: emptyList()
        DriveId.BAIDU -> baiduApi.listCloudFiles(dirToken, cookie)
        DriveId.C139 -> c139Api.listCloudFiles(dirToken, cookie)
        DriveId.PAN123 -> pan123Api.listCloudFiles(dirToken, cookie)
        DriveId.XUNLEI -> {
            val c = xunleiCreds() ?: throw IllegalStateException("请先登录迅雷网盘")
            xunleiApi.getFiles(dirToken, c.first, c.second, c.third) ?: emptyList()
        }
    }

    /** 取下载直链返回 (url, headers) */
    private suspend fun resolveDownload(p: DriveId, file: ShareFile, cookie: String): Pair<String, Map<String, String>> = when (p) {
        DriveId.QUARK -> {
            val link = quarkApi.getDownloadLink(file.fid, cookie) ?: throw IllegalStateException("获取下载链接失败")
            link.downloadUrl to mapOf(
                "Cookie" to cookie,
                "User-Agent" to QuarkConstants.API_USER_AGENT,
                "Referer" to QuarkConstants.DOWNLOAD_REFERER
            )
        }
        DriveId.UC -> {
            val link = ucApi.cloudGetDownloadLink(file.fid, cookie) ?: throw IllegalStateException("获取下载链接失败")
            link.downloadUrl to mapOf(
                "Cookie" to cookie,
                "User-Agent" to UCConstants.USER_AGENT,
                "Referer" to UCConstants.DOWNLOAD_REFERER,
                "Origin" to UCConstants.WEB_ORIGIN
            )
        }
        DriveId.BAIDU -> {
            val url = baiduApi.fileMetasDlink(file.fid, cookie)
            url to mapOf(
                "Cookie" to cookie,
                "User-Agent" to BaiduConstants.UA_NETDISK
            )
        }
        DriveId.C139 -> {
            val link = c139Api.getDownloadUrl(file.fid, cookie) ?: throw IllegalStateException("获取下载链接失败")
            link.downloadUrl to mapOf(
                "User-Agent" to C139Constants.PC_UA,
                "Referer" to "https://yun.139.com/"
            )
        }
        DriveId.PAN123 -> {
            val link = pan123Api.getDownloadLink(file, cookie) ?: throw IllegalStateException("获取下载链接失败")
            link.downloadUrl to mapOf(
                "User-Agent" to Pan123Constants.WEB_UA,
                "Referer" to Pan123Constants.DOWNLOAD_REFERER
            )
        }
        DriveId.XUNLEI -> {
            val c = xunleiCreds() ?: throw IllegalStateException("请先登录迅雷网盘")
            val link = xunleiApi.getFileDetail(file.fid, c.first, c.second, c.third) ?: throw IllegalStateException("获取下载链接失败")
            link.downloadUrl to mapOf("User-Agent" to XunleiConstants.APP_UA)
        }
    }

    private fun platformTag(p: DriveId): String = when (p) {
        DriveId.QUARK -> DownloadPlatform.QUARK
        DriveId.UC -> DownloadPlatform.UC
        DriveId.BAIDU -> DownloadPlatform.BAIDU
        DriveId.C139 -> DownloadPlatform.C139
        DriveId.PAN123 -> DownloadPlatform.PAN123
        DriveId.XUNLEI -> DownloadPlatform.XUNLEI
    }

    /** 递归收集目录下所有文件（保持目录结构），depth 上限 12 层 */
    private suspend fun collectFolderFiles(
        p: DriveId, dirToken: String, prefix: String, cookie: String,
        result: MutableList<Pair<ShareFile, String>>, depth: Int
    ) {
        if (depth > 12) return
        val list = runCatching { listDir(p, dirToken, cookie) }.getOrDefault(emptyList())
        list.filter { !it.isdir }.forEach { result.add(it to (prefix + "/" + it.fname)) }
        list.filter { it.isdir }.forEach {
            collectFolderFiles(p, dirTokenOf(p, it), prefix + "/" + it.fname, cookie, result, depth + 1)
        }
    }
}

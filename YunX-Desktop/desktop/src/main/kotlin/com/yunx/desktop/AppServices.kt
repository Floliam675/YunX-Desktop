package com.yunx.desktop

import com.yunx.app.data.download.ChunkDownloader
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.JsonDownloadTaskStore
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.XunleiDeviceFingerprint
import com.yunx.app.data.security.FileCredentialCipher
import com.yunx.desktop.app.CloudDriveController
import com.yunx.desktop.app.DriveAccountStore
import com.yunx.desktop.app.DriveLogin
import com.yunx.desktop.app.ResolveController
import java.io.File

/**
 * YunX Desktop 应用装配：数据目录、持久化、下载引擎、账号与解析控制器。
 * 单一共享实例由 Compose 根持有并逐层传入。
 */
class AppServices {
    companion object {
        fun dataDir(): File = File(System.getProperty("user.home"), ".yunx-desktop").apply { mkdirs() }
        fun downloadDir(): File = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
    }

    val settings = SettingsStore(File(dataDir(), "settings.json"))
    val accountStore = DriveAccountStore(File(dataDir(), "accounts.json"))
    val login = DriveLogin(accountStore)
    val resolver = ResolveController(login)
    val cloud = CloudDriveController(login) { url, name, headers, size, platform ->
        downloadManager.enqueue(url, name, headers, size, platform)
    }
    val credentialKey = File(dataDir(), "credential.key")

    private val taskFile = File(dataDir(), "download_tasks.json")
    val taskDao = JsonDownloadTaskStore(taskFile)

    val downloadManager: DownloadManager by lazy {
        DownloadManager(
            dao = taskDao,
            downloader = ChunkDownloader { HttpClients.downloadClient() },
            threadProvider = { platform -> settings.threadsFor(platform) },
            saveDirProvider = { settings.downloadDir ?: downloadDir().absolutePath },
            concurrencyProvider = { settings.maxConcurrent },
            speedLimitProvider = { settings.speedLimitBytes },
            retryCountProvider = { settings.retryCount },
        )
    }

    init {
        XunleiDeviceFingerprint.init(File(dataDir(), "xunlei_fp.properties"))
    }
}

/** 设置持久化（JSON 小文件） */
class SettingsStore(file: File) {
    private val f = file
    private val j get() = runCatching { org.json.JSONObject(f.readText(Charsets.UTF_8)) }.getOrDefault(org.json.JSONObject())

    var downloadDir: String?
        get() = j.optString("download_dir").takeIf { it.isNotBlank() }
        set(v) { put("download_dir", v ?: "") }
    var maxConcurrent: Int
        get() = j.optInt("max_concurrent", 3).coerceIn(1, 10)
        set(v) { put("max_concurrent", v.coerceIn(1, 10)) }
    var speedLimitBytes: Long
        get() = j.optLong("speed_limit", 0L)
        set(v) { put("speed_limit", v.coerceAtLeast(0L)) }
    var retryCount: Int
        get() = j.optInt("retry", 3).coerceIn(0, 10)
        set(v) { put("retry", v.coerceIn(0, 10)) }

    fun threadsFor(platform: String): Int {
        if (platform == com.yunx.app.data.download.DownloadPlatform.XUNLEI) return 8
        if (platform.isBlank() || platform == com.yunx.app.data.download.DownloadPlatform.GENERIC)
            return j.optInt("threads", 32).coerceIn(1, 512)
        return j.optInt("threads_" + platform, 32).coerceIn(1, 512)
    }
    fun setThreads(platform: String, value: Int) {
        val v = value.coerceIn(1, 512)
        if (platform.isBlank() || platform == com.yunx.app.data.download.DownloadPlatform.GENERIC) put("threads", v)
        else put("threads_" + platform, v)
    }

    private fun put(key: String, value: Any) {
        val obj = j
        obj.put(key, value)
        f.parentFile?.mkdirs()
        f.writeText(obj.toString(2), Charsets.UTF_8)
    }
}

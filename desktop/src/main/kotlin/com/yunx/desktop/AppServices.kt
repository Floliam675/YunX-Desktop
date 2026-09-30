package com.yunx.desktop

import com.yunx.app.data.download.ChunkDownloader
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.download.JsonDownloadTaskStore
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.XunleiDeviceFingerprint
import com.yunx.app.data.security.FileCredentialCipher
import com.yunx.desktop.app.CloudDriveController
import com.yunx.desktop.app.DriveAccountStore
import com.yunx.desktop.app.DriveLogin
import com.yunx.desktop.app.ResolveController
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * YunX Desktop 应用装配：数据目录、持久化、下载引擎、账号与解析控制器。
 * 单一共享实例由 Compose 根持有并逐层传入。
 */
class AppServices {
    companion object {
        fun dataDir(): File = File(System.getProperty("user.home"), ".yunx-desktop").apply { mkdirs() }

        @Volatile
        private var cachedDownloadDir: File? = null

        /**
         * 系统真实的「下载」文件夹。
         * 不能硬编码成 ~/Downloads：Windows 允许用户把「下载」移到别的盘（属性 → 位置），
         * 那样硬编码路径要么不存在、要么与系统认知不一致。优先读注册表里的已知文件夹。
         */
        fun downloadDir(): File = cachedDownloadDir ?: resolveDownloadDir().also { cachedDownloadDir = it }

        private fun resolveDownloadDir(): File = runCatching {
            val home = System.getProperty("user.home")
            val fromRegistry = if (System.getProperty("os.name").orEmpty().startsWith("Windows", true)) {
                val proc = ProcessBuilder(
                    "reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders",
                    "/v", "{374DE290-123F-4565-9164-39C4925E467B}",
                ).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                Regex("REG_SZ\\s+(.+)").find(out)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            } else null

            listOfNotNull(fromRegistry?.let(::File), File(home, "Downloads"), File(home))
                .firstOrNull { it.isDirectory || it.mkdirs() } ?: File(home)
        }.getOrElse { File(System.getProperty("user.home"), "Downloads") }.apply { mkdirs() }
    }

    /** 实际生效的下载目录：设置里指定过就用它，否则用系统「下载」文件夹 */
    fun effectiveDownloadDir(): File =
        settings.downloadDir?.let(::File)?.takeIf { it.isDirectory || it.mkdirs() } ?: downloadDir()

    val settings = SettingsStore(File(dataDir(), "settings.json"))
    val accountStore = DriveAccountStore(File(dataDir(), "accounts.json"))
    val login = DriveLogin(accountStore)
    /** 网页登录识别到登录态后的「是否保存」确认闸门（UI 弹框，登录线程等待结果） */
    val loginConfirm = com.yunx.desktop.app.LoginConfirmGate()
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

    /** 外观主题：system / light / dark（Compose 可观察，设置里切换后立即生效） */
    private val themeState = mutableStateOf(
        j.optString("theme").takeIf { it == "light" || it == "dark" } ?: "system"
    )
    val theme: String get() = themeState.value
    fun setTheme(v: String) {
        if (v != "light" && v != "dark" && v != "system") return
        themeState.value = v
        put("theme", v)
    }

    /** 自定义主题色（ARGB，null = Material3 默认）；Compose 可观察，滑杆拖动即时生效 */
    private val accentState = mutableStateOf(j.optString("accent").takeIf { it.length == 8 }?.toLongOrNull(16)?.toInt())
    val accentArgb: Int? get() = accentState.value
    fun setAccent(argb: Int?) {
        accentState.value = argb
        put("accent", argb?.let { "%08X".format(it) } ?: "")
    }

    /** 自定义背景色（ARGB，null = 默认） */
    private val baseColorState = mutableStateOf(j.optString("base").takeIf { it.length == 8 }?.toLongOrNull(16)?.toInt())
    val baseArgb: Int? get() = baseColorState.value
    fun setBaseColor(argb: Int?) {
        baseColorState.value = argb
        put("base", argb?.let { "%08X".format(it) } ?: "")
    }

    /** 被用户「忽略此版本」的版本号（启动静默检查时不再提示该版本） */
    var skippedVersion: String?
        get() = j.optString("skip_version").takeIf { it.isNotBlank() }
        set(v) { put("skip_version", v ?: "") }

    /** 上次停留的页面索引（下次启动恢复） */
    var lastTab: Int        get() = j.optInt("last_tab", 0).coerceIn(0, 15)
        set(v) { put("last_tab", v.coerceIn(0, 15)) }

    /** 窗口尺寸记忆 */
    var windowWidth: Int
        get() = j.optInt("win_w", 1240).coerceIn(960, 4096)
        set(v) { put("win_w", v.coerceIn(960, 4096)) }

    var windowHeight: Int
        get() = j.optInt("win_h", 780).coerceIn(600, 2160)
        set(v) { put("win_h", v.coerceIn(600, 2160)) }

    /** 每个网盘独立的分片线程数：迅雷默认 8（其接口并发敏感），其余默认 32；全部可在设置页分别调节 */
    fun threadsFor(platform: String): Int =
        j.optInt(threadsKey(platform), defaultThreads(platform)).coerceIn(1, 512)

    fun setThreads(platform: String, value: Int) = put(threadsKey(platform), value.coerceIn(1, 512))

    private fun threadsKey(platform: String): String =
        if (platform.isBlank() || platform == DownloadPlatform.GENERIC) "threads" else "threads_$platform"

    private fun defaultThreads(platform: String): Int =
        if (platform == DownloadPlatform.XUNLEI) 8 else 32

    private fun put(key: String, value: Any) {
        val obj = j
        obj.put(key, value)
        f.parentFile?.mkdirs()
        f.writeText(obj.toString(2), Charsets.UTF_8)
    }
}

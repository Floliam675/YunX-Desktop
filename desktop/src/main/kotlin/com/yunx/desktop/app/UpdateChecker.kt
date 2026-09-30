/*
 * YunX Desktop - AGPL-3.0.
 * 更新检测：移植自上游 YunX（6ac95bd「Feature/update detection」），改为面向本仓库的 Release。
 * 原版是下载 APK 并安装；桌面版只负责「发现新版本 → 打开发布页/下载安装包」，不擅自替换自身。
 */
package com.yunx.desktop.app

import com.yunx.app.data.network.HttpClients
import okhttp3.Request
import org.json.JSONObject

/** Release 里的一个附件 */
data class ReleaseAsset(val name: String, val downloadUrl: String, val size: Long)

data class ReleaseInfo(
    val tagName: String,
    val body: String,
    val assets: List<ReleaseAsset>,
    val publishedAt: String,
    /** Release 页面地址，供「打开发布页」跳浏览器 */
    val htmlUrl: String,
)

sealed interface UpdateResult {
    data class Success(val release: ReleaseInfo) : UpdateResult
    data class Failure(val reason: String) : UpdateResult
}

object UpdateChecker {

    private const val LATEST_URL =
        "https://api.github.com/repos/Floliam675/YunX-Desktop/releases/latest"

    /** 国内直连 GitHub 慢/失败时的兜底下载通道（与上游同款镜像前缀） */
    const val MIRROR_PREFIX = "https://cdn.gh-proxy.org/"

    fun mirrorUrl(url: String): String = MIRROR_PREFIX + url

    /** 版本号比较：>0 表示 [a] 比 [b] 新。忽略前缀 v，逐段按数字比，段数不同按 0 补齐。 */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().trimStart('v', 'V').split(".")
        val pb = b.trim().trimStart('v', 'V').split(".")
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }

    /** 从 Release 正文里取 `[网盘下载](url)` 兜底链接（上游同款约定） */
    private val NETDISK_LINK = Regex("""\[网盘下载]\s*\(\s*(https?://[^)\s]+?)\s*\)""")

    fun netdiskUrl(body: String): String? =
        NETDISK_LINK.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /** 安装包附件（Windows installer / portable） */
    fun installerAsset(release: ReleaseInfo): ReleaseAsset? =
        release.assets.firstOrNull { it.name.endsWith(".exe", ignoreCase = true) }
            ?: release.assets.firstOrNull { it.name.contains("installer", ignoreCase = true) }

    /**
     * 查询最新 Release。阻塞调用，请在 IO 线程使用。
     * 只认正式发布的 Release（draft/prerelease 不会出现在 latest 接口里）。
     */
    fun fetchLatest(): UpdateResult = runCatching {
        val req = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "YunX-Desktop-UpdateCheck")
            .build()
        HttpClients.apiClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val reason = when (resp.code) {
                    403, 429 -> "GitHub 接口限流（HTTP ${resp.code}），请稍后再试"
                    404 -> "尚未发布任何正式版本"
                    else -> "获取最新版本失败（HTTP ${resp.code}）"
                }
                return UpdateResult.Failure(reason)
            }
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val assets = json.optJSONArray("assets")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val a = arr.optJSONObject(i) ?: return@mapNotNull null
                    ReleaseAsset(
                        name = a.optString("name"),
                        downloadUrl = a.optString("browser_download_url"),
                        size = a.optLong("size"),
                    )
                }
            } ?: emptyList()
            UpdateResult.Success(
                ReleaseInfo(
                    tagName = json.optString("tag_name"),
                    body = json.optString("body"),
                    assets = assets,
                    publishedAt = json.optString("published_at"),
                    htmlUrl = json.optString("html_url"),
                )
            )
        }
    }.getOrElse { t ->
        UpdateResult.Failure("网络错误：" + (t.message ?: t.javaClass.simpleName))
    }
}

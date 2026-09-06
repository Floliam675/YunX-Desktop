package com.yunx.app.data.db

/**
 * 下载任务（桌面版持久化：JSON store；断点续传依赖 part 文件 + 已下载大小）。
 * 字段与 Android 原版（Room）完全一致，便于逻辑复用。
 */
data class DownloadTaskEntity(
    val id: Long = 0,
    val url: String,
    val fileName: String,
    val totalSize: Long = 0L,
    val downloadedSize: Long = 0L,
    val status: Int = STATUS_PENDING,
    val errorMsg: String = "",
    val savePath: String = "",
    val requestHeadersJson: String = "{}",
    val chunkCount: Int = 0,
    val plannedTotalSize: Long = 0L,
    val cleanupId: String = "",
    val platform: String = "",
    val avgSpeed: Long = 0,
    val createTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_DOWNLOADING = 1
        const val STATUS_PAUSED = 2
        const val STATUS_COMPLETED = 3
        const val STATUS_FAILED = 4

        fun statusText(status: Int): String = when (status) {
            STATUS_PENDING -> "等待中"
            STATUS_DOWNLOADING -> "下载中"
            STATUS_PAUSED -> "已暂停"
            STATUS_COMPLETED -> "已完成"
            STATUS_FAILED -> "失败"
            else -> "未知"
        }
    }
}

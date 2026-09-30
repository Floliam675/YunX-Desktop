/*
 * YunX Desktop - AGPL-3.0. Desktop replacement for Android DownloadSaver
 * (MediaStore/SAF): copy the assembled temp file into the chosen directory.
 */
package com.yunx.app.data.download

import java.io.File

object DesktopSaver {

    /** 默认保存目录：用户 Download 目录 */
    fun defaultDir(): File =
        File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }

    /**
     * 把已合并/下载完成的 [src] 保存为 [fileName] 到 [saveDir]（null 时用默认目录）。
     * [fileName] 可含相对子目录（如 "folder/sub/a.mp4"，"/" 或 "\" 均识别），自动创建目录，
     * 用于文件夹递归下载保持目录结构；单文件下载传文件名即可。
     * @return 保存后的绝对路径；失败返回 null
     */
    fun save(fileName: String, src: File, saveDir: String?): String? {
        val dir = if (!saveDir.isNullOrBlank()) File(saveDir) else defaultDir()
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val normalized = fileName.replace('/', File.separatorChar).replace('\\', File.separatorChar)
        val target = File(dir, normalized)
        val parent = target.parentFile ?: dir
        if (!parent.isDirectory && !parent.mkdirs()) return null
        val resolved = uniqueFile(parent, target.name)
        return runCatching {
            src.copyTo(resolved, overwrite = false)
            resolved.absolutePath
        }.getOrNull()
    }

    /**
     * 只解析最终保存路径（含同名自动编号），**不复制任何内容**。
     * 配合 ChunkDownloader.mergeChunksToStream 把分片流式写进最终文件，
     * 峰值占用从 3 份（分片 + 合并副本 + 目标副本）降到 ≈ 文件大小 + 一个分片
     * —— 与上游 1.2.7（b0d2eb2「大文件下载改为流式落盘」）同款做法。
     * @return 可写的目标文件；目录创建失败返回 null
     */
    fun prepare(fileName: String, saveDir: String?): File? {
        val dir = if (!saveDir.isNullOrBlank()) File(saveDir) else defaultDir()
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val normalized = fileName.replace('/', File.separatorChar).replace('\\', File.separatorChar)
        val target = File(dir, normalized)
        val parent = target.parentFile ?: dir
        if (!parent.isDirectory && !parent.mkdirs()) return null
        return uniqueFile(parent, target.name)
    }

    /** 删除已保存文件 */
    fun delete(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

    /** 同名文件自动追加序号 */
    private fun uniqueFile(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var i = 1
        while (candidate.exists()) {
            candidate = if (ext.isEmpty()) File(dir, "$base ($i)")
            else File(dir, "$base ($i).$ext")
            i++
        }
        return candidate
    }
}

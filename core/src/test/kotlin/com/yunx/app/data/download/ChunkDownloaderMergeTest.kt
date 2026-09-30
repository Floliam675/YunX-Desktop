/*
 * YunX Desktop - AGPL-3.0.
 * 移植自上游 YunX 1.2.7（b0d2eb2「大文件下载改为流式落盘」）的同名测试。
 *
 * mergeChunksToStream 是大文件「峰值占用 ≈ 1 份」的核心保证：
 *   · 边写边删 —— 每片写完立即释放空间，否则峰值占用翻倍
 *   · 返回字节数必须等于分片总长 —— 上层据此做完整性校验
 *   · 未写完的分片必须保留 —— 失败后重下时不能凭空少数据
 */
package com.yunx.app.data.download

import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkDownloaderMergeTest {

    private val downloader = ChunkDownloader { OkHttpClient() }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "yunx_merge_${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun writesAllBytesAndDeletesEachPart() = runBlocking {
        val dir = tempDir()
        try {
            val parts = listOf(
                File(dir, "part_0").apply { writeBytes(ByteArray(300_000) { 1 }) },
                File(dir, "part_1").apply { writeBytes(ByteArray(100_000) { 2 }) },
            )
            val out = java.io.ByteArrayOutputStream()
            val written = downloader.mergeChunksToStream(parts, out)
            assertEquals(400_000L, written)
            assertEquals(400_000, out.size())
            assertFalse("写完整片后必须立即删除分片（否则峰值占用翻倍）", parts.any { it.exists() })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun keepsPartThatFailedToWrite() {
        val dir = tempDir()
        try {
            val first = File(dir, "part_0").apply { writeBytes(ByteArray(10)) }
            val second = File(dir, "part_1").apply { writeBytes(ByteArray(10)) }
            // 只接受 10 字节，第二片写入时抛 ENOSPC 同类 IOException
            val full = object : OutputStream() {
                private var written = 0
                override fun write(b: Int) {
                    if (written + 1 > 10) throw IOException("write failed: ENOSPC (No space left on device)")
                    written++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (written + len > 10) throw IOException("write failed: ENOSPC (No space left on device)")
                    written += len
                }
            }
            assertThrows(IOException::class.java) {
                runBlocking<Unit> { downloader.mergeChunksToStream(listOf(first, second), full) }
            }
            assertFalse("已完整写入目标的分片应已释放", first.exists())
            assertTrue("未写完的分片必须保留，避免失败后凭空少数据", second.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}

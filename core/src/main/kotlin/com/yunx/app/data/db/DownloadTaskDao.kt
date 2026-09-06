package com.yunx.app.data.db

import kotlinx.coroutines.flow.Flow

/**
 * 下载任务 DAO 接口（桌面版，去掉 Room 注解）：JsonDownloadTaskStore 实现。
 */
interface DownloadTaskDao {

    fun observeAll(): Flow<List<DownloadTaskEntity>>

    suspend fun insert(task: DownloadTaskEntity): Long

    suspend fun get(id: Long): DownloadTaskEntity?

    suspend fun updateProgress(id: Long, status: Int, downloadedSize: Long, totalSize: Long)

    suspend fun updatePlan(id: Long, chunkCount: Int, totalSize: Long)

    suspend fun updateRequestHeaders(id: Long, encryptedHeaders: String)

    suspend fun markInterruptedAsPaused()

    suspend fun updateStatus(id: Long, status: Int)

    suspend fun updateError(id: Long, errorMsg: String)

    suspend fun complete(id: Long, status: Int, savePath: String, avgSpeed: Long = 0L)

    suspend fun delete(id: Long)
}

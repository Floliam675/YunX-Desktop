/*
 * YunX Desktop - AGPL-3.0. JSON-file backed DownloadTaskDao (Room replacement).
 */
package com.yunx.app.data.download

import com.yunx.app.data.db.DownloadTaskDao
import com.yunx.app.data.db.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 下载任务持久化：内存 StateFlow + JSON 文件落盘（改动即写）。
 * 断点续传仍依赖分片 part 文件；这里仅存任务元数据。
 */
class JsonDownloadTaskStore(private val file: File) : DownloadTaskDao {

    private val mutex = Mutex()
    private var nextId: Long = 1

    private val _all = MutableStateFlow<List<DownloadTaskEntity>>(load())
    val tasks: Flow<List<DownloadTaskEntity>> = _all.asStateFlow()

    private fun load(): List<DownloadTaskEntity> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        DownloadTaskEntity(
                            id = o.optLong("id"),
                            url = o.optString("url"),
                            fileName = o.optString("fileName"),
                            totalSize = o.optLong("totalSize"),
                            downloadedSize = o.optLong("downloadedSize"),
                            status = o.optInt("status"),
                            errorMsg = o.optString("errorMsg"),
                            savePath = o.optString("savePath"),
                            requestHeadersJson = o.optString("requestHeadersJson", "{}"),
                            chunkCount = o.optInt("chunkCount"),
                            plannedTotalSize = o.optLong("plannedTotalSize"),
                            cleanupId = o.optString("cleanupId"),
                            platform = o.optString("platform"),
                            avgSpeed = o.optLong("avgSpeed"),
                            createTime = o.optLong("createTime")
                        )
                    )
                }
            }
            nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1
            list
        }.getOrDefault(emptyList())
    }

    private suspend fun persist(list: List<DownloadTaskEntity>) {
        val arr = JSONArray()
        list.sortedByDescending { it.createTime }.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("url", t.url)
                    .put("fileName", t.fileName)
                    .put("totalSize", t.totalSize)
                    .put("downloadedSize", t.downloadedSize)
                    .put("status", t.status)
                    .put("errorMsg", t.errorMsg)
                    .put("savePath", t.savePath)
                    .put("requestHeadersJson", t.requestHeadersJson)
                    .put("chunkCount", t.chunkCount)
                    .put("plannedTotalSize", t.plannedTotalSize)
                    .put("cleanupId", t.cleanupId)
                    .put("platform", t.platform)
                    .put("avgSpeed", t.avgSpeed)
                    .put("createTime", t.createTime)
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(arr.toString(2), Charsets.UTF_8)
    }

    private suspend fun mutate(block: (MutableList<DownloadTaskEntity>) -> Unit) {
        mutex.withLock {
            val list = _all.value.toMutableList()
            block(list)
            _all.value = list
            persist(list)
        }
    }

    override fun observeAll(): Flow<List<DownloadTaskEntity>> = tasks

    override suspend fun insert(task: DownloadTaskEntity): Long = mutex.withLock {
        val withId = task.copy(id = nextId++)
        val list = _all.value.toMutableList()
        list.add(withId)
        _all.value = list
        persist(list)
        withId.id
    }

    override suspend fun get(id: Long): DownloadTaskEntity? = _all.value.firstOrNull { it.id == id }

    override suspend fun updateProgress(id: Long, status: Int, downloadedSize: Long, totalSize: Long) = mutate { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(status = status, downloadedSize = downloadedSize, totalSize = totalSize)
    }

    override suspend fun updatePlan(id: Long, chunkCount: Int, totalSize: Long) = mutate { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(chunkCount = chunkCount, plannedTotalSize = totalSize)
    }

    override suspend fun updateRequestHeaders(id: Long, encryptedHeaders: String) = mutate { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(requestHeadersJson = encryptedHeaders)
    }

    override suspend fun markInterruptedAsPaused() = mutate { list ->
        for (i in list.indices) {
            val st = list[i].status
            if (st == DownloadTaskEntity.STATUS_DOWNLOADING || st == DownloadTaskEntity.STATUS_PENDING) {
                list[i] = list[i].copy(status = DownloadTaskEntity.STATUS_PAUSED)
            }
        }
    }

    override suspend fun updateStatus(id: Long, status: Int) = mutate { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(status = status)
    }

    override suspend fun updateError(id: Long, errorMsg: String) = mutate { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(errorMsg = errorMsg)
    }

    override suspend fun complete(id: Long, status: Int, savePath: String, avgSpeed: Long) = mutate { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(status = status, savePath = savePath, avgSpeed = avgSpeed, errorMsg = "")
    }

    override suspend fun delete(id: Long) = mutate { list ->
        list.removeAll { it.id == id }
    }
}

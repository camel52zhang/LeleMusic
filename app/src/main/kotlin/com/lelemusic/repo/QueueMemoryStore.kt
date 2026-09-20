package com.lelemusic.repo

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.lelemusic.model.StorableSong
import java.io.File

/**
 * 队列记忆快照：崩溃 / 杀进程后恢复「上次听到哪」。
 *
 * 只存稳定字段（歌曲 DTO 可序列化），index 用「在当前队列里的位置」表达，
 * 恢复时按快照重建队列 + seek + 是否续播。
 */
data class QueueSnapshot(
    val songs: List<StorableSong> = emptyList(),
    /** 恢复时从队列第几首开始（0-based） */
    val index: Int = 0,
    /** 从第几毫秒续播 */
    val positionMs: Long = 0L,
    /** 进程被杀时是否正在播放（true 才自动续播，否则恢复成暂停等用户点） */
    val wasPlaying: Boolean = false,
    val updatedAtMs: Long = 0L
)

/**
 * 队列记忆仓库：`filesDir/queue.json` 存最近一次快照。
 *
 * 写盘用「临时文件 + rename」原子替换；读盘容忍坏 JSON（直接丢弃，落到空快照），
 * 绝不让一个坏文件影响启动。单例由 `AppGraph.queueMemoryStore` 提供。
 */
class QueueMemoryStore(
    private val file: File,
    private val gson: Gson
) {

    @Volatile
    private var loaded = false

    @Volatile
    private var cached: QueueSnapshot? = null

    /** 最近一次快照；没有历史返回 null（首次安装 / 已清空） */
    fun snapshot(): QueueSnapshot? {
        if (!loaded) {
            loaded = true
            cached = readFromDisk()
        }
        return cached
    }

    /** 覆盖保存快照（内存即写 + 原子落盘）；失败不影响内存态 */
    fun save(snapshot: QueueSnapshot) {
        cached = snapshot
        try {
            val tmp = File(file.parentFile, "queue.json.tmp")
            tmp.writeText(gson.toJson(snapshot))
            if (!tmp.renameTo(file)) {
                if (file.exists() && !file.delete()) {
                    Log.e(TAG, "delete old queue.json failed")
                }
                if (!tmp.renameTo(file)) {
                    Log.e(TAG, "rename tmp -> queue.json failed")
                }
            }
            Log.i(TAG, "queue snapshot saved: songs=${snapshot.songs.size} index=${snapshot.index} pos=${snapshot.positionMs}")
        } catch (error: Exception) {
            Log.e(TAG, "persist queue.json failed", error)
        }
    }

    /** 清空历史（例如用户主动停止播放后调用） */
    fun clear() {
        cached = null
        runCatching { if (file.exists()) file.delete() }
    }

    @Suppress("USELESS_ELVIS")
    private fun readFromDisk(): QueueSnapshot? {
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), QueueSnapshot::class.java)
                ?.let { snap ->
                    snap.copy(
                        songs = snap.songs ?: emptyList(),
                        index = snap.index.coerceAtLeast(0),
                        positionMs = snap.positionMs.coerceAtLeast(0L)
                    )
                }
        } catch (bad: JsonSyntaxException) {
            Log.w(TAG, "queue.json corrupt -> discard", bad)
            runCatching { file.delete() }
            null
        } catch (other: Exception) {
            Log.e(TAG, "read queue.json failed", other)
            null
        }
    }

    private companion object {
        const val TAG = "QueueMemoryStore"
    }
}

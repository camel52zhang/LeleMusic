package com.lelemusic.player

import com.lelemusic.model.Song
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内 `uid → Song` 内存表。
 *
 * 存在意义：`Song` 对象需要在 Activity（展示）与 Service（播放）之间共享，
 * 而它不可 Parcelable、也不该落盘。进程内单例内存表是最省事且零风险的做法。
 *
 * ⚠️ 必须用 [ConcurrentHashMap]——主线程（UI 读）与 ExoPlayer 加载线程（写）会并发访问，
 * `mutableMapOf()` 不是线程安全的，必崩（架构文档 §7.9）。
 */
class TrackStore {

    private val map = ConcurrentHashMap<String, Song>()

    /**
     * 写入顺序（仅 uid）。
     *
     * [ConcurrentHashMap] **不保证迭代顺序**，而播放队列必须稳定（榜单第 1 首就是第 1 首），
     * 因此额外维护一个同步的插入序列表，供 [ordered] 生成有序快照。
     */
    private val order = Collections.synchronizedList(mutableListOf<String>())

    fun putAll(songs: List<Song>) {
        songs.forEach { song -> put(song) }
    }

    fun put(song: Song) {
        // 只有「新 uid」才追加顺序，重复写入（如刷新榜单）不改变队列位置
        if (map.put(song.uid, song) == null) {
            order.add(song.uid)
        }
    }

    fun get(uid: String): Song? = map[uid]

    /** 该 uid 是否已登记；用于避免重复写入造成的位置漂移 */
    fun has(uid: String): Boolean = map.containsKey(uid)

    /** 无序快照（仅调试 / 统计用） */
    fun all(): List<Song> = map.values.toList()

    /**
     * **按写入顺序**的快照。
     *
     * `PlaybackController.playUid(uid)` 在调用方没传队列时用它兜底，
     * 保证「播完自动下一首」的顺序与榜单一致。
     */
    fun ordered(): List<Song> =
        synchronized(order) { order.mapNotNull { uid -> map[uid] } }

    fun size(): Int = map.size

    fun clear() {
        map.clear()
        order.clear()
    }
}

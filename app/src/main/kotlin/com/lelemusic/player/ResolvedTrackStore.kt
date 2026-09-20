package com.lelemusic.player

import com.lelemusic.model.ResolveFailure
import com.lelemusic.model.ResolvedTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内 `uid → ResolvedTrack / ResolveFailure` 内存表。
 *
 * - 由 T03 的 `ResolveDataSpecResolver` 在懒解析后**写入**；
 * - 由 UI（`PlayerScreen` / `MiniPlayerBar` / `PlaybackController`）**读取**，
 *   用来渲染「试听片段」橙色胶囊与错误卡片。
 *
 * ⚠️ 必须用 [ConcurrentHashMap]——写发生在 ExoPlayer 加载线程，读发生在主线程（§7.9）。
 *
 * ⚠️ 直链**永不落盘**（网易 20 分钟过期），本表随进程销毁即失效。
 */
class ResolvedTrackStore {

    private val tracks = ConcurrentHashMap<String, ResolvedTrack>()
    private val failures = ConcurrentHashMap<String, ResolveFailure>()

    fun putTrack(uid: String, track: ResolvedTrack) {
        tracks[uid] = track
        failures.remove(uid)
    }

    fun putFailure(uid: String, failure: ResolveFailure) {
        failures[uid] = failure
        tracks.remove(uid)
    }

    /** 已解析成功的直链；未解析返回 null */
    fun trackOf(uid: String): ResolvedTrack? = tracks[uid]

    /** 解析失败记录；未失败返回 null */
    fun failureOf(uid: String): ResolveFailure? = failures[uid]

    /** 便捷判断：true = 试听片段（含「解析成功但是试听」的情况） */
    fun isTrial(uid: String): Boolean = tracks[uid]?.let { !it.isFull } ?: false

    /**
     * 清空某首歌的解析结果与失败记录。
     *
     * 用途：用户点「重试」时先 `invalidate(uid)`，再让播放器 `prepare()`，
     * 强制 `ResolveDataSpecResolver` 重新取链（不这么做会一直复用上次的失败态）。
     *
     * 由 `PlaybackController.retry()` 调用。
     */
    fun invalidate(uid: String) {
        tracks.remove(uid)
        failures.remove(uid)
    }

    fun clear() {
        tracks.clear()
        failures.clear()
    }
}

package com.lelemusic.player

import androidx.media3.common.Player
import com.lelemusic.core.data.AppSettings
import com.lelemusic.model.PlaybackMode

/**
 * 播放模式的**持久化 + 应用**封装（架构文档 §3.3：`PlaybackMode` 三态直接映射 Media3）。
 *
 * | 模式 | repeatMode | shuffleModeEnabled |
 * |---|---|---|
 * | [PlaybackMode.LIST_LOOP]   | `REPEAT_MODE_ALL` | false |
 * | [PlaybackMode.SINGLE_LOOP] | `REPEAT_MODE_ONE` | false |
 * | [PlaybackMode.SHUFFLE]     | `REPEAT_MODE_ALL` | true  |
 *
 * 为什么要单独一层：
 * - `PlaybackService`（写：切歌/自动续播时读模式）与 `PlaybackController`（读：UI 展示、
 *   并把它下发给 `MediaController`）都要用，抽出来避免两处各写一遍；
 * - 真正的落盘在 [AppSettings]（SharedPreferences `playback_mode`），本类只做语义封装。
 *
 * 线程说明：`SharedPreferences` 自身线程安全（读走内存缓存、写走 `apply()` 异步落盘），
 * 本类不额外加锁；按 §7.6 约定，重 IO 场景仍在 `AppDispatchers.IO` 上调用为佳。
 *
 * @param settings 全局配置（由 `AppGraph.settings` 注入；Service 与 Controller 各自 new 一个
 *                 实例也安全，因为背后是同一份 SharedPreferences）
 */
class PlaybackModeStore(private val settings: AppSettings) {

    /** 当前模式；读取失败回落 [PlaybackMode.LIST_LOOP]，`AppSettings` 保证不抛异常 */
    val current: PlaybackMode
        get() = settings.playbackMode

    /** 顺序循环：列表循环 → 单曲循环 → 随机播放 → 列表循环；返回切换后的模式 */
    fun cycle(): PlaybackMode =
        settings.playbackMode.next().also { next -> settings.playbackMode = next }

    /** 直接指定模式 */
    fun set(mode: PlaybackMode) {
        settings.playbackMode = mode
    }

    /**
     * 把当前模式应用到播放器。
     *
     * 必须在**主线程**调用（Media3 的 `Player` 方法全部要求主线程）。
     * 用于：Service 建好 `ExoPlayer` 后、Controller 连上 `MediaController` 后、
     * 以及每次切模式后。
     */
    fun applyTo(player: Player) {
        val mode = settings.playbackMode
        player.repeatMode = mode.repeatMode
        player.shuffleModeEnabled = mode.shuffleOn
    }
}

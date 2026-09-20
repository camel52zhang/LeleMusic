package com.lelemusic.model

import androidx.media3.common.Player

/**
 * 播放模式三态，直接映射到 Media3 的 repeat / shuffle 组合。
 *
 * | 模式 | repeatMode | shuffleModeEnabled |
 * |---|---|---|
 * | [LIST_LOOP]   | `REPEAT_MODE_ALL` | false |
 * | [SINGLE_LOOP] | `REPEAT_MODE_ONE` | false |
 * | [SHUFFLE]     | `REPEAT_MODE_ALL` | true  |
 */
enum class PlaybackMode(
    val repeatMode: Int,
    val shuffleOn: Boolean
) {

    LIST_LOOP(Player.REPEAT_MODE_ALL, false),
    SINGLE_LOOP(Player.REPEAT_MODE_ONE, false),
    SHUFFLE(Player.REPEAT_MODE_ALL, true);

    /** 循环顺序：列表循环 → 单曲循环 → 随机播放 → 列表循环 */
    fun next(): PlaybackMode = when (this) {
        LIST_LOOP -> SINGLE_LOOP
        SINGLE_LOOP -> SHUFFLE
        SHUFFLE -> LIST_LOOP
    }

    companion object {

        /** 按持久化名称反查；未命中回落 [LIST_LOOP]，禁止抛异常 */
        fun fromName(name: String?): PlaybackMode =
            values().firstOrNull { it.name == name } ?: LIST_LOOP
    }
}

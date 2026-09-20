package com.lelemusic.data.source

import com.lelemusic.model.LyricResult
import com.lelemusic.model.Platform
import com.lelemusic.model.Song

/**
 * 平台歌词能力抽象（**可选能力**）。
 *
 * 平台模块不实现歌词时，[PlatformModule.lyricSource] 返回 null，播放页显示「该平台暂不支持歌词」。
 * 所有平台相关错误已在实现内收敛为 [LyricResult]（Success / NoLyric / Error），
 * 不会向 UI 抛原始异常。
 */
interface LyricSource {

    val platform: Platform

    /** 拉取一首歌的歌词；网络/解析失败返回 [LyricResult.Error]，无词返回 [LyricResult.NoLyric] */
    suspend fun lyric(song: Song): LyricResult

    /**
     * **可选兜底**：按关键字搜索歌词（云端歌词补漏）。
     *
     * 首查（按平台 songId）得到 [LyricResult.NoLyric] / [LyricResult.Error] 时，
     * `PlayerViewModel` 会用 `title + artist` 再调一次本方法，尝试在平台内搜出
     * 「有歌词的那个版本」。
     *
     * 默认实现直接返回 NoLyric（表示该平台不支持搜索兜底——本地旁路字幕源就是如此，
     * 本地文件没有云端版本可搜）。
     *
     * @param title      歌名（可能为空，实现要自己判空）
     * @param artist     歌手
     * @param durationMs 歌曲时长（ms，可能为 0）；实现可用它挑选更接近的候选
     */
    suspend fun lyricBySearch(title: String, artist: String, durationMs: Long): LyricResult =
        LyricResult.NoLyric("search_unsupported")
}

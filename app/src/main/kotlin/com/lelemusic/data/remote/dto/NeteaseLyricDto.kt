package com.lelemusic.data.remote.dto

/**
 * 网易云歌词接口 DTO。
 *
 * 接口：`music.163.com/api/song/lyric?id=xxx&lv=-1&kv=-1&tv=-1`（免加密免 Cookie，2026-09 实测可用）。
 *
 * 响应结构（节选）：
 * ```json
 * {"code":200,"nolyric":false,
 *  "lrc":{"lyric":"[00:00.00] 作词 : ...\n[00:01.00] ..."},
 *  "tlyric":{"lyric":""}}
 * ```
 *
 * ⚠️ **所有字段必须 `? = null`**（Gson + Unsafe 约定，架构文档 §7.4-1）。
 * `nolyric` / `uncollected` 为 true 表示无词/未收录，播放页显示「暂无歌词」。
 */
data class NeteaseLyricResp(
    val code: Int? = null,
    /** true = 纯音乐（无词） */
    val nolyric: Boolean? = null,
    /** true = 歌词未收录 */
    val uncollected: Boolean? = null,
    val lrc: NeteaseLrc? = null,
    val tlyric: NeteaseLrc? = null
)

data class NeteaseLrc(
    /** 原始 LRC 文本（多行 `[mm:ss.xx]`），可能为 null / 空串 */
    val lyric: String? = null
)

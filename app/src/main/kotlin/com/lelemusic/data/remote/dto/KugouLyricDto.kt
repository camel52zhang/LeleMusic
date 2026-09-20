package com.lelemusic.data.remote.dto

/**
 * 酷狗歌词接口 DTO（两步取词，2026-09 实测可用）：
 *
 * 1. 搜索：`lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=<曲名>&duration=<ms>&hash=<hash>`
 *    → 拿到候选列表，取首个非空 [KugouLyricCandidate.accesskey]；
 * 2. 下载：`lyrics.kugou.com/download?ver=1&client=pc&id=<id>&accesskey=<key>&fmt=lrc&charset=utf8`
 *    → [KugouLyricDownloadResp.content] 为 **base64 编码的 LRC 文本**。
 *
 * ⚠️ **所有字段必须 `? = null`**（Gson + Unsafe 约定，架构文档 §7.4-1）。
 */
data class KugouLyricSearchResp(
    val status: Int? = null,
    val candidates: List<KugouLyricCandidate>? = null
)

data class KugouLyricCandidate(
    val id: Long? = null,
    /** 下载第二步的凭证；缺失/空表示该候选不可下载 */
    val accesskey: String? = null,
    /** 歌词时长（毫秒，如 233000），可用来挑最匹配的候选 */
    val duration: Long? = null,
    val song: String? = null,
    val singer: String? = null
)

data class KugouLyricDownloadResp(
    val status: Int? = null,
    val id: Long? = null,
    /** base64(lrc)；可能缺失（候选失效） */
    val content: String? = null
)

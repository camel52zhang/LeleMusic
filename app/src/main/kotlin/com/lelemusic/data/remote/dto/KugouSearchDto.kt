package com.lelemusic.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 酷狗关键字搜歌（`songsearch.kugou.com/song_search_v2`，2026-09 curl 实测匿名可用）。
 *
 * M2c 歌词补漏用：搜出候选的 `FileHash`（酷狗歌曲主键），再走
 * `lyrics.kugou.com/search` → `download` 主链路取词。
 *
 * 返回字段是酷狗特有的 PascalCase 命名，用 [SerializedName] 对齐到小驼峰。
 */
data class KugouSearchResp(
    val status: Int? = null,
    val data: KugouSearchData? = null
)

data class KugouSearchData(
    val lists: List<KugouSearchItem>? = null
)

data class KugouSearchItem(
    @SerializedName("FileHash") val fileHash: String? = null,
    @SerializedName("HQFileHash") val hqFileHash: String? = null,
    @SerializedName("SQFileHash") val sqFileHash: String? = null,
    @SerializedName("SongName") val songName: String? = null,
    @SerializedName("Duration") val durationSec: Long? = null
) {
    /** 候选主 hash：优先 FileHash，退而求其次 HQ/SQ（部分条目只给高清 hash） */
    val primaryHash: String?
        get() = fileHash ?: hqFileHash ?: sqFileHash
}

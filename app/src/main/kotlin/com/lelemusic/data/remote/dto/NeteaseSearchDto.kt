package com.lelemusic.data.remote.dto

/**
 * 网易云关键字搜歌（`/api/search/get/web?type=1`，2026-09 curl 实测匿名可用）。
 *
 * M2c 歌词补漏用：首查无词时按 `title + artist` 搜出候选歌曲 id，
 * 再用候选 id 走主歌词链路取词。
 */
data class NeteaseSearchResp(
    val result: NeteaseSearchResult? = null
)

data class NeteaseSearchResult(
    val songs: List<NeteaseSearchSong>? = null
)

data class NeteaseSearchSong(
    val id: Long? = null,
    val name: String? = null,
    val duration: Long? = null,
    val artists: List<NeteaseSearchArtist>? = null,
    /** cloudsearch 返回专辑与封面；旧 search/get 也带，歌词补漏路径缺省时 Gson 安全忽略 */
    val album: NeteaseSearchAlbum? = null
)

data class NeteaseSearchArtist(
    val name: String? = null
)

data class NeteaseSearchAlbum(
    val name: String? = null,
    val picUrl: String? = null
)

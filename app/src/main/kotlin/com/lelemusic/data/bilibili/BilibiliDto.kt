package com.lelemusic.data.bilibili

import com.google.gson.annotations.SerializedName

/**
 * 哔哩哔哩接口 DTO（Gson 缺字段安全忽略，全部可空带默认值）。
 *
 * 2026-09-11 curl 实测（匿名 + `Cookie: buvid3=infoc`）：
 * - `/x/web-interface/popular` 返回 `data.list[]`，视频对象**自带 cid**；
 * - `/x/web-interface/view` 用于按 bvid 补 cid；
 * - `/x/player/playurl?fnval=16` 返回 `data.dash.audio[]`，
 *   匿名可取 30216(64k) / 30232(132k) / 30280(192k) 三档。
 */
data class BilibiliPopularResp(
    val code: Int? = null,
    val message: String? = null,
    val data: BilibiliPopularData? = null
)

data class BilibiliPopularData(
    val list: List<BilibiliVideo>? = null
)

data class BilibiliViewResp(
    val code: Int? = null,
    val message: String? = null,
    val data: BilibiliVideo? = null
)

data class BilibiliVideo(
    val bvid: String? = null,
    val aid: Long? = null,
    val cid: Long? = null,
    val title: String? = null,
    val pic: String? = null,
    val desc: String? = null,

    /** 秒（B 站接口时长单位是秒，映射时 ×1000 转毫秒） */
    val duration: Int? = null,
    val owner: BilibiliOwner? = null
)

data class BilibiliOwner(
    val name: String? = null,
    val face: String? = null
)

data class BilibiliPlayUrlResp(
    val code: Int? = null,
    val message: String? = null,
    val data: BilibiliPlayUrlData? = null
)

data class BilibiliPlayUrlData(
    /** fnval=16 时的新版 DASH 结构 */
    val dash: BilibiliDash? = null,

    /** 旧版 FLV/MP4 结构（部分老视频 dash 为空时兜底） */
    val durl: List<BilibiliDurlItem>? = null
)

data class BilibiliDash(
    val audio: List<BilibiliAudioStream>? = null
)

data class BilibiliAudioStream(
    val id: Int? = null,
    val bandwidth: Long? = null,
    @SerializedName("baseUrl") val baseUrl: String? = null,
    @SerializedName("base_url") val baseUrlSnake: String? = null
)

data class BilibiliDurlItem(
    val url: String? = null
)

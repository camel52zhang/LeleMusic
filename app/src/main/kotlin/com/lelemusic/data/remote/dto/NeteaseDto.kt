package com.lelemusic.data.remote.dto

/**
 * 网易云音乐 全部响应 DTO。
 *
 * ⚠️ **所有字段必须是 `? = null`**——Gson 用 Unsafe 绕开构造器，
 * 缺字段时非空字段会变 null，一访问就 NPE（架构文档 §7.4-1）。
 *
 * 走的是 `/api/...` 老接口，**免加密、免 Cookie**（api-feasibility A2.3）。
 */

// ===========================================================================
// 榜单详情：music.163.com/api/playlist/detail?id=3778678
// ---------------------------------------------------------------------------
// {"result":{"tracks":[{"id":..,"name":..,"artists":[...],"album":{..},
//                       "duration":210461,"fee":8}]}}
// ===========================================================================

data class NeteasePlaylistResp(
    /** 部分榜单响应不含 code，因此必须可空 */
    val code: Int? = null,
    val result: NeteasePlaylistResult? = null
)

data class NeteasePlaylistResult(
    val id: Long? = null,
    /** 榜单名；实测响应中被截断，因此 App 侧榜单名走各平台模块的本地榜单表 */
    val name: String? = null,
    val trackCount: Int? = null,
    val tracks: List<NeteaseTrack>? = null
)

data class NeteaseTrack(
    val id: Long? = null,
    val name: String? = null,
    val artists: List<NeteaseArtist>? = null,
    val album: NeteaseAlbum? = null,
    /** 时长（**毫秒**），与酷狗榜单接口的秒不同，勿混 */
    val duration: Long? = null,
    /**
     * 付费标记：0=免费 ✅ / 8=VIP ✅ 实测可播 / 1=付费单曲 ❌ / 4=付费专辑 ❓
     * （api-feasibility A2.1）
     */
    val fee: Int? = null
)

data class NeteaseArtist(
    val id: Long? = null,
    val name: String? = null
)

data class NeteaseAlbum(
    val id: Long? = null,
    val name: String? = null,
    /** `http://p1.music.126.net/...` 明文，交给 Coil 前必须 normalizeToHttps */
    val picUrl: String? = null
)

// ===========================================================================
// 直链：music.163.com/api/song/enhance/player/url?ids=[id]&br=320000
// ---------------------------------------------------------------------------
// {"data":[{"id":..,"url":"http://m10.music.126.net/...","br":320000,
//           "code":200,"fee":0,"level":"exhigh","expi":1200}],"code":200}
// ===========================================================================

data class NeteaseUrlResp(
    /** 顶层 code：200 = **接口调用成功**，不代表这首歌能播 */
    val code: Int? = null,
    val data: List<NeteaseUrlItem>? = null
)

data class NeteaseUrlItem(
    val id: Long? = null,
    /** 直链；null = 无音源。是 `http://`，必须改写为 `https://` */
    val url: String? = null,
    val br: Int? = null,
    val size: Long? = null,
    val type: String? = null,
    /**
     * **200 = 拿到直链；-110 = 拿不到（版权/付费限制）**
     * 注意：不要拿顶层 code 判断单曲可播性。
     */
    val code: Int? = null,
    val fee: Int? = null,
    /** standard(128k) / exhigh(320k) / lossless(flac) */
    val level: String? = null,
    val encodeType: String? = null,
    /** 有效期，实测 1200 秒（20 分钟）→ 直链绝不能落盘 */
    val expi: Int? = null,
    val freeTrialInfo: NeteaseFreeTrialInfo? = null,
    val freeTimeTrialPrivilege: NeteaseFreeTimeTrialPrivilege? = null
)

/** 试听信息；实测中未出现非 null 值，但接口随时可能收紧，字段先留着 */
data class NeteaseFreeTrialInfo(
    val start: Long? = null,
    val end: Long? = null
)

data class NeteaseFreeTimeTrialPrivilege(
    val resConsumable: Boolean? = null,
    val userConsumable: Boolean? = null,
    val type: Int? = null,
    val remainTime: Long? = null
)

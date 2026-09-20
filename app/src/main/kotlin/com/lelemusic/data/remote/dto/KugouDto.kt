package com.lelemusic.data.remote.dto

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * 酷狗音乐 全部响应 DTO。
 *
 * ⚠️ **所有字段必须是 `? = null`**（Gson + Unsafe，缺字段即 NPE，架构文档 §7.4-1）。
 *
 * ⚠️ JSON 里的 `320hash` / `128hash` / `320privilege` / `128filesize` 等
 * **数字开头的字段名不符合 Kotlin 标识符规范，一律用 `@SerializedName`**，
 * 禁止用反引号字段名（架构文档 §7.4-2）。
 */

// ===========================================================================
// 榜单详情：m.kugou.com/rank/info/?rankid=8888&page=1&json=true
// ---------------------------------------------------------------------------
// {"info":{...},"songs":{"total":500,"page":1,"pagesize":30,
//   "list":[{"hash":"..","filename":"..","songname":"..","singername":"..",
//            "320hash":"..","sqhash":".."}]}}
// ===========================================================================

data class KugouRankResp(
    val info: KugouRankInfo? = null,
    val songs: KugouSongPage? = null,
    /** 部分异常响应会带 status / errcode */
    val status: Int? = null,
    val errcode: Int? = null,
    val error: String? = null
)

data class KugouRankInfo(
    val rankid: Int? = null,
    val rankname: String? = null,
    val ranktype: Int? = null,
    val rank_id_publish_date: String? = null,
    val total: Int? = null,
    val intro: String? = null
)

data class KugouSongPage(
    /** 该榜歌曲总数 */
    val total: Int? = null,
    val page: Int? = null,
    /** 固定 30，取 Top50 需要翻页 */
    val pagesize: Int? = null,
    val timestamp: Long? = null,
    val list: List<KugouRankSong>? = null
)

data class KugouRankSong(
    /** 128kbps 播放 hash */
    val hash: String? = null,
    @SerializedName("320hash") val hash320: String? = null,
    @SerializedName("320filesize") val filesize320: Long? = null,
    @SerializedName("320privilege") val privilege320: Int? = null,
    /** 无损 hash */
    val sqhash: String? = null,
    @SerializedName("sqfilesize") val filesizeSq: Long? = null,
    @SerializedName("sqprivilege") val privilegeSq: Int? = null,
    /** "歌手 - 歌名" 混合串；展示用 songname / singername */
    val filename: String? = null,
    val songname: String? = null,
    val singername: String? = null,
    /**
     * 专辑封面（2026-09-12 实测 TOP500 响应带此字段），形如
     * `http://imge.kugou.com/stdmusic/{size}/....jpg`——`{size}` 占位符须替换，
     * 见 [com.lelemusic.data.kugou.KugouMapper]。
     */
    @SerializedName("album_sizable_cover") val albumSizableCover: String? = null,
    /** 部分榜单返回；为 null 时取链时用 playInfo 的 timeLength 回填判断 */
    val duration: Int? = null
)

// ===========================================================================
// 榜单列表枚举（OQ-2 补测「网络红歌榜」rankid 用，二手来源未验证）
// m.kugou.com/rank/list?json=true
// ===========================================================================

data class KugouTopListResp(
    val info: List<KugouTopListGroup>? = null,
    val rank: KugouTopListWrapper? = null,
    val status: Int? = null,
    val error: String? = null
)

data class KugouTopListWrapper(
    val list: List<KugouTopListItem>? = null,
    val total: Int? = null
)

data class KugouTopListGroup(
    val rankid: Int? = null,
    val rankname: String? = null,
    val list: List<KugouTopListItem>? = null
)

data class KugouTopListItem(
    val rankid: Int? = null,
    val rankname: String? = null,
    val intro: String? = null,
    val img: String? = null
)

// ===========================================================================
// 取链：m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash={hash}
// ---------------------------------------------------------------------------
// {"status":1,"errcode":0,"url":"https://sharefs.kugou.com/.../yp/full/xxx.mp3",
//  "backup_url":["https://sharefs.tx.kugou.com/..."],"timeLength":247,
//  "bitRate":128,"album_img":"http://imge.kugou.com/stdmusic/{size}/...",
//  "privilege":0,"extra":{"128hash":"..","320hash":"..","sqhash":".."}}
// ===========================================================================

data class KugouPlayInfoResp(
    /** 1 = 成功 */
    val status: Int? = null,
    val errcode: Int? = null,
    /** 人话原因：如"需要付费"/"版权限制"——区分 status=0 的具体场景 */
    val error: String? = null,
    val hash: String? = null,
    val songName: String? = null,
    val singerName: String? = null,
    val fileName: String? = null,
    /** 主播放地址；路径含 `/yp/full/` 表示全曲 */
    val url: String? = null,
    /**
     * 备用域名地址列表，主 URL 为空或失效时兜底取第一项。
     *
     * ⚠️ **必须声明为 [JsonElement] 而非 `List<String>`**：酷狗对部分歌曲会把
     * `backup_url` 返回成**对象**（`{}` 或带 errcode 的对象），Gson 按数组解析会直接抛
     * `IllegalStateException: Expected BEGIN_ARRAY but was BEGIN_OBJECT`，连主 `url`
     * 都没机会用（自检台 `山风山风等等我` 即此坑，E_PARSE）。改为 JsonElement 接受任意
     * JSON 形态，由 [com.lelemusic.data.kugou.KugouUrlResolver.firstBackupUrl] 手工提取。
     */
    val backup_url: JsonElement? = null,
    /** 真实时长（**秒**），与榜单时长比对可判定是否全曲 */
    val timeLength: Int? = null,
    val bitRate: Int? = null,
    val fileSize: Long? = null,
    val extName: String? = null,
    /** 含 `{size}` 占位符，必须替换成具体数字（如 150）否则图片 404 */
    val album_img: String? = null,
    /** 0 = 无版权限制 */
    val privilege: Int? = null,
    val pay_type: Int? = null,
    @SerializedName("128privilege") val privilege128: Int? = null,
    @SerializedName("320privilege") val privilege320: Int? = null,
    val sqprivilege: Int? = null,
    val extra: KugouExtra? = null,
    /** 高潮片段信息，可作为试听降级 */
    val climax_info: KugouClimaxInfo? = null
)

data class KugouExtra(
    @SerializedName("128hash") val hash128: String? = null,
    @SerializedName("320hash") val hash320: String? = null,
    val sqhash: String? = null,
    @SerializedName("128filesize") val filesize128: Long? = null,
    @SerializedName("320filesize") val filesize320: Long? = null,
    val sqfilesize: Long? = null
)

data class KugouClimaxInfo(
    val timelength: String? = null,
    val start_time: String? = null,
    val end_time: String? = null
)

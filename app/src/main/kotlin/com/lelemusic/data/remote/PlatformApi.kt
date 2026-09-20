package com.lelemusic.data.remote

import com.lelemusic.data.remote.dto.KugouLyricDownloadResp
import com.lelemusic.data.remote.dto.KugouLyricSearchResp
import com.lelemusic.data.remote.dto.KugouPlayInfoResp
import com.lelemusic.data.remote.dto.KugouRankResp
import com.lelemusic.data.remote.dto.KugouSearchResp
import com.lelemusic.data.remote.dto.KugouTopListResp
import com.lelemusic.data.remote.dto.NeteaseLyricResp
import com.lelemusic.data.remote.dto.NeteasePlaylistResp
import com.lelemusic.data.remote.dto.NeteaseSearchResp
import com.lelemusic.data.remote.dto.NeteaseUrlResp
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * 两个平台的 Retrofit 接口（架构文档 §7.5；QQ 已于 2026-09 下线，平台按需增删）。
 *
 * 每个平台的接口用 `@Url` 全路径，由各自的平台模块对象（`XxxModule`）内部
 * `retrofit.create(...)` 构建，**本文件不再随平台增删改动**。
 *
 * 两条约定：
 * 1. **统一用 `@Url` 传全路径**，Retrofit 的 baseUrl 只是占位（`https://placeholder.invalid/`）；
 * 2. `data` 这类参数是 **URL-encoded JSON 字符串**，由调用方用
 *    `URLEncoder.encode(json, "UTF-8")` 手动编码后传入——Retrofit 不会帮你把对象转 JSON。
 */

interface NeteaseApi {

    /**
     * 榜单（歌单）详情。
     *
     * 免加密、免 Cookie 的老接口（api-feasibility A2.1 亲自验证）。
     */
    @GET
    suspend fun playlistDetail(
        @Url url: String,
        @Query("id") id: Long
    ): NeteasePlaylistResp

    /**
     * 直链接口。
     *
     * @param ids 形如 `[3399839173]`（方括号包裹的 id 列表，Retrofit 会做 URL 编码）
     * @param br  码率：320000 / 192000 / 128000
     *
     * ⚠️ 返回的 url 是 `http://` 明文，Android 9+ 禁 cleartext，
     * 调用方必须过 `normalizeToHttps()`（架构文档 §7.10）。
     */
    @GET
    suspend fun songUrl(
        @Url url: String,
        @Query("ids") ids: String,
        @Query("br") br: Int
    ): NeteaseUrlResp

    /**
     * 歌词（免加密、免 Cookie，2026-09 实测可用，付费墙歌曲同样返回完整 LRC）。
     *
     * `lv=-1` 表示不带翻译（`tlyric` 基本为空，暂不做双语展示）。
     */
    @GET
    suspend fun lyric(
        @Url url: String,
        @Query("id") id: Long,
        @Query("lv") lv: Int = -1,
        @Query("kv") kv: Int = -1,
        @Query("tv") tv: Int = -1
    ): NeteaseLyricResp

    /**
     * 关键字搜歌（`/api/search/get/web?type=1`，2026-09 实测匿名可用）。
     *
     * M2c 歌词补漏：首查（按 songId）无词时，用 `title + artist` 搜候选再取词。
     */
    @GET
    suspend fun searchSong(
        @Url url: String,
        @Query("s") keyword: String,
        @Query("type") type: Int = 1,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 1
    ): NeteaseSearchResp

    /**
     * 云搜索（`POST /api/cloudsearch/pc`，2026-09-11 curl 实测匿名可用，结果质量高于旧 search/get）。
     *
     * 搜索页主通道：`result.songs[]` 含 id/name/artists/album(picUrl)/duration，
     * 反序列化复用 [NeteaseSearchResp]（缺字段 Gson 安全忽略）。
     */
    @FormUrlEncoded
    @POST
    suspend fun cloudSearchSong(
        @Url url: String,
        @Field("s") keyword: String,
        @Field("type") type: Int = 1,
        @Field("limit") limit: Int = 30,
        @Field("offset") offset: Int = 0
    ): NeteaseSearchResp
}

interface KugouApi {

    /**
     * 榜单详情。
     *
     * 固定每页 30 条（`pagesize`），取 Top50 需要翻两页。
     */
    @GET
    suspend fun rankInfo(
        @Url url: String,
        @Query("rankid") rankid: Int,
        @Query("page") page: Int,
        @Query("json") json: String = "true"
    ): KugouRankResp

    /**
     * 取链接口（唯一实测可用的酷狗方案，api-feasibility B3.2）。
     *
     * 已失效、不要再用：`wwwapi.kugou.com/yy/index.php?r=play/getdata`（err_code 20010 / 30020）。
     */
    @GET
    suspend fun playInfo(
        @Url url: String,
        @Query("cmd") cmd: String = "playInfo",
        @Query("hash") hash: String
    ): KugouPlayInfoResp

    /**
     * 榜单列表枚举（用于真机补测「网络红歌榜」的 rankid，OQ-2）。
     *
     * ⚠️ 二手来源，未亲自验证；若返回 "Access Deny" 说明已被风控。
     */
    @GET
    suspend fun rankList(
        @Url url: String,
        @Query("json") json: String = "true"
    ): KugouTopListResp

    /**
     * 歌词第一步：搜索候选（`lyrics.kugou.com/search`，2026-09 实测可用）。
     */
    @GET
    suspend fun lyricSearch(
        @Url url: String,
        @Query("ver") ver: Int = 1,
        @Query("man") man: String = "yes",
        @Query("client") client: String = "pc",
        @Query("keyword") keyword: String,
        @Query("duration") duration: Long,
        @Query("hash") hash: String
    ): KugouLyricSearchResp

    /**
     * 歌词第二步：下载 LRC（`lyrics.kugou.com/download`，content 为 base64）。
     */
    @GET
    suspend fun lyricDownload(
        @Url url: String,
        @Query("ver") ver: Int = 1,
        @Query("client") client: String = "pc",
        @Query("id") id: Long,
        @Query("accesskey") accesskey: String,
        @Query("fmt") fmt: String = "lrc",
        @Query("charset") charset: String = "utf8"
    ): KugouLyricDownloadResp

    /**
     * 关键字搜歌（`songsearch.kugou.com/song_search_v2`，2026-09 实测匿名可用）。
     *
     * M2c 歌词补漏：搜出候选 [com.lelemusic.data.remote.dto.KugouSearchItem.primaryHash]，
     * 再走 lyricSearch → lyricDownload 主链路取词。
     */
    @GET
    suspend fun searchSongV2(
        @Url url: String,
        @Query("keyword") keyword: String,
        @Query("page") page: Int = 1,
        @Query("pagesize") pagesize: Int = 5,
        @Query("platform") platform: String = "WebFilter"
    ): KugouSearchResp
}

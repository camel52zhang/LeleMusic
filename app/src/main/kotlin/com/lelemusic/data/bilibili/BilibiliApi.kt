package com.lelemusic.data.bilibili

import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * 哔哩哔哩接口（配合 Retrofit `@Url` 传全路径，约定见 PlatformApi.kt）。
 *
 * 三个必须的请求头（2026-09-11 curl 实测验证）：
 * - `User-Agent`：CDN 与 API 均校验，缺省 UA 会被风控；
 * - `Referer: https://www.bilibili.com/`：API 与取链 CDN 双双必须；
 * - `Cookie: buvid3=infoc`：匿名搜索/热门的最低要求（真实 buvid3 由服务端 Set-Cookie
 *   下发，但实测固定占位值即可通过校验，与 MusicFree 插件做法一致）。
 */
interface BilibiliApi {

    /** 热门视频（`/x/web-interface/popular`，匿名可用，视频对象自带 cid） */
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer: https://www.bilibili.com/",
        "Cookie: buvid3=infoc"
    )
    @GET
    suspend fun popular(
        @Url url: String,
        @Query("ps") ps: Int = 50,
        @Query("pn") pn: Int = 1
    ): BilibiliPopularResp

    /** 视频详情（按 bvid 取 cid；榜单数据缺失 cid 时的补取通道） */
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer: https://www.bilibili.com/",
        "Cookie: buvid3=infoc"
    )
    @GET
    suspend fun view(
        @Url url: String,
        @Query("bvid") bvid: String
    ): BilibiliViewResp

    /**
     * 取链（`/x/player/playurl?fnval=16` → DASH）。
     *
     * 返回 `data.dash.audio[]`，按 `bandwidth` 降序取最高档（匿名最高 192k）；
     * 老视频 dash 为空时回落 `data.durl[0].url`。
     */
    @Headers(
        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer: https://www.bilibili.com/",
        "Cookie: buvid3=infoc"
    )
    @GET
    suspend fun playUrl(
        @Url url: String,
        @Query("bvid") bvid: String,
        @Query("cid") cid: Long,
        @Query("fnval") fnval: Int = 16
    ): BilibiliPlayUrlResp

    companion object {
        const val POPULAR_URL = "https://api.bilibili.com/x/web-interface/popular"
        const val VIEW_URL = "https://api.bilibili.com/x/web-interface/view"
        const val PLAY_URL = "https://api.bilibili.com/x/player/playurl"
    }
}

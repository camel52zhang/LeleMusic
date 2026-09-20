package com.lelemusic.data.bilibili

import android.content.Context
import com.lelemusic.data.source.LyricSource
import com.lelemusic.data.source.PlatformModule
import com.lelemusic.data.source.PlayUrlResolver
import com.lelemusic.data.source.RankSource
import com.lelemusic.model.ChartDef
import com.lelemusic.model.Platform
import retrofit2.Retrofit

/**
 * 哔哩哔哩平台身份（全工程唯一实例）。
 *
 * `id` 参与构造 `Song.uid`，**不可更改**，否则历史 uid 失效。
 *
 * `platformSongId` 约定：`bvid`，榜单数据自带 cid 时用 `bvid|cid` 复合形态
 * （取链器优先用 cid 免一次 view 请求；`songPageUrl` 只取竖线前半段）。
 */
object BilibiliPlatform : Platform {

    override val id = "bilibili"

    override val displayName = "哔哩哔哩"

    override val seedColor = 0xFFFB7299L

    override fun songPageUrl(platformSongId: String): String {
        val bvid = platformSongId.substringBefore('|')
        return "https://www.bilibili.com/video/$bvid"
    }
}

/**
 * 哔哩哔哩平台模块（2026-09-11 端到端 curl 实测：热门榜单 / view / playurl 匿名全通，
 * DASH 音频 64k/132k/192k 三档可下载，头部 `ftypiso5` 合法 fMP4）。
 *
 * 榜单：「热门视频」（`/x/web-interface/popular`，匿名可用）。
 *
 * ⚠️ 已知边界：
 * - 内容是 B 站视频音轨（含翻唱/MV/广播剧），非全量曲库；无搜索（P1 接入）；
 * - DASH 音频 URL 由 mcdn CDN 动态下发、有时效，播放时必须实时取链
 *   （复用既有 ResolveDataSpecResolver 5 分钟 TTL 机制，无需特判）。
 */
object BilibiliModule : PlatformModule {

    override val platform: Platform = BilibiliPlatform

    override val charts: List<ChartDef> = listOf(
        ChartDef(BilibiliPlatform, "popular", "热门视频", enabled = true, isDefault = true)
    )

    override fun rankSource(retrofit: Retrofit): RankSource =
        BilibiliRankSource(retrofit.create(BilibiliApi::class.java))

    override fun resolvers(retrofit: Retrofit): List<PlayUrlResolver> =
        listOf(BilibiliUrlResolver(retrofit.create(BilibiliApi::class.java)))

    override fun lyricSource(retrofit: Retrofit, context: Context): LyricSource? = null
}

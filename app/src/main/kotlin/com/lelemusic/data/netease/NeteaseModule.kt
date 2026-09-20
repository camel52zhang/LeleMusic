package com.lelemusic.data.netease

import android.content.Context
import com.lelemusic.data.remote.NeteaseApi
import com.lelemusic.data.source.LyricSource
import com.lelemusic.data.source.PlatformModule
import com.lelemusic.data.source.PlayUrlResolver
import com.lelemusic.data.source.RankSource
import com.lelemusic.model.ChartDef
import com.lelemusic.model.Platform
import retrofit2.Retrofit

/**
 * 网易云音乐平台身份（全工程唯一实例）。
 *
 * `id` 参与构造 `Song.uid`，**不可更改**，否则历史 uid 失效。
 */
object NeteasePlatform : Platform {

    override val id = "netease"

    override val displayName = "网易云音乐"

    override val seedColor = 0xFFC20C0C

    override fun songPageUrl(platformSongId: String): String =
        "https://music.163.com/#/song?id=$platformSongId"
}

/**
 * 网易云音乐平台模块。
 *
 * 榜单：热歌榜（默认）/ 新歌榜已上线；飙升 / 原创为 P1 预留。
 *
 * ⚠️ 已知待验证项：
 * - `3779629`（新歌榜）为二手来源，**未实测**（OQ-4）；
 * 真机跑一次若为空榜，直接把 `enabled` 关掉或换同平台其他榜单即可。
 */
object NeteaseModule : PlatformModule {

    override val platform: Platform = NeteasePlatform

    override val charts: List<ChartDef> = listOf(
        ChartDef(NeteasePlatform, "3778678", "热歌榜", enabled = true, isDefault = true),
        ChartDef(NeteasePlatform, "3779629", "新歌榜", enabled = true),
        ChartDef(NeteasePlatform, "19723756", "飙升榜", enabled = false), // P1
        ChartDef(NeteasePlatform, "2884035", "原创榜", enabled = false)   // P1
    )

    override fun rankSource(retrofit: Retrofit): RankSource =
        NeteaseRankSource(retrofit.create(NeteaseApi::class.java))

    override fun resolvers(retrofit: Retrofit): List<PlayUrlResolver> =
        listOf(NeteaseUrlResolver(retrofit.create(NeteaseApi::class.java)))

    override fun lyricSource(retrofit: Retrofit, context: Context): LyricSource? =
        NeteaseLyricSource(retrofit.create(NeteaseApi::class.java))
}

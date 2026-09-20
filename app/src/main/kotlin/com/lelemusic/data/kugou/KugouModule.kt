package com.lelemusic.data.kugou

import android.content.Context
import com.lelemusic.data.remote.KugouApi
import com.lelemusic.data.source.LyricSource
import com.lelemusic.data.source.PlatformModule
import com.lelemusic.data.source.PlayUrlResolver
import com.lelemusic.data.source.RankSource
import com.lelemusic.model.ChartDef
import com.lelemusic.model.Platform
import retrofit2.Retrofit

/**
 * 酷狗音乐平台身份（全工程唯一实例）。
 *
 * `id` 参与构造 `Song.uid`，**不可更改**，否则历史 uid 失效。
 */
object KugouPlatform : Platform {

    override val id = "kugou"

    override val displayName = "酷狗音乐"

    override val seedColor = 0xFF0092FF

    override fun songPageUrl(platformSongId: String): String =
        "https://www.kugou.com/song/#hash=$platformSongId"
}

/**
 * 酷狗音乐平台模块。
 *
 * 榜单：TOP500（默认）/ 飙升榜已上线；抖音热歌榜为 P1 预留。
 *
 * ⚠️ 已知待验证项：
 * - `6666`（飙升榜）为二手来源，**未实测**（OQ-2，需求里的「网络红歌榜」未找到 rankid，
 *   临时用飙升榜占位）；
 * 真机跑一次若为空榜，直接把 `enabled` 关掉或换同平台其他榜单即可。
 */
object KugouModule : PlatformModule {

    override val platform: Platform = KugouPlatform

    override val charts: List<ChartDef> = listOf(
        ChartDef(KugouPlatform, "8888", "TOP500", enabled = true, isDefault = true),
        ChartDef(KugouPlatform, "6666", "飙升榜", enabled = true), // ⚠️ 见 OQ-2
        ChartDef(KugouPlatform, "52144", "抖音热歌榜", enabled = false) // P1
    )

    override fun rankSource(retrofit: Retrofit): RankSource =
        KugouRankSource(retrofit.create(KugouApi::class.java))

    override fun resolvers(retrofit: Retrofit): List<PlayUrlResolver> =
        listOf(KugouUrlResolver(retrofit.create(KugouApi::class.java)))

    override fun lyricSource(retrofit: Retrofit, context: Context): LyricSource? =
        KugouLyricSource(retrofit.create(KugouApi::class.java))
}

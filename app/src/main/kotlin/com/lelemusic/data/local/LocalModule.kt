package com.lelemusic.data.local

import android.content.Context
import com.lelemusic.data.source.LyricSource
import com.lelemusic.data.source.PlatformModule
import com.lelemusic.data.source.RankSource
import com.lelemusic.model.LocalMedia
import com.lelemusic.model.Platform
import retrofit2.Retrofit

/**
 * 本地音乐平台身份（全工程唯一实例）。
 *
 * `id = "local"` 参与构造 `Song.uid`（`local:<uriHash>`），**不可更改**。
 * 没有网页播放页（[songPageUrl] 返回空串，播放页不会提供「跳转平台」出口）。
 */
object LocalPlatform : Platform {

    override val id: String = LocalMedia.PLATFORM_ID

    override val displayName: String = "本地音乐"

    override val seedColor: Long = 0xFF607D8B

    override fun songPageUrl(platformSongId: String): String = ""
}

/**
 * 本地音乐平台模块（插件化：只是 [PlatformModule] 的一个实现）。
 *
 * - 榜单：**有条件的动态榜单**——每个「本地歌单」（导入 / 挂载命名后创建，在我的歌单里
 *   带「导入」/「挂载」徽标）就是一个下一级榜单 Tab（[LocalRankSource] 直读
 *   [com.lelemusic.repo.LibraryRepository]）；没有任何本地歌单时
 *   `charts()` 为空，`ChartRepository.platforms()` 会把本平台过滤掉 → 首页不显示「本地音乐」Tab；
 * - 无取链策略（resolvers 默认空）——本地曲目 MediaItem 带真实 content/file uri，
 *   `ResolveDataSpecResolver` 对非占位 scheme 直放；
 * - 提供歌词能力 [LocalSidecarLyricSource]：同目录同名 .lrc/.srt 旁路字幕。
 *
 * 增删本地能力只改本模块 + `AppGraph.modules` 一处，不动榜单/播放/歌词主链路。
 */
object LocalModule : PlatformModule {

    override val platform: Platform = LocalPlatform

    override fun rankSource(retrofit: Retrofit): RankSource = LocalRankSource()

    override fun lyricSource(retrofit: Retrofit, context: Context): LyricSource? =
        LocalSidecarLyricSource(context)
}

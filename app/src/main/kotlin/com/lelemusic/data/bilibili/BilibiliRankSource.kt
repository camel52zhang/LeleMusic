package com.lelemusic.data.bilibili

import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.safeCall
import com.lelemusic.data.bilibili.BilibiliApi.Companion.POPULAR_URL
import com.lelemusic.data.source.RankPage
import com.lelemusic.data.source.RankSource
import com.lelemusic.model.ChartDef
import com.lelemusic.model.PlayableStatus
import com.lelemusic.model.Platform
import com.lelemusic.model.Song

/**
 * 哔哩哔哩榜单数据源：`/x/web-interface/popular`（2026-09-11 curl 实测匿名可用）。
 *
 * `platformSongId = "bvid|cid"`：热门接口自带 cid，直接塞进复合 id，
 * 取链时免一次 view 请求；缺 cid 的场景由取链器按 bvid 补取。
 */
class BilibiliRankSource(
    private val api: BilibiliApi
) : RankSource {

    override val platform: Platform = BilibiliPlatform

    override fun charts(): List<ChartDef> = BilibiliModule.activeCharts

    override suspend fun fetchChart(chartId: String, limit: Int): RankPage = safeCall {
        check(chartId == CHART_POPULAR) {
            "unknown bilibili chartId=$chartId"
        }
        val safeLimit = limit.coerceIn(1, 50)

        val resp = api.popular(POPULAR_URL, ps = safeLimit)
        val code = resp.code
        if (code != null && code != 0) {
            throw AppException(AppError.Network, "bilibili popular code=$code ${resp.message.orEmpty()}")
        }

        val videos = resp.data?.list.orEmpty()
        if (videos.isEmpty()) {
            throw AppException(AppError.EmptyData, "bilibili popular has 0 videos")
        }

        val songs = videos.take(safeLimit).mapIndexedNotNull { index, video ->
            val bvid = video.bvid?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val cid = video.cid?.toString().orEmpty()
            Song(
                uid = "${BilibiliPlatform.id}:$bvid",
                platform = BilibiliPlatform,
                platformSongId = if (cid.isBlank()) bvid else "$bvid|$cid",
                title = video.title.orEmpty().ifBlank { "未知视频" },
                artist = video.owner?.name.orEmpty().ifBlank { "未知UP主" },
                album = "B站视频",
                durationMs = (video.duration ?: 0).toLong() * 1000L,
                coverUrl = video.pic?.let { pic ->
                    if (pic.startsWith("http://")) "https://${pic.substringAfter("http://")}" else pic
                },
                rank = index + 1,
                playable = PlayableStatus.UNKNOWN
            )
        }
        if (songs.isEmpty()) {
            throw AppException(AppError.EmptyData, "bilibili popular mapped 0 songs")
        }

        RankPage(songs = songs, updatedAt = "")
    }

    private companion object {
        const val CHART_POPULAR = "popular"
    }
}

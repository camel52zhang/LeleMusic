package com.lelemusic.data.netease

import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.safeCall
import com.lelemusic.data.remote.NeteaseApi
import com.lelemusic.data.source.RankPage
import com.lelemusic.data.source.RankSource
import com.lelemusic.model.ChartDef
import com.lelemusic.model.Platform

/** 网易云榜单接口（配合 Retrofit @Url 传全路径） */
private const val PLAYLIST_URL = "https://music.163.com/api/playlist/detail"

/**
 * 网易云榜单数据源：`/api/playlist/detail`（api-feasibility A2.1 亲自验证）。
 *
 * 免加密、免 Cookie——**不需要自建 NeteaseCloudMusicApi 中转服务**（A2.3）。
 */
class NeteaseRankSource(
    private val api: NeteaseApi
) : RankSource {

    override val platform: Platform = NeteasePlatform

    override fun charts(): List<ChartDef> = NeteaseModule.activeCharts

    override suspend fun fetchChart(chartId: String, limit: Int): RankPage = safeCall {
        val safeLimit = limit.coerceIn(1, 500)
        val playlistId = chartId.toLongOrNull()
            ?: throw AppException(AppError.Parse, "invalid netease playlist id=$chartId")

        val resp = api.playlistDetail(PLAYLIST_URL, playlistId)

        // 部分榜单响应不含顶层 code，只在明确非 200 时判失败
        val code = resp.code
        if (code != null && code != 200) {
            throw AppException(AppError.Network, "netease playlist code=$code id=$playlistId")
        }

        val result = resp.result
            ?: throw AppException(AppError.Parse, "netease result missing, id=$playlistId")

        val tracks = result.tracks.orEmpty()
        if (tracks.isEmpty()) {
            throw AppException(AppError.EmptyData, "netease playlist id=$playlistId has 0 tracks")
        }

        val songs = NeteaseMapper.toSongs(tracks.take(safeLimit), startRank = 1)
        if (songs.isEmpty()) {
            throw AppException(AppError.EmptyData, "netease playlist id=$playlistId mapped 0 songs")
        }

        // 榜单名不从接口取（实测被截断），更新时间网易接口也不提供
        RankPage(songs = songs, updatedAt = "")
    }
}

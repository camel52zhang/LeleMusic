package com.lelemusic.data.local

import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.safeCall
import com.lelemusic.core.di.AppGraph
import com.lelemusic.data.source.RankPage
import com.lelemusic.data.source.RankSource
import com.lelemusic.model.ChartDef
import com.lelemusic.model.Platform
import com.lelemusic.model.toSong

/**
 * 本地音乐榜单数据源：每个「导入 / 挂载」产生的本地歌单就是一个下一级榜单 Tab。
 *
 * 数据直读 [AppGraph.libraryRepository] 的本地歌单（origin = import / mount）——
 * **无网络请求**；`charts()` 每次实时读取，因此：
 * - 「导入 / 挂载 → 命名」后榜单 Tab 立即出现；
 * - 在我的歌单里**重命名**本地歌单 → 榜单 Tab 名同步更新；
 * - 在我的歌单里**删除**本地歌单 → 榜单 Tab 消失；全部删光则「本地音乐」Tab 隐藏
 *   （`ChartRepository.platforms()` 按 charts 非空过滤）。
 */
class LocalRankSource : RankSource {

    override val platform: Platform = LocalPlatform

    override fun charts(): List<ChartDef> =
        AppGraph.libraryRepository.playlists.value
            .filter { it.isLocalChart }
            .mapIndexed { index, playlist ->
                ChartDef(
                    platform = LocalPlatform,
                    chartId = playlist.id,
                    title = playlist.name,
                    enabled = true,
                    isDefault = index == 0
                )
            }

    override suspend fun fetchChart(chartId: String, limit: Int): RankPage = safeCall {
        val playlist = AppGraph.libraryRepository.playlists.value
            .firstOrNull { it.id == chartId && it.isLocalChart }
            ?: throw AppException(AppError.Parse, "local chart not found id=$chartId")

        val songs = playlist.songs.mapNotNull { storable ->
            storable.toSong(AppGraph.libraryRepository.platformById)
        }
        if (songs.isEmpty()) {
            throw AppException(AppError.EmptyData, "local chart id=$chartId has 0 songs")
        }
        // 榜单顺序即歌单内顺序（导入时已按文件名自然排序）；更新时间不适用，留空
        RankPage(songs = songs.take(limit.coerceAtLeast(1)), updatedAt = "")
    }
}

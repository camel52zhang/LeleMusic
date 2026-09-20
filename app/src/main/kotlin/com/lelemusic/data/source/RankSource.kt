package com.lelemusic.data.source

import com.lelemusic.model.ChartDef
import com.lelemusic.model.Platform
import com.lelemusic.model.Song

/**
 * 一次榜单拉取的结果。
 *
 * @property songs     按排名升序的歌曲列表
 * @property updatedAt 榜单更新时间原文（可能是日期，也可能是空串；UI 兜底处理）
 */
data class RankPage(
    val songs: List<Song>,
    val updatedAt: String
)

/**
 * 榜单数据源抽象（架构文档 §1.2：抽象只做在数据源层）。
 *
 * 实现类（在 `data/netease/`、`data/kugou/` 两个子包下）：
 * - `NeteaseRankSource`
 * - `KugouRankSource`
 *
 * 契约：
 * 1. 失败一律抛 `AppException`，由 Repository 统一收敛；**禁止返回 null**；
 * 2. 返回空列表应抛 `AppException(AppError.EmptyData)`，让上层能区分「接口挂了」和「真的没歌」。
 */
interface RankSource {

    val platform: Platform

    /** 该平台已启用的榜单列表 */
    fun charts(): List<ChartDef>

    /**
     * @param chartId 平台侧榜单 ID
     * @param limit   需要返回的条目数（首页 Top50）
     */
    suspend fun fetchChart(chartId: String, limit: Int = 50): RankPage
}

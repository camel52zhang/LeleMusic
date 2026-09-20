package com.lelemusic.data.kugou

import android.util.Log
import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.safeCall
import com.lelemusic.data.remote.KugouApi
import com.lelemusic.data.source.RankPage
import com.lelemusic.data.source.RankSource
import com.lelemusic.model.ChartDef
import com.lelemusic.model.Platform
import com.lelemusic.model.Song

/** 酷狗榜单接口（配合 Retrofit @Url 传全路径） */
private const val RANK_URL = "https://m.kugou.com/rank/info/"

/** 酷狗榜单接口固定每页 30 条，取 50 需要翻页 */
private const val PAGE_SIZE = 30

/**
 * 酷狗榜单数据源：`m.kugou.com/rank/info/?rankid=&page=&json=true`
 * （api-feasibility A3.1 亲自验证，TOP500 rankid=8888）。
 *
 * ⚠️ 该接口**固定每页 30 条**（`pagesize`），而首页要 Top50，因此需要翻第 2 页。
 * ⚠️ `m.kugou.com/plist/rank/home` 已被反爬（"Access Deny"），**不要**使用。
 */
class KugouRankSource(
    private val api: KugouApi
) : RankSource {

    override val platform: Platform = KugouPlatform

    override fun charts(): List<ChartDef> = KugouModule.activeCharts

    override suspend fun fetchChart(chartId: String, limit: Int): RankPage = safeCall {
        val safeLimit = limit.coerceIn(1, 500)
        val rankId = chartId.toIntOrNull()
            ?: throw AppException(AppError.Parse, "invalid kugou rankid=$chartId")

        // 每页 30 条，向上取整；最多翻 5 页，防止榜单异常导致死循环
        val pagesNeeded = ((safeLimit + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtMost(5)
        val songs = ArrayList<Song>(safeLimit)
        var updatedAt = ""

        for (page in 1..pagesNeeded) {
            if (songs.size >= safeLimit) break

            val resp = api.rankInfo(RANK_URL, rankId, page)

            // 反爬或参数错误时酷狗会返回 status != 1
            val status = resp.status
            if (status != null && status != 1) {
                throw AppException(
                    AppError.Network,
                    "kugou rank status=$status errcode=${resp.errcode} rankid=$rankId page=$page"
                )
            }

            val list = resp.songs?.list.orEmpty()
            if (list.isEmpty()) break

            if (page == 1) {
                updatedAt = resp.info?.rank_id_publish_date.orEmpty()
            }

            val startRank = (page - 1) * PAGE_SIZE + 1
            songs.addAll(KugouMapper.toSongs(list, startRank))

            // 不足一页说明没有下一页了
            if (list.size < PAGE_SIZE) break
        }

        if (songs.isEmpty()) {
            throw AppException(AppError.EmptyData, "kugou rankid=$rankId returned 0 songs")
        }

        Log.i(TAG, "kugou rankid=$rankId fetched ${songs.size} songs")
        RankPage(songs = songs.take(safeLimit), updatedAt = updatedAt)
    }

    private companion object {
        const val TAG = "KugouRankSource"
    }
}

package com.lelemusic.data.bilibili

import android.util.Log
import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.safeCall
import com.lelemusic.data.bilibili.BilibiliApi.Companion.PLAY_URL
import com.lelemusic.data.bilibili.BilibiliApi.Companion.VIEW_URL
import com.lelemusic.data.source.PlayUrlResolver
import com.lelemusic.data.source.ResolverEndpoints
import com.lelemusic.model.Platform
import com.lelemusic.model.ResolvedTrack
import com.lelemusic.model.Song

/** 策略 ID（自检台展示与开关覆盖的 key；**不可更改**，否则已保存的开关覆盖失效） */
private const val STRATEGY_ID = "bilibili.dash"

/**
 * 哔哩哔哩取链：`/x/player/playurl?fnval=16` → DASH 音频（2026-09-11 端到端 curl 实测）。
 *
 * 链路：`platformSongId`（`bvid|cid` 或 `bvid`）→（缺 cid 时 view 补取）→ playurl →
 * `data.dash.audio[]` 按 bandwidth 降序取最高档（匿名最高 30280=192k）；
 * 老视频 dash 为空时回落 `data.durl[0].url`（旧版 MP4 直链）。
 *
 * 播放时必须带 `Referer: https://www.bilibili.com/` 与桌面 UA，
 * 否则 mcdn CDN 拒绝 range 请求（实测缺 Referer 返回异常响应）。
 */
class BilibiliUrlResolver(
    private val api: BilibiliApi
) : PlayUrlResolver {

    override val platform: Platform = BilibiliPlatform
    override val strategyId: String = STRATEGY_ID
    override val priority: Int = 10
    override val defaultEnabled: Boolean = true

    init {
        // 只开放 playurl 主接口编辑；view 辅助接口（补 cid）保持内置
        ResolverEndpoints.register(STRATEGY_ID, PLAY_URL)
    }

    override suspend fun resolve(song: Song): ResolvedTrack = safeCall {
        val raw = song.platformSongId
        if (raw.isBlank()) {
            throw AppException(AppError.Parse, "bilibili song id is blank, uid=${song.uid}")
        }
        val bvid = raw.substringBefore('|').trim()
        if (bvid.isBlank()) {
            throw AppException(AppError.Parse, "bilibili bvid is blank, uid=${song.uid}")
        }
        val cidPart = raw.substringAfter('|', "").trim()

        val cid = cidPart.toLongOrNull() ?: fetchCid(bvid)
        val stream = requestAudioStream(bvid, cid)

        val url = stream.baseUrl ?: stream.baseUrlSnake
        if (url.isNullOrBlank()) {
            throw AppException(
                AppError.PlaySourceUnavailable,
                "bilibili dash audio url empty bvid=$bvid cid=$cid strategy=$strategyId"
            )
        }

        Log.i(TAG, "bilibili resolved: audioId=${stream.id} bw=${stream.bandwidth} bvid=$bvid")
        ResolvedTrack(
            url = url,
            isFull = true,
            headers = mapOf(
                "Referer" to "https://www.bilibili.com/",
                "User-Agent" to USER_AGENT
            ),
            strategyId = strategyId,
            quality = qualityOf(stream)
        )
    }

    /** 榜单数据没带 cid 时按 bvid 补取（多一次 view 请求，可接受） */
    private suspend fun fetchCid(bvid: String): Long {
        val resp = api.view(VIEW_URL, bvid)
        val cid = resp.data?.cid
        if (cid == null || cid <= 0) {
            throw AppException(
                AppError.PlaySourceUnavailable,
                "bilibili view cid missing bvid=$bvid code=${resp.code}"
            )
        }
        return cid
    }

    private suspend fun requestAudioStream(bvid: String, cid: Long): BilibiliAudioStream {
        val resp = api.playUrl(ResolverEndpoints.current(STRATEGY_ID) ?: PLAY_URL, bvid, cid)
        if (resp.code != null && resp.code != 0) {
            throw AppException(
                AppError.Network,
                "bilibili playurl code=${resp.code} ${resp.message.orEmpty()} bvid=$bvid"
            )
        }
        val audio = resp.data?.dash?.audio.orEmpty()
            .filter { !(it.baseUrl.isNullOrBlank() && it.baseUrlSnake.isNullOrBlank()) }
            .maxByOrNull { it.bandwidth ?: 0L }
        if (audio != null) return audio

        // 老视频兜底：旧版 durl 直链
        val durlUrl = resp.data?.durl?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
            ?: throw AppException(
                AppError.PlaySourceUnavailable,
                "bilibili no dash audio and no durl bvid=$bvid cid=$cid strategy=$strategyId"
            )
        return BilibiliAudioStream(id = null, bandwidth = null, baseUrl = durlUrl)
    }

    /** DASH 音频 id → 展示码率；未知 id 按 bandwidth 粗分 */
    private fun qualityOf(stream: BilibiliAudioStream): String = when (stream.id) {
        30280 -> "192k"
        30232 -> "132k"
        30216 -> "64k"
        else -> when {
            (stream.bandwidth ?: 0L) >= 150_000 -> "192k"
            (stream.bandwidth ?: 0L) >= 90_000 -> "132k"
            else -> "64k"
        }
    }

    private companion object {
        const val TAG = "BilibiliUrlResolver"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
}

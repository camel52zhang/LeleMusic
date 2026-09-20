package com.lelemusic.data.netease

import android.util.Log
import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.NETEASE_REFERER
import com.lelemusic.core.common.normalizePlayUrl
import com.lelemusic.core.common.safeCall
import com.lelemusic.data.remote.NeteaseApi
import com.lelemusic.data.source.PlayUrlResolver
import com.lelemusic.data.source.ResolverEndpoints
import com.lelemusic.model.Platform
import com.lelemusic.model.ResolvedTrack
import com.lelemusic.model.Song

/** 网易云单曲取链接口（配合 Retrofit @Url 传全路径） */
private const val SONG_URL_URL = "https://music.163.com/api/song/enhance/player/url"

/** 策略 ID（自检台展示与开关覆盖的 key；**不可更改**，否则已保存的开关覆盖失效） */
private const val STRATEGY_ID = "netease.320"

/**
 * 网易云取链：`/api/song/enhance/player/url`（api-feasibility B2 亲自验证，命中率 80%）。
 *
 * 降级逻辑：
 * 1. 先按 320000 取；
 * 2. 失败（典型是 `code:-110` 版权/付费限制）自动降 128000 重试——有时低码率有音源；
 * 3. 仍失败 → 抛 `PlaySourceUnavailable`，由上层错误卡片引导跳转官方。
 *
 * 三个必须处理的点：
 * - 返回的 URL 是 **http:// 明文**；`music.126.net` 在 network_security_config 白名单里允许明文，
 *   因此用 `normalizePlayUrl()` 保留 http（强转 https 在部分网络下连不通 CDN，见 Format.kt 注释）；
 * - 直链 **20 分钟过期**（`expi:1200`）→ 只能实时取，绝不落盘；
 * - 播放时必须带 `Referer: https://music.163.com/`，否则网易 CDN 对 ExoPlayer 的 range 请求返回异常，
 *   UI 表现为「网络异常，无法播放」（自检台用 MediaMetadataRetriever 探测时未必触发此风控）。
 */
class NeteaseUrlResolver(
    private val api: NeteaseApi
) : PlayUrlResolver {

    override val platform: Platform = NeteasePlatform
    override val strategyId: String = STRATEGY_ID
    override val priority: Int = 10
    override val defaultEnabled: Boolean = true

    init {
        ResolverEndpoints.register(STRATEGY_ID, SONG_URL_URL)
    }

    override suspend fun resolve(song: Song): ResolvedTrack = safeCall {
        try {
            requestUrl(song, BITRATE_320)
        } catch (e: AppException) {
            if (e.error != AppError.PlaySourceUnavailable) throw e
            Log.w(
                TAG,
                "netease 320 failed (${e.detail}), retry with 128, id=${song.platformSongId}"
            )
            requestUrl(song, BITRATE_128)
        }
    }

    private suspend fun requestUrl(song: Song, bitrate: Int): ResolvedTrack {
        val songId = song.platformSongId
        if (songId.isBlank()) {
            throw AppException(AppError.Parse, "netease song id is blank, uid=${song.uid}")
        }

        val resp = api.songUrl(
            ResolverEndpoints.current(STRATEGY_ID) ?: SONG_URL_URL,
            "[$songId]",
            bitrate
        )
        val item = resp.data?.firstOrNull()
        val raw = item?.url?.takeIf { it.isNotBlank() }

        if (raw == null) {
            throw AppException(
                AppError.PlaySourceUnavailable,
                "netease code=${item?.code} fee=${item?.fee} br=$bitrate id=$songId strategy=$strategyId"
            )
        }

        val url = normalizePlayUrl(raw)
        Log.i(TAG, "netease resolved: br=$bitrate level=${item.level} id=$songId")

        return ResolvedTrack(
            url = url,
            isFull = true,
            headers = mapOf("Referer" to NETEASE_REFERER),
            strategyId = strategyId,
            quality = if (bitrate >= BITRATE_320) "320k" else "128k"
        )
    }

    private companion object {
        const val TAG = "NeteaseUrlResolver"
        const val BITRATE_320 = 320_000
        const val BITRATE_128 = 128_000
    }
}

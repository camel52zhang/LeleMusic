package com.lelemusic.data.netease

import android.util.Log
import com.google.gson.JsonObject
import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.NETEASE_REFERER
import com.lelemusic.core.common.TIMEOUT_FALLBACK_MS
import com.lelemusic.core.common.normalizePlayUrl
import com.lelemusic.data.source.PlayUrlResolver
import com.lelemusic.data.source.ResolverEndpoints
import com.lelemusic.model.Platform
import com.lelemusic.model.ResolvedTrack
import com.lelemusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 策略 ID（自检台展示与开关覆盖的 key；**不可更改**，否则已保存的开关覆盖失效） */
private const val STRATEGY_ID = "netease.gd"

/**
 * GD 音乐台公共 API 兜底取链（仅网易源；2026-09-11 curl 实测可用）。
 *
 * 协议：`GET https://music-api.gdstudio.xyz/api.php?types=url&source=netease&id=<id>&br=320`
 * → `{"url":"https://...126.net/...mp3","br":320,"size":...}`；url 为空 = 该歌拿不到。
 *
 * 定位：**最终兜底**（priority 91，排在原生/eapi/LX 代理之后）。GD 是个人维护的免费聚合 API，
 * 随时可能限流失效，不可作主链；但对「原生只给 30s 试听而 GD 给全曲」的歌是有效补充。
 */
class GdNeteaseUrlResolver(
    private val client: OkHttpClient
) : PlayUrlResolver {

    /**
     * 兜底策略专用快失败客户端：GD 不可达时 3s 内交还降级链，
     * 不把链尾拖成 8s 死等（2026-09-11 自检日志实证 lx/gd 全线 E_NET timeout）。
     */
    private val fastClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(TIMEOUT_FALLBACK_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_FALLBACK_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_FALLBACK_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    override val platform: Platform = NeteasePlatform
    override val strategyId: String = STRATEGY_ID
    override val priority: Int = 91
    override val defaultEnabled: Boolean = true

    init {
        ResolverEndpoints.register(STRATEGY_ID, API_URL)
    }

    override suspend fun resolve(song: Song): ResolvedTrack = withContext(Dispatchers.IO) {
        val songId = song.platformSongId
        if (songId.isBlank()) {
            throw AppException(AppError.Parse, "gd song id blank uid=${song.uid} strategy=$strategyId")
        }

        val url = "${ResolverEndpoints.current(STRATEGY_ID) ?: API_URL}?types=url&source=netease&id=$songId&br=320"
        val request = Request.Builder().url(url).get().build()

        val body = try {
            fastClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw AppException(
                        AppError.Network,
                        "gd http ${resp.code} id=$songId strategy=$strategyId"
                    )
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: AppException) {
            throw e
        } catch (t: Throwable) {
            throw AppException(AppError.Network, "gd call failed: ${t.message}", t)
        }

        val realUrl = try {
            val json = JSON_PARSER.fromJson(body.trim(), JsonObject::class.java)
            json?.get("url")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            null
        } ?: throw AppException(
            AppError.PlaySourceUnavailable,
            "gd url empty id=$songId body=${body.take(120)} strategy=$strategyId"
        )

        Log.i(TAG, "gd resolved id=$songId")
        ResolvedTrack(
            url = normalizePlayUrl(realUrl),
            isFull = true,
            headers = mapOf("Referer" to NETEASE_REFERER),
            strategyId = strategyId,
            quality = "320k"
        )
    }

    private companion object {
        const val TAG = "GdNeteaseUrlResolver"
        const val API_URL = "https://music-api.gdstudio.xyz/api.php"
        val JSON_PARSER = com.google.gson.Gson()
    }
}

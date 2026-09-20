package com.lelemusic.data.plugin

import android.util.Log
import com.google.gson.Gson
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

/**
 * LX-Music 代理取链器（REST 代理桥接，**无需 JS 引擎**）。
 *
 * ## 对接协议（源自 `render_api.js`，已用 curl 实测返回真实可播直链）
 * ```
 * GET {baseUrl}/url/{source}/{songmid}/{quality}
 * Header: X-Request-Key: {apiKey}
 * -> { "code": 0, "msg": "...", "url": "真实直链" }
 * ```
 * - `source` ∈ {kw, kg, tx, wy, mg}：本解析器只挂 `netease=wy` / `kugou=kg` 两条；
 * - `quality` 固定取 `320k`；
 * - `code == 0` 且 `url` 非空 → 成功，否则抛 [AppError.PlaySourceUnavailable]，
 *   交由 [com.lelemusic.repo.PlayUrlResolveUseCase] 降级链继续尝试下一个策略。
 *
 * ## 为什么不需要 JS 引擎
 * 调研结论（见前序记录）：
 * 1. api.txt 里的 LX 源本质是**纯 REST 代理**——`sixyin`/`ikun` 等脚本只有 `musicUrl`
 *    能力、没有 `search`，所谓「音源」就是上面这层 `GET /url/...` 转发；
 * 2. Maven Central 上的 QuickJS 绑定（cashapp/taoweiji/zipline）evaluate 后都**不排空
 *    Promise 微任务**（`await` 必卡死），本机无 NDK 也无法自编译带 job 泵的版本。
 *
 * 因此直接调 REST 即可绕开脚本执行，在原生 Netease/Kugou 取链失败时作为降级策略补上可播直链。
 */
class LxProxyResolver(
    override val platform: Platform,
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val source: String,
    override val strategyId: String,
    override val priority: Int,
    override val defaultEnabled: Boolean
) : PlayUrlResolver {

    /**
     * 兜底策略专用快失败客户端：远端公益代理不可达时（典型 E_NET timeout），
     * 3s 内交还降级链，避免把整条链尾拖成 8s×N 的死等（2026-09-11 自检日志实证）。
     */
    private val fastClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(TIMEOUT_FALLBACK_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_FALLBACK_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_FALLBACK_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    init {
        // 自检台「编辑音源地址」：LX 代理的 baseUrl 就是可换的音源地址（自建反代场景）
        ResolverEndpoints.register(strategyId, baseUrl)
    }

    override suspend fun resolve(song: Song): ResolvedTrack = withContext(Dispatchers.IO) {
        val songmid = song.platformSongId
        if (songmid.isBlank()) {
            throw AppException(
                AppError.Parse,
                "lx proxy songmid blank uid=${song.uid} strategy=$strategyId"
            )
        }

        val requestUrl = buildRequestUrl(songmid)
        val request = Request.Builder()
            .url(requestUrl)
            .get()
            .header("X-Request-Key", apiKey)
            .build()

        val body = try {
            fastClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw AppException(
                        AppError.Network,
                        "lx proxy http ${resp.code} for $requestUrl strategy=$strategyId"
                    )
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: AppException) {
            throw e
        } catch (t: Throwable) {
            throw AppException(
                AppError.Network,
                "lx proxy call failed: ${t.message} strategy=$strategyId",
                t
            )
        }

        val realUrl = extractUrl(body)
            ?: throw AppException(
                AppError.PlaySourceUnavailable,
                "lx proxy no url in body=${body.take(200)} songmid=$songmid " +
                    "source=$source strategy=$strategyId"
            )

        Log.i(TAG, "lx proxy resolved: source=$source songmid=$songmid -> $realUrl")
        // 网易云 CDN（music.126.net）的直链必须带 Referer，否则 range 请求异常（见 NeteaseUrlResolver）；
        // 代理转发来的 126.net 直链同样需要，这里按 host 兜底补上。
        val host = runCatching { android.net.Uri.parse(realUrl).host }.getOrNull().orEmpty()
        val headers = if (host == "music.126.net" || host.endsWith(".music.126.net")) {
            mapOf("Referer" to NETEASE_REFERER)
        } else {
            emptyMap()
        }
        ResolvedTrack(
            url = normalizePlayUrl(realUrl),
            isFull = true,
            headers = headers,
            strategyId = strategyId,
            quality = "320k"
        )
    }

    /** `{baseUrl}/url/{source}/{songmid}/320k`（baseUrl 去掉尾斜杠；用户覆盖优先） */
    private fun buildRequestUrl(songmid: String): String {
        val base = (ResolverEndpoints.current(strategyId) ?: baseUrl).trimEnd('/')
        return "$base/url/$source/$songmid/320k"
    }

    /**
     * 从响应体里尽量抽出真实直链。兼容三种形态：
     * 1. 标准 JSON `{"code":0,"url":"..."}`；
     * 2. 被引号包裹的裸字符串 `""https://..."`；
     * 3. 纯文本裸 URL（部分代理直接返回 http(s) 地址，无 JSON 外壳）。
     */
    private fun extractUrl(body: String): String? {
        val trimmed = body.trim().trim('"').trim()
        if (trimmed.isEmpty()) return null

        // 纯文本裸 URL（不含空白字符才视为裸 URL，避免把整段 JSON 误判）
        if ((trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)) &&
            trimmed.indexOfAny(charArrayOf(' ', '\n', '\r', '\t')) < 0
        ) {
            return trimmed
        }

        // JSON 解析：`code==0` 且 `url` 非空才算成功
        return try {
            val json = JSON_PARSER.fromJson(trimmed, JsonObject::class.java)
                ?: return null
            if (json.isJsonNull) return null
            val code = json.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: -1
            val url = json.get("url")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.takeIf { it.isNotBlank() }
            if (code == 0 && url != null) url else null
        } catch (t: Throwable) {
            null
        }
    }

    private companion object {
        const val TAG = "LxProxyResolver"
        val JSON_PARSER = Gson()
    }
}

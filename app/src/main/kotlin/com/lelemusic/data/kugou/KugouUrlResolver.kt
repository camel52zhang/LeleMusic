package com.lelemusic.data.kugou

import android.util.Log
import com.google.gson.JsonElement
import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.FULL_TRACK_RATIO
import com.lelemusic.core.common.normalizePlayUrl
import com.lelemusic.core.common.safeCall
import com.lelemusic.data.remote.KugouApi
import com.lelemusic.data.source.PlayUrlResolver
import com.lelemusic.data.source.ResolverEndpoints
import com.lelemusic.model.Platform
import com.lelemusic.model.ResolvedTrack
import com.lelemusic.model.Song

/** 酷狗取链接口（配合 Retrofit @Url 传全路径） */
private const val PLAYINFO_URL = "https://m.kugou.com/app/i/getSongInfo.php"

/** 酷狗 `album_img` 的 `{size}` 占位符替换值（不替换会 404） */
private const val ALBUM_SIZE = 150

/** 策略 ID（自检台展示与开关覆盖的 key；**不可更改**，否则已保存的开关覆盖失效） */
private const val STRATEGY_ID = "kugou.playinfo"

/**
 * 酷狗取链：`getSongInfo.php?cmd=playInfo`（api-feasibility B3.2 亲自验证，全曲 128kbps）。
 *
 * 三条依据（用来判断「是不是全曲」）：
 * 1. `url` 路径含 `/yp/full/` → full 表示完整全曲（试听链接是 `/yp/cut/`）；
 * 2. `timeLength` 与榜单声明时长一致 → 实测 247s == Duration 247s；
 * 3. `privilege:0` / `pay_type:0` → 无版权限制。
 *
 * 兜底：主 `url` 为空时取 `backup_url[0]`（备用域名）。
 *
 * ⚠️ 已废弃、**不要**使用 `wwwapi.kugou.com/yy/index.php?r=play/getdata`（err_code 20010/30020）。
 */
class KugouUrlResolver(
    private val api: KugouApi
) : PlayUrlResolver {

    override val platform: Platform = KugouPlatform
    override val strategyId: String = STRATEGY_ID
    override val priority: Int = 10
    override val defaultEnabled: Boolean = true

    init {
        // 自检台「编辑音源地址」：登记默认地址（请求时经 ResolverEndpoints.current 实时读）
        ResolverEndpoints.register(STRATEGY_ID, PLAYINFO_URL)
    }

    override suspend fun resolve(song: Song): ResolvedTrack = safeCall {
        val hash = song.platformSongId
        if (hash.isBlank()) {
            throw AppException(AppError.Parse, "kugou hash is blank, uid=${song.uid}")
        }

        // ⚠️ 必须用具名实参：接口签名是 playInfo(url, cmd = "playInfo", hash)，
        // 直接写 playInfo(URL, hash) 会把 hash 当成 cmd 传进去。
        val resp = api.playInfo(
            url = ResolverEndpoints.current(STRATEGY_ID) ?: PLAYINFO_URL,
            hash = hash
        )

        val status = resp.status
        if (status != null && status != 1) {
            // 把 `resp.error`（人话原因，如"需要付费"/"版权限制"）带进 message，
            // 否则上层 use case 拼出来的文案只有 status/errcode 这种数字，用户看不出是付费还是别的。
            val reason = resp.error?.takeIf { it.isNotBlank() } ?: "no audio"
            throw AppException(
                AppError.PlaySourceUnavailable,
                "kugou status=$status errcode=${resp.errcode} reason=$reason hash=$hash strategy=$strategyId"
            )
        }

        // 主 URL 为空 → 退到 backup_url 第一项。
        // backup_url 可能是数组（正常）也可能是对象（酷狗部分歌曲返回），见 DTO 注释，
        // 这里用 firstBackupUrl 兼容两种形态，避免在反序列化阶段就崩成 E_PARSE。
        val rawUrl = resp.url?.takeIf { it.isNotBlank() }
            ?: firstBackupUrl(resp.backup_url)?.takeIf { it.isNotBlank() }
            ?: throw AppException(
                AppError.PlaySourceUnavailable,
                "kugou url and backup_url both empty, status=$status errcode=${resp.errcode} " +
                    "reason=${resp.error?.orEmpty() ?: "no audio"} " +
                    "payType=${resp.pay_type} privilege=${resp.privilege} hash=$hash strategy=$strategyId"
            )

        val url = normalizePlayUrl(rawUrl)
        val isFull = decideIsFull(url, resp.timeLength, song.durationMs)

        Log.i(
            TAG,
            "kugou resolved: isFull=$isFull timeLength=${resp.timeLength}s " +
                "bitRate=${resp.bitRate} hash=$hash"
        )

        ResolvedTrack(
            url = url,
            isFull = isFull,
            headers = emptyMap(),
            strategyId = strategyId,
            quality = "${resp.bitRate ?: 0}k",
            coverUrl = buildCoverUrl(resp.album_img)
        )
    }

    /**
     * 从 `backup_url` 字段安全提取第一个非空字符串地址。
     *
     * 酷狗有时把 `backup_url` 返回成**对象**（`{}` 或带 errcode 的对象）而非数组，
     * 直接按 `List<String>` 解析会抛 Gson 异常。这里用 [JsonElement] 兼容两种形态：
     * - 数组 → 取第一个非空字符串元素；
     * - 对象 / 空 / null → 返回 null，由上层回落到「主 url 缺失」的清晰报错。
     */
    private fun firstBackupUrl(elm: JsonElement?): String? {
        if (elm == null || elm.isJsonNull) return null
        if (elm.isJsonArray) {
            for (item in elm.asJsonArray) {
                if (item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                    val s = item.asString
                    if (s.isNotBlank()) return s
                }
            }
        }
        return null
    }

    /**
     * 判定是否全曲。
     *
     * 优先用 `timeLength >= 声明时长 * [FULL_TRACK_RATIO]`；
     * 但酷狗榜单接口**不保证返回 duration**，声明时长为 0 时该公式会恒判 false
     * （那样会把所有歌误标成试听）——此时退化为路径特征判断：含 `/yp/full/` 即视为全曲。
     */
    fun decideIsFull(url: String, timeLengthSec: Int?, declareMs: Long): Boolean {
        val declareSec = declareMs / 1_000L
        if (timeLengthSec != null && timeLengthSec > 0 && declareSec > 0) {
            return timeLengthSec >= (declareSec * FULL_TRACK_RATIO).toLong()
        }
        return url.contains("/yp/full/", ignoreCase = true)
    }

    /**
     * 酷狗 `album_img` 形如 `http://imge.kugou.com/stdmusic/{size}/20250125/....jpg`。
     *
     * 两件事必须做：
     * 1. `{size}` 占位符替换成具体数字（150），否则图片 404；
     * 2. `http://` → `https://`（Android 9+ 禁明文）。
     */
    fun buildCoverUrl(albumImg: String?): String? {
        val raw = albumImg?.takeIf { it.isNotBlank() } ?: return null
        val sized = raw.replace("{size}", ALBUM_SIZE.toString())
        return normalizePlayUrl(sized)
    }

    private companion object {
        const val TAG = "KugouUrlResolver"
    }
}

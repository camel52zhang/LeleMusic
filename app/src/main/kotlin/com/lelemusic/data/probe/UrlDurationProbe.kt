package com.lelemusic.data.probe

import android.media.MediaMetadataRetriever
import com.lelemusic.core.common.FULL_TRACK_RATIO
import java.util.concurrent.ConcurrentHashMap

/**
 * 直链真实时长探测器。
 *
 * 存在意义：区分「直链是全曲还是试听片段」不能靠猜——不同平台的直链可能被 CDN 截断成
 * 试听（历史教训：QQ C100 曾被怀疑是 30 秒试听）。这里用 `MediaMetadataRetriever`
 * 读真实时长，与榜单声明时长比对，**用数据代替猜测**（架构文档 D4 / OQ-7）。
 *
 * ⚠️ 必须在 [com.lelemusic.core.common.AppDispatchers.IO] 上调用——它是阻塞 IO，
 * 且没有内置超时（`withTimeout` 对阻塞调用无效，真正的兜底是 OkHttp 的 timeout）。
 */
object UrlDurationProbe {

    /**
     * 读取音频真实时长。
     *
     * @param url     必须是 `https://` 开头的绝对地址
     * @param headers 附加请求头（如网易云直链需要 Referer，否则 CDN 拒答）
     * @return 真实时长（毫秒）；探测失败返回 null
     */
    fun probeDurationMs(url: String, headers: Map<String, String>): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, headers)
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull()

    /**
     * 实测 ≥ 声明 × [FULL_TRACK_RATIO] 判为全曲。
     *
     * **realMs 为 null 时保守判 false** —— 宁可标「试听」，不可谎报全曲。
     */
    fun isFull(realMs: Long?, declareMs: Long): Boolean {
        if (realMs == null) return false
        if (declareMs <= 0L) return false
        return realMs >= (declareMs * FULL_TRACK_RATIO).toLong()
    }

    /** 实测秒数 vs 声明秒数，供自检台 UI 直接展示 */
    fun describe(realMs: Long?, declareMs: Long): String {
        val realSec = (realMs ?: 0L) / 1000L
        val declareSec = declareMs / 1000L
        return "${realSec}s / ${declareSec}s"
    }
}

/**
 * 探测结果缓存。
 *
 * 同一 URL 只探一次：`MediaMetadataRetriever` 会真实下载一段音频，
 * 重复探测既慢又容易被风控（架构文档 §8.2 低风险项）。
 *
 * 线程安全：会被 ExoPlayer 加载线程与自检台协程并发调用。
 *
 * 注：本类与 [UrlDurationProbe] 同文件，是刻意的文件数控制（架构文档 §2 清单未单列此文件）。
 */
class ProbeCache(private val maxSize: Int = DEFAULT_MAX_SIZE) {

    private val map = ConcurrentHashMap<String, Long>()

    /**
     * 命中缓存直接返回，未命中执行 [block] 并写入缓存。
     *
     * @return 时长（毫秒）；[block] 返回 null 时不缓存、直接返回 null
     */
    fun getOrProbe(url: String, block: () -> Long?): Long? {
        val cached = map[url]
        if (cached != null) return cached
        val probed = block() ?: return null
        if (map.size >= maxSize) map.clear() // 简单粗暴但可预测的淘汰策略
        map[url] = probed
        return probed
    }

    fun clear() = map.clear()

    fun size(): Int = map.size

    private companion object {
        const val DEFAULT_MAX_SIZE = 200
    }
}

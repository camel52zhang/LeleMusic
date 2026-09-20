package com.lelemusic.model

/**
 * 可播性三态（PRD 第六章：失败与降级是一等公民）。
 *
 * - [UNKNOWN]     尚未探测（默认态，UI 正常渲染不灰显）
 * - [AVAILABLE]   可播全曲
 * - [TRIAL_ONLY]  只能播试听片段（网易付费墙预判、酷狗高潮片段等）
 * - [UNAVAILABLE] 拿不到任何音源（网易 `fee=1/4` 预判灰显）
 */
enum class PlayableStatus {
    UNKNOWN,
    AVAILABLE,
    TRIAL_ONLY,
    UNAVAILABLE
}

/**
 * 取链成功的结果。
 *
 * @property url              **必须**是 `https://` 开头（经 `normalizeToHttps` 处理）
 * @property isFull           true = 全曲；false = 试听片段
 * @property headers          交给 ExoPlayer / OkHttp 的请求头（如网易云的 Referer）
 * @property strategyId       产出它的策略 ID，自检台展示用
 * @property quality          可读码率描述：`128k` / `320k` / `flac` / `probe:full` / `trial`
 * @property actualDurationMs `MediaMetadataRetriever` 实测时长（自检台判定 C100 是否全曲的依据）
 * @property coverUrl         取链时的专辑图（酷狗 playInfo `album_img`）；Song 无封面时由
 *                            PlayerViewModel 从 `ResolvedTrackStore` 回填到 UI
 */
data class ResolvedTrack(
    val url: String,
    val isFull: Boolean,
    val headers: Map<String, String> = emptyMap(),
    val strategyId: String,
    val quality: String? = null,
    val actualDurationMs: Long? = null,
    val coverUrl: String? = null,
    /**
     * 解析成功的时刻（毫秒），用于 `ResolveDataSpecResolver` 的 5 分钟 TTL 缓存。
     *
     * **默认值 0L 表示「无时间戳」**——保持向后兼容（lab / 旧调用方不解引用此字段）；
     * 真正写入缓存的路径都会在 [com.lelemusic.player.ResolveDataSpecResolver] 里
     * `.copy(resolvedAt = System.currentTimeMillis())` 后再落盘。
     */
    val resolvedAt: Long = 0L
)

/**
 * 单个策略的失败记录。
 *
 * 降级链内部吞掉中间策略的异常、逐条记录，
 * 只有全部失败时才向外抛 `AppException(PlaySourceUnavailable)`，
 * 并把这些 errorCode 拼进 detail 供自检台与日志回溯。
 */
data class ResolveFailure(
    val strategyId: String,
    val errorCode: String,
    val message: String,
    val elapsedMs: Long = 0L
)

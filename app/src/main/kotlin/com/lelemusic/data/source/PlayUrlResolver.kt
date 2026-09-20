package com.lelemusic.data.source

import com.lelemusic.core.common.errorCodeOf
import com.lelemusic.core.data.AppSettings
import com.lelemusic.model.Platform
import com.lelemusic.model.ResolvedTrack
import com.lelemusic.model.Song

/**
 * 取链策略抽象。
 *
 * 同一平台内按 [priority] 升序形成**责任链**：前一个失败自动降级到下一个
 * （编排在 `PlayUrlResolveUseCase`，本接口不感知链的存在）。
 */
interface PlayUrlResolver {

    val platform: Platform

    /** 策略标识，自检台展示与开关覆盖的 key，如 `netease.320` / `kugou.playinfo` */
    val strategyId: String

    /** 数字越小越先尝试 */
    val priority: Int

    /** 默认是否启用；可被 `AppSettings.resolverOverride` 覆盖 */
    val defaultEnabled: Boolean

    /**
     * 解析直链。
     *
     * @throws com.lelemusic.core.common.AppException 失败时抛 `AppError.PlaySourceUnavailable`
     */
    suspend fun resolve(song: Song): ResolvedTrack
}

/**
 * 策略注册表。
 *
 * 唯一职责：按平台/ID 检索策略，并持有「启用状态」的判定逻辑
 * （`resolverOverride` 优先，回落 `defaultEnabled`）。
 */
class ResolverRegistry(
    private val all: List<PlayUrlResolver>,
    private val settings: AppSettings
) {

    /** 该平台全部策略，按 priority 升序（即降级链顺序） */
    fun forPlatform(platform: Platform): List<PlayUrlResolver> =
        all.filter { it.platform == platform }.sortedBy { it.priority }

    /** 该平台当前**启用**的策略，按 priority 升序 */
    fun enabledFor(platform: Platform): List<PlayUrlResolver> =
        forPlatform(platform).filter { isEnabled(it) }

    /** 按 strategyId 精确查找；未命中返回 null（自检台需处理 null） */
    fun byId(strategyId: String): PlayUrlResolver? =
        all.firstOrNull { it.strategyId == strategyId }

    /** 所有策略（自检台逐策略遍历用） */
    fun all(): List<PlayUrlResolver> = all

    /** 单个策略当前是否启用：设置覆盖 > 默认开关 */
    fun isEnabled(resolver: PlayUrlResolver): Boolean =
        settings.resolverOverride[resolver.strategyId] ?: resolver.defaultEnabled

    /** 自检台开关：立即写入 SharedPreferences，下次 `enabledFor` 即生效 */
    fun toggle(strategyId: String, enabled: Boolean) {
        settings.setResolverOverride(strategyId, enabled)
    }

    /** 清除覆盖，回落 `defaultEnabled` */
    fun reset(strategyId: String) {
        settings.clearResolverOverride(strategyId)
    }

    /** 把 Throwable 转成可展示的错误码字符串（自检台表格用） */
    fun codeOf(throwable: Throwable): String = errorCodeOf(throwable)
}

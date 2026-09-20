package com.lelemusic.data.source

import com.lelemusic.core.data.AppSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * 音源接口地址注册表（2026-09-12 自检台「编辑音源地址」功能）。
 *
 * 三方协作：
 * 1. **注册默认**：各 resolver 在自己的 `init` 里调 [register]，把代码内置的
 *    接口地址登记进来（key = strategyId）——策略列表构建时全部完成；
 * 2. **回灌覆盖**：[AppGraph] 构建 `resolvers` 后调 [load]，把 SharedPreferences 里
 *    用户自定义过的地址灌进内存（overrides）；
 * 3. **请求时实时读**：resolver 发请求前调 [current]——自定义地址优先，回落默认。
 *    因此改完地址**下一次请求立即生效**，无需重启。
 *
 * 用户场景：原接口被墙 / 挂了 / 想换自建反代，直接在自检台改地址，不用重新装包。
 */
object ResolverEndpoints {

    private val defaults = ConcurrentHashMap<String, String>()
    private val overrides = ConcurrentHashMap<String, String>()

    /** resolver `init` 时登记代码内置默认地址（重复注册无害，后者覆盖） */
    fun register(strategyId: String, defaultUrl: String) {
        if (defaultUrl.isNotBlank()) defaults[strategyId] = defaultUrl
    }

    /** AppGraph 构建完策略列表后回灌一次用户覆盖（重复调用安全，以后写入为准） */
    fun load(settings: AppSettings) {
        for ((key, value) in settings.endpointOverride) {
            overrides[key] = value
        }
    }

    /** 该策略的默认地址；未注册（如非取链类策略）返回 null */
    fun defaultOf(strategyId: String): String? = defaults[strategyId]

    /** 该策略当前应使用的地址：用户覆盖 > 默认 */
    fun current(strategyId: String): String? = overrides[strategyId] ?: defaults[strategyId]

    /** 是否被用户自定义过（自检台行内提示用） */
    fun isOverridden(strategyId: String): Boolean = overrides.containsKey(strategyId)

    /**
     * 保存自定义地址：落盘 + 推内存。调用方负责校验 [url] 合法性（http/https 开头）。
     */
    fun setOverride(settings: AppSettings, strategyId: String, url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            clearOverride(settings, strategyId)
            return
        }
        settings.setEndpointOverride(strategyId, trimmed)
        overrides[strategyId] = trimmed
    }

    /** 清除自定义，回落默认（落盘 + 推内存） */
    fun clearOverride(settings: AppSettings, strategyId: String) {
        settings.clearEndpointOverride(strategyId)
        overrides.remove(strategyId)
    }
}

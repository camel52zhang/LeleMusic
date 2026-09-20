package com.lelemusic.core.data

import android.content.Context
import android.content.SharedPreferences
import com.lelemusic.model.PlaybackMode

/**
 * SharedPreferences 封装（MVP 不做 Room，理由见架构文档 §1.4）。
 *
 * 线程安全说明：
 * - 读（`SharedPreferences` 已做内存缓存）与 `apply()`（异步落盘）都可在任意线程调用；
 * - 但按 §7.6 约定，仍在 [com.lelemusic.core.common.AppDispatchers.IO] 上访问为佳。
 *
 * 运行期开关改完立即生效——[com.lelemusic.repo.PlayUrlResolveUseCase] 每次调用时实时读本类。
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // -----------------------------------------------------------------------
    // 播放模式
    // -----------------------------------------------------------------------

    var playbackMode: PlaybackMode
        get() {
            val raw = prefs.getString(KEY_PLAYBACK_MODE, null)
            return parsePlaybackMode(raw)
        }
        set(value) = prefs.edit().putString(KEY_PLAYBACK_MODE, value.name).apply()

    // -----------------------------------------------------------------------
    // 显示（平板 / 车机大屏）
    // -----------------------------------------------------------------------

    /**
     * 大屏（窗口 ≥600dp）内容是否铺满到边。
     * true（默认，2026-09-12 用户定）= 平板/车机横屏直接铺满到边；
     * false = 限宽居中。UI 开关在自检台「显示」区，运行期改完立即生效（DisplayPrefs 推流）。
     */
    var fillWideScreen: Boolean
        get() = prefs.getBoolean(KEY_FILL_WIDE_SCREEN, true)
        set(value) = prefs.edit().putBoolean(KEY_FILL_WIDE_SCREEN, value).apply()

    /**
     * 启动软件是否自动续播上次队列（2026-09-12 用户需求）。
     * true（默认）= 启动即接着上次进度自动播放；false = 恢复队列但停在暂停态，等用户手动点播放。
     * 开关在自检台「显示」区，改完下次启动生效。
     */
    var autoPlayOnLaunch: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PLAY_ON_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PLAY_ON_LAUNCH, value).apply()

    // -----------------------------------------------------------------------
    // 单个 resolver 的启用覆盖（自检台用）
    // -----------------------------------------------------------------------

    /**
     * 以 `strategyId → enabled` 形式返回所有覆盖项。
     *
     * 未出现在 map 中的策略回落到其 `PlayUrlResolver.defaultEnabled`。
     */
    val resolverOverride: Map<String, Boolean>
        get() {
            val result = LinkedHashMap<String, Boolean>()
            for ((key, value) in prefs.all) {
                if (key.startsWith(KEY_RESOLVER_PREFIX) && value is Boolean) {
                    result[key.removePrefix(KEY_RESOLVER_PREFIX)] = value
                }
            }
            return result
        }

    /** 覆盖某个策略的启用状态（立即生效） */
    fun setResolverOverride(strategyId: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESOLVER_PREFIX + strategyId, enabled).apply()
    }

    /** 清除某个策略的覆盖，回落到 `defaultEnabled` */
    fun clearResolverOverride(strategyId: String) {
        prefs.edit().remove(KEY_RESOLVER_PREFIX + strategyId).apply()
    }

    // -----------------------------------------------------------------------
    // 音源接口地址覆盖（自检台「编辑音源地址」用）
    // -----------------------------------------------------------------------

    /**
     * 全部音源接口地址覆盖：`strategyId → 自定义接口地址`。
     *
     * 未覆盖的策略回落到各自代码里的默认地址（`ResolverEndpoints` 注册表）。
     * 用户改地址的场景：原接口被墙 / 挂了，换成自建反代或镜像。
     */
    val endpointOverride: Map<String, String>
        get() {
            val result = LinkedHashMap<String, String>()
            for ((key, value) in prefs.all) {
                if (key.startsWith(KEY_ENDPOINT_PREFIX) && value is String && value.isNotBlank()) {
                    result[key.removePrefix(KEY_ENDPOINT_PREFIX)] = value
                }
            }
            return result
        }

    /** 覆盖某个策略的接口地址（立即生效：resolver 每次请求前实时读） */
    fun setEndpointOverride(strategyId: String, url: String) {
        prefs.edit().putString(KEY_ENDPOINT_PREFIX + strategyId, url).apply()
    }

    /** 清除某个策略的地址覆盖，回落代码内置默认 */
    fun clearEndpointOverride(strategyId: String) {
        prefs.edit().remove(KEY_ENDPOINT_PREFIX + strategyId).apply()
    }

    private fun parsePlaybackMode(raw: String?): PlaybackMode {
        if (raw.isNullOrBlank()) return PlaybackMode.LIST_LOOP
        return try {
            PlaybackMode.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            PlaybackMode.LIST_LOOP
        }
    }

    private companion object {
        const val PREF_NAME = "lelemusic_settings"
        const val KEY_PLAYBACK_MODE = "playback_mode"
        const val KEY_FILL_WIDE_SCREEN = "fill_wide_screen"
        const val KEY_AUTO_PLAY_ON_LAUNCH = "auto_play_on_launch"
        const val KEY_RESOLVER_PREFIX = "resolver_override_"
        const val KEY_ENDPOINT_PREFIX = "endpoint_override_"
    }
}

package com.lelemusic.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 显示设置的反应式桥（2026-09-12 平板/车机适配）。
 *
 * [AppSettings] 是同步 SharedPreferences API，而 UI 需要「改了立即重组」的 StateFlow，
 * 这里做双向同步：读 = 启动时从 AppSettings 回灌一次；写 = 先落盘再推流。
 */
object DisplayPrefs {

    private val _fillWideScreen = MutableStateFlow(true)

    /**
     * 大屏（窗口 ≥600dp）内容是否铺满到边（平板/车机横屏）。
     * true（默认，2026-09-12 用户定）= 直接铺满到边；false = 限宽居中。
     */
    val fillWideScreen: StateFlow<Boolean> = _fillWideScreen.asStateFlow()

    private val _autoPlayOnLaunch = MutableStateFlow(true)

    /**
     * 启动软件是否自动续播（默认开，2026-09-12 用户需求）。
     * false = 恢复队列但停在暂停态。UI 开关在自检台「显示」区。
     */
    val autoPlayOnLaunch: StateFlow<Boolean> = _autoPlayOnLaunch.asStateFlow()

    /** App 启动（或 AppGraph 首次访问）时从 [AppSettings] 回灌初值 */
    fun load(settings: AppSettings) {
        _fillWideScreen.value = settings.fillWideScreen
        _autoPlayOnLaunch.value = settings.autoPlayOnLaunch
    }

    /** 开关切换：先落盘（进程被杀也不丢）再推流（UI 立即重组） */
    fun setFillWideScreen(settings: AppSettings, value: Boolean) {
        settings.fillWideScreen = value
        _fillWideScreen.value = value
    }

    /** 启动自动续播开关：只落盘（下次启动 restoreQueueSnapshot 读取），推流供 UI 即时反映 */
    fun setAutoPlayOnLaunch(settings: AppSettings, value: Boolean) {
        settings.autoPlayOnLaunch = value
        _autoPlayOnLaunch.value = value
    }
}

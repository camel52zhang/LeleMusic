package com.lelemusic.core.common

import kotlinx.coroutines.Dispatchers

/**
 * 全局协程调度器入口。
 *
 * 约定（架构文档 §7.6）：
 * - 网络 / Gson 解析 / SharedPreferences / MediaMetadataRetriever → [IO]
 * - 更新 UI 与给 StateFlow 赋值 → [Main]
 * - CPU 密集 → [Default]（MVP 基本不用）
 *
 * 统一走本 object，便于未来替换实现（如测试时注入 TestDispatcher）。
 */
object AppDispatchers {

    val IO get() = Dispatchers.IO

    val Main get() = Dispatchers.Main

    val Default get() = Dispatchers.Default
}

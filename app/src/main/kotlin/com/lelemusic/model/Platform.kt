package com.lelemusic.model

/**
 * 平台身份标识。
 *
 * **模块化设计（2026-09 起）**：不再是枚举——每个平台一个唯一实例
 * （`data/<platform>/XxxModule.kt` 里的 `object XxxPlatform`），由
 * `AppGraph.modules` 单表注册后，榜单页 / 自检台 / 策略注册表自动遍历收敛，
 * 增删平台不再触碰任何 `when(platform)` 分支。
 *
 * @property id 稳定的小写标识，参与构造 `Song.uid`（`"${platform.id}:${platformSongId}"`），
 *              **不可随意更改**，否则历史 uid 会失效
 * @property displayName 中文展示名
 * @property seedColor   品牌主题色（ARGB，可直接传给 `androidx.compose.ui.graphics.Color(...)`）
 */
interface Platform {

    val id: String

    val displayName: String

    val seedColor: Long

    /**
     * 平台官方单曲落地页（PRD：播放失败时「跳官方」兜底出口，见 `PlayerViewModel.openInPlatform`）。
     *
     * 各平台 URL 形态差异大（网易是 `#/song?id=`、酷狗是 `#hash=`），
     * 所以收进平台自身而不是用 `when(platform)` 在 UI 层拼。
     */
    fun songPageUrl(platformSongId: String): String
}

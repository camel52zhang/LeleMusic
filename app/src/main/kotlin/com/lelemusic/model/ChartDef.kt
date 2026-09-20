package com.lelemusic.model

/**
 * 榜单定义。
 *
 * 新增一个榜单 = 在所属平台的模块对象（`data/netease/NeteaseModule.kt`、
 * `data/kugou/KugouModule.kt` 的 `charts` 列表）里加一行，不需要改动任何业务代码。
 *
 * @property platform  所属平台（平台身份对象）
 * @property chartId   平台侧榜单 ID（网易歌单 id / 酷狗 `rankid`）
 * @property title     展示名（网易云榜单名不从接口取，本地硬编码，见 api-feasibility A2.1）
 * @property enabled   是否上线；`false` 为 P1 预留
 * @property isDefault 进入该平台时默认选中的榜单
 */
data class ChartDef(
    val platform: Platform,
    val chartId: String,
    val title: String,
    val enabled: Boolean = true,
    val isDefault: Boolean = false
)

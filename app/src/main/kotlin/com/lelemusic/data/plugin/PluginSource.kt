package com.lelemusic.data.plugin

/**
 * 用户自行添加的音源（插件）定义。
 *
 * **为什么用 String 常量而不是 enum**：本类要用 Gson 直接序列化进 `filesDir/plugins.json`，
 * 枚举在「老数据缺字段 / 改名」时的反序列化容错比 String 差，而这里只是展示用标签，
 * 不存在类型安全诉求，用常量更省事（与 `Song.platform` 的持久化取舍一致）。
 *
 * @param id          稳定 id：由 url 生成的 UUID 前 12 位，重复添加同一 URL 时用于去重
 * @param name        显示名（多行清单里带的名称；单 URL 时从文件名推导）
 * @param url         插件脚本 / 注册表地址
 * @param type        [TYPE_SINGLE] 单源脚本 / [TYPE_REGISTRY] 插件注册表（plugins.json）
 * @param runtime     脚本运行时契约：lx / musicfree / unknown（抓取脚本内容后判定）
 * @param version     注册表自带的版本号，可为空
 * @param enabled     是否启用（关掉不参与后续搜索，但记录保留）
 * @param bytes       上次成功抓取的脚本字节数，0 表示还没抓过
 * @param childCount  注册表解析出的插件条目数
 * @param lastError   上次抓取/展开的错误信息，成功时清空
 * @param parentId    由注册表展开出来的子源指向注册表 id；顶级源为 null
 */
data class PluginSource(
    val id: String,
    val name: String,
    val url: String,
    val type: String = TYPE_SINGLE,
    val runtime: String = RUNTIME_UNKNOWN,
    val version: String? = null,
    val enabled: Boolean = true,
    val bytes: Long = 0L,
    val childCount: Int = 0,
    val lastError: String? = null,
    val parentId: String? = null,
    val installedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_SINGLE = "single"
        const val TYPE_REGISTRY = "registry"

        const val RUNTIME_LX = "lx"
        const val RUNTIME_MUSICFREE = "musicfree"
        const val RUNTIME_UNKNOWN = "unknown"
    }
}

/** 添加结果统计（给 UI 一次性反馈「加了几个 / 跳过几个 / 几行没认出来」） */
data class AddSummary(
    val added: Int = 0,
    val skipped: Int = 0,
    val invalid: Int = 0
)

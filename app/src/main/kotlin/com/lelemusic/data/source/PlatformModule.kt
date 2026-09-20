package com.lelemusic.data.source

import android.content.Context
import com.lelemusic.model.ChartDef
import com.lelemusic.model.Platform
import retrofit2.Retrofit

/**
 * 平台插件模块抽象（模块化架构的核心接口）。
 *
 * **一个平台 = 一个 [PlatformModule] 实现**，该平台的一切知识都在模块内：
 * 身份（[platform]）、榜单（[charts]）、数据源 / 取链 / 歌词能力的构建工厂。
 * 除在 `AppGraph.modules` 注册表加一项外，**增删平台不触碰任何业务代码**——
 * 榜单页 / 自检台 / 策略注册表都通过遍历 [PlatformModule] 列表自动收敛。
 *
 * 各平台实现位于自己的包内（`data/netease/NeteaseModule.kt`、`data/kugou/KugouModule.kt`、
 * `data/local/LocalModule.kt`）。
 *
 * **三类能力全部可选**（默认空实现），平台按需覆写：
 * - 云端榜单平台（网易/酷狗）：覆写 [charts] + [rankSource]（+ [resolvers]）；
 * - 本地音乐平台：无榜单（charts/rankSource 保持默认空），只提供取链 / 歌词能力，
 *   因此**不会**出现在榜单 Tab 与自检台里。
 *
 * @see com.lelemusic.model.Platform 平台身份（Song / ChartDef / LabRow 持有的是它）
 */
interface PlatformModule {

    /** 平台身份：全工程唯一实例，作为 `Song.platform` 等字段的类型标识 */
    val platform: Platform

    /**
     * 该平台全部榜单定义（含 P1 预留）；无榜单的平台（如本地音乐）返回空。
     *
     * `enabled = true` 上线，`false` 为预留，见 [activeCharts]。
     */
    val charts: List<ChartDef>
        get() = emptyList()

    /** 已上线榜单（[charts] 中 `enabled = true` 的子集），榜单 Tab / 默认榜选择用它 */
    val activeCharts: List<ChartDef>
        get() = charts.filter { it.enabled }

    /**
     * 该平台的榜单数据源工厂；无榜单的平台返回 null（会被 AppGraph 过滤，不占榜单 Tab）。
     *
     * @param retrofit 全局共享的 Retrofit 实例（`@Url` 全路径约定，见 HttpStack），
     *                 模块在内部用 `retrofit.create(自己的 Api)` 构建，不对外暴露平台 Api 类型。
     */
    fun rankSource(retrofit: Retrofit): RankSource? = null

    /**
     * 该平台的全部取链策略（按 [com.lelemusic.data.source.PlayUrlResolver.priority]
     * 降级链编排由 `PlayUrlResolveUseCase` 负责，模块只负责给出完整策略集）。
     *
     * 本地文件平台播放走真实 uri 直放（`ResolveDataSpecResolver` 对非占位 scheme 放行），
     * 一般不需要取链策略，保持默认空即可。
     */
    fun resolvers(retrofit: Retrofit): List<PlayUrlResolver> = emptyList()

    /**
     * 该平台的歌词能力（**可选能力**，默认不提供）。
     *
     * 支持歌词的平台覆写此方法返回 [LyricSource]；不支持的平台保持默认 null，
     * 播放页歌词面板会显示「该平台暂不支持歌词」。
     *
     * @param context ApplicationContext：本地旁路字幕（LocalModule）读 content uri 需要；
     *                云端平台实现可以忽略它。
     */
    fun lyricSource(retrofit: Retrofit, context: Context): LyricSource? = null
}

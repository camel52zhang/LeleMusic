package com.lelemusic.core.di

import android.app.Application
import android.util.Log
import com.google.gson.Gson
import com.lelemusic.BuildConfig
import com.lelemusic.core.data.AppSettings
import com.lelemusic.core.data.DisplayPrefs
import com.lelemusic.core.net.HttpStack
import android.net.Uri
import com.lelemusic.data.bilibili.BilibiliModule
import com.lelemusic.data.kugou.KugouModule
import com.lelemusic.data.kugou.KugouPlatform
import com.lelemusic.data.local.LocalModule
import com.lelemusic.data.netease.GdNeteaseUrlResolver
import com.lelemusic.data.netease.NeteaseEapiResolver
import com.lelemusic.data.netease.NeteaseModule
import com.lelemusic.data.netease.NeteasePlatform
import com.lelemusic.data.plugin.LxProxyResolver
import com.lelemusic.data.plugin.PluginSourceRepository
import com.lelemusic.data.probe.UrlDurationProbe
import com.lelemusic.data.remote.NeteaseApi
import com.lelemusic.data.source.LyricSource
import com.lelemusic.data.source.PlatformModule
import com.lelemusic.data.source.PlayUrlResolver
import com.lelemusic.data.source.RankSource
import com.lelemusic.data.source.ResolverEndpoints
import com.lelemusic.data.source.ResolverRegistry
import com.lelemusic.model.Platform
import com.lelemusic.player.PlaybackModeStore
import com.lelemusic.player.ResolvedTrackStore
import com.lelemusic.player.TrackStore
import com.lelemusic.repo.ChartRepository
import com.lelemusic.repo.LibraryRepository
import com.lelemusic.repo.PlayUrlResolveUseCase
import com.lelemusic.repo.QueueMemoryStore
import com.lelemusic.repo.SourceHealthRepository
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.File

/**
 * 手工 DI 容器（ServiceLocator 模式）。
 *
 * **为什么不引入 Hilt**（架构文档 §1.4）：
 * Hilt 需要 KSP，KSP 版本必须与 Kotlin 1.9.22 精确匹配（1.9.22-1.0.17），
 * 配错一个字符就整轮返工；而本项目只有 1 个 Activity、1 个 Service、3 个 ViewModel，
 * 手工 DI 多写约 40 行，边际成本远低于「无编译环境下的版本风险」。
 *
 * 所有属性都是 `by lazy` 单例，首次访问时构造；`init` 只保存 Application 引用。
 *
 * T05 会在这里追加 `sourceHealthRepository`（启动探活）。
 */
object AppGraph {

    private lateinit var application: Application

    @Volatile
    private var initialized: Boolean = false

    /** 在 `LeLeMusicApp.onCreate` 中调用一次；重复调用安全 */
    @Synchronized
    fun init(app: Application) {
        if (initialized) return
        application = app.applicationContext as Application
        initialized = true
        Log.i(TAG, "AppGraph initialized")
    }

    private fun requireInit() {
        check(initialized) { "AppGraph.init(app) must be called before accessing dependencies" }
    }

    // -----------------------------------------------------------------------
    // 基础设施
    // -----------------------------------------------------------------------

    val okHttp: OkHttpClient by lazy {
        requireInit()
        HttpStack.okHttpClient(debug = BuildConfig.DEBUG)
    }

    val gson: Gson by lazy { Gson() }

    private val retrofit: Retrofit by lazy { HttpStack.retrofit(okHttp, gson) }

    /** 网易云接口单例（搜索页等非模块内消费者共用，避免重复 retrofit.create） */
    val neteaseApi: NeteaseApi by lazy { retrofit.create(NeteaseApi::class.java) }

    val settings: AppSettings by lazy {
        requireInit()
        AppSettings(application)
    }

    /** 显示设置桥（大屏铺满开关等）：首次访问时从 [settings] 回灌初值 */
    val displayPrefs: DisplayPrefs by lazy {
        requireInit()
        DisplayPrefs.load(settings)
        DisplayPrefs
    }

    // -----------------------------------------------------------------------
    // 平台模块注册表（模块化设计：增删平台的唯一改动点）
    // -----------------------------------------------------------------------

    /**
     * 全部已接入的平台模块。
     *
     * **增删平台 = 在这里增删一项**，其余（榜单页 / 自检台 / 策略注册表 / 播放器）全部
     * 通过遍历本表自动收敛；平台自身的榜单、策略、Api 构建都封装在各模块内
     * （见 [PlatformModule] 与 `data/netease|kugou` 下的 `XxxModule.kt`）。
     */
    val modules: List<PlatformModule> by lazy {
        requireInit()
        listOf(
            NeteaseModule,
            KugouModule,
            BilibiliModule, // 2026-09-11 端到端实测：热门榜单/取链匿名全通
            LocalModule // 无榜单模块：不占 Tab、不进自检台，只贡献播放/歌词能力
        )
    }

    /** `platform.id → Platform` 反查表（歌单持久化反序列化时按 id 还原 Song.platform） */
    val platformById: Map<String, Platform> by lazy {
        modules.associate { module -> module.platform.id to module.platform }
    }

    // -----------------------------------------------------------------------
    // 进程内内存表
    // -----------------------------------------------------------------------

    val trackStore: TrackStore by lazy { TrackStore() }

    val resolvedStore: ResolvedTrackStore by lazy { ResolvedTrackStore() }

    /**
     * 播放模式持久化单例。
     *
     * `PlaybackController` 的 `modeStore` 默认参数会各自 new 一个实例（背后是同一份
     * SharedPreferences，语义等价）；这里显式注册，是为了让 T04 的 `PlayerViewModel`
     * 与 T05 的自检台能共享同一个实例，避免多处读取时出现「刚写完还没落盘」的毛刺。
     */
    val playbackModeStore: PlaybackModeStore by lazy { PlaybackModeStore(settings) }

    // -----------------------------------------------------------------------
    // 探测与策略
    // -----------------------------------------------------------------------

    /** 单例 object，此处只是为了让依赖图显式可见 */
    val probe: UrlDurationProbe by lazy { UrlDurationProbe }

    /**
     * 全部取链策略：由各平台模块提供，按平台聚合后交给 [ResolverRegistry]，
     * 再拼上网易增强取链与 LX 代理取链。
     *
     * 网易降级链（priority 升序）：
     * `netease.eapi`(9，eapi 加密通道) → `netease.320`(10，旧直连接口)
     * → `lx.wy.onrender`(90，LX 代理) → `netease.gd`(91，GD 音乐台兜底)
     */
    val resolvers: List<PlayUrlResolver> by lazy {
        val list = modules.flatMap { module -> module.resolvers(retrofit) } +
            neteaseExtraResolvers + lxProxyResolvers
        // 各 resolver 在 init 里已把默认接口地址登记进 ResolverEndpoints，
        // 这里把用户在自检台改过的地址回灌进内存（下一次请求即生效）
        ResolverEndpoints.load(settings)
        list
    }

    /**
     * 网易增强取链（无需 JS 引擎，全部原生实现）：
     * - [NeteaseEapiResolver]：eapi 加密通道（配方源自 GitHub Suxiaoqinx/Netease_url，
     *   AES-ECB + md5，纯 Kotlin），匿名可取 exhigh(320k)，排原生之前；
     * - [GdNeteaseUrlResolver]：GD 音乐台公共 API（个人维护，随时可能失效），
     *   只作最终兜底。
     */
    private val neteaseExtraResolvers: List<PlayUrlResolver> by lazy {
        listOf(
            NeteaseEapiResolver(client = okHttp),
            GdNeteaseUrlResolver(client = okHttp)
        )
    }

    /**
     * LX-Music 代理取链策略（插件化桥接，无需 JS 引擎）。
     *
     * 两类来源：
     * 1. 内置：api.txt 中 `render_api.js` 指向的公共代理 `lxmusicapi.onrender.com`
     *    （`X-Request-Key: share-v3`），已用 curl 实测返回真实可播直链；
     * 2. 动态：用户「音源管理」里添加的 LX api-host（非 `.js` 脚本、非 `.json` 注册表、
     *    且已启用的 http(s) 地址），按 host 生成 `netease(wy)` + `kugou(kg)` 两条降级策略。
     *
     * 全部以**低优先级（90）**挂到原生策略之后：原生取链失败时才降级到代理，
     * 不抢占 `netease.320` / `kugou.playinfo`。策略会进入 [ResolverRegistry] 自检台，
     * 用户可单独开关。
     */
    private val lxProxyResolvers: List<PlayUrlResolver> by lazy {
        buildList {
            // 1) 内置公共代理（api.txt 真实源）
            addLxHost(baseUrl = LX_ONRENDER_BASE, apiKey = "share-v3", tag = "onrender")

            // 2) 动态：用户自行添加的 LX api-host
            for (src in pluginSourceRepository.sources.value) {
                if (!src.enabled) continue
                if (!isLxApiHost(src.url)) continue
                val origin = hostOrigin(src.url) ?: continue
                val key = queryKey(src.url) ?: "share-v3"
                addLxHost(baseUrl = origin, apiKey = key, tag = src.id)
            }
        }
    }

    /** 给某代理 host 生成 `netease(wy)` + `kugou(kg)` 两条取链策略 */
    private fun MutableList<PlayUrlResolver>.addLxHost(
        baseUrl: String,
        apiKey: String,
        tag: String
    ) {
        add(
            LxProxyResolver(
                platform = NeteasePlatform,
                client = okHttp,
                baseUrl = baseUrl,
                apiKey = apiKey,
                source = "wy",
                strategyId = "lx.wy.$tag",
                priority = 90,
                defaultEnabled = true
            )
        )
        add(
            LxProxyResolver(
                platform = KugouPlatform,
                client = okHttp,
                baseUrl = baseUrl,
                apiKey = apiKey,
                source = "kg",
                strategyId = "lx.kg.$tag",
                priority = 90,
                defaultEnabled = true
            )
        )
    }

    /**
     * 判断一个用户音源 URL 是否为 LX api-host（而非 `.js` 脚本 / `.json` 注册表）。
     * 命中后才会被纳入代理取链策略。
     */
    private fun isLxApiHost(url: String): Boolean =
        url.startsWith("http", ignoreCase = true) &&
            !url.endsWith(".js", ignoreCase = true) &&
            !url.endsWith(".json", ignoreCase = true)

    /** 从任意 http(s) URL 抽 origin（`scheme://authority`），去掉 path/query */
    private fun hostOrigin(url: String): String? {
        val uri = Uri.parse(url)
        val scheme = uri.scheme ?: return null
        val authority = uri.authority ?: return null
        return "$scheme://$authority"
    }

    /** 从 URL query 里取 api key（约定参数名 `key`）；缺省返回 null */
    private fun queryKey(url: String): String? {
        val v = Uri.parse(url).getQueryParameter("key") ?: return null
        return v.takeIf { it.isNotBlank() }
    }

    val resolverRegistry: ResolverRegistry by lazy {
        ResolverRegistry(all = resolvers, settings = settings)
    }

    // -----------------------------------------------------------------------
    // 榜单数据源
    // -----------------------------------------------------------------------

    /** 各平台榜单数据源（顺序即平台 Tab 顺序，由 [modules] 决定；无榜单模块返回 null 被滤除） */
    val rankSources: List<RankSource> by lazy {
        modules.mapNotNull { module -> module.rankSource(retrofit) }
    }

    /**
     * 各平台歌词源（可选能力：平台未实现时为 null → 播放页歌词面板提示「暂不支持」）。
     * 播放页按 `song.platform.id` 查找。
     */
    val lyricSources: List<LyricSource> by lazy {
        modules.mapNotNull { module -> module.lyricSource(retrofit, application) }
    }

    // -----------------------------------------------------------------------
    // 编排层
    // -----------------------------------------------------------------------

    val resolveUseCase: PlayUrlResolveUseCase by lazy {
        PlayUrlResolveUseCase(registry = resolverRegistry)
    }

    val chartRepository: ChartRepository by lazy {
        ChartRepository(sources = rankSources, trackStore = trackStore)
    }

    /**
     * 自建歌单仓库（`filesDir/playlists.json` 持久化）。
     *
     * 榜单页「快捷加歌」/ 我的歌单页 / 歌单详情页共用此单例，内存态一致。
     */
    val libraryRepository: LibraryRepository by lazy {
        requireInit()
        LibraryRepository(
            file = File(application.filesDir, "playlists.json"),
            gson = gson,
            platformById = platformById
        )
    }

    /**
     * 用户音源仓库（`filesDir/plugins.json`）。
     *
     * 与 [libraryRepository] 同样的持久化形态（Gson + tmp 文件改名），
     * 但只存「用户贴进来的源」的元数据；JS 引擎执行层在后续迭代接在
     * [PluginSourceRepository] 的 `runtime` 分派处，不改动本容器的装配方式。
     */
    val pluginSourceRepository: PluginSourceRepository by lazy {
        requireInit()
        PluginSourceRepository(
            file = File(application.filesDir, "plugins.json"),
            gson = gson,
            client = okHttp
        )
    }

    /**
     * 队列记忆仓库（`filesDir/queue.json`）：进程被杀后恢复上次的队列 + 位置 + 播放态。
     * 写快照由 `PlayerViewModel` 在切歌 / 定时 / 暂停时驱动。
     */
    val queueMemoryStore: QueueMemoryStore by lazy {
        requireInit()
        QueueMemoryStore(file = File(application.filesDir, "queue.json"), gson = gson)
    }

    /**
     * 启动探活（T05）。
     *
     * 与 [rankSources] 共用同一批数据源实例，因此探活结果与实际取榜的行为一致。
     * 由 `LeLeMusicApp.onCreate` 在后台协程里发起，**不阻塞首屏**；
     * 结果经 `SourceHealthRepository.health` 推给榜单页，用于把挂掉的平台 Tab 置灰。
     */
    val sourceHealthRepository: SourceHealthRepository by lazy {
        SourceHealthRepository(sources = rankSources)
    }

    private const val TAG = "AppGraph"

    /**
     * LX-Music 内置公共代理（来自 api.txt 的 `render_api.js` 解析结果）。
     * 协议：`GET {base}/url/{source}/{songmid}/320k` + `X-Request-Key: share-v3`。
     */
    private const val LX_ONRENDER_BASE = "https://lxmusicapi.onrender.com"
}

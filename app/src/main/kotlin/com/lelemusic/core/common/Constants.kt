package com.lelemusic.core.common

// ---------------------------------------------------------------------------
// 播放占位 URI scheme
// ---------------------------------------------------------------------------

/** MediaItem 的占位 scheme：`lelemusic://<uid>`，真正取链在 [MediaSource] 层懒解析 */
const val LELE_SCHEME = "lelemusic"

// ---------------------------------------------------------------------------
// 请求头常量
// ---------------------------------------------------------------------------

/** 全局伪装成移动端浏览器，避免被一眼识别为爬虫 */
const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/**
 * 各平台 Referer（注入策略见 [com.lelemusic.core.net.HttpStack] 的 host → Referer 映射）。
 *
 * 说明：这是**唯一的平台知识残留在 core 的地方**——HttpStack 的 RefererInterceptor 是
 * OkHttp/ExoPlayer 播放与自检探针三条请求路径共用的基础拦截器，core.net 不能反向依赖
 * data 层平台模块，故 host 映射与 Referer 值留在 core，**每接入一个平台在此加一行常量 +
 * 在 HttpStack.refererFor 加一行映射即可**（其余平台知识已全部收敛进平台模块对象）。
 */
const val NETEASE_REFERER = "https://music.163.com/"
const val KUGOU_REFERER = "https://m.kugou.com/"

// ---------------------------------------------------------------------------
// 超时与阈值
// ---------------------------------------------------------------------------

/** OkHttp 连接超时（**真正生效的超时手段**，见架构文档 坑 3） */
const val TIMEOUT_CONNECT_MS = 8_000L

/** OkHttp 读取超时（放宽到 12s：网易热歌榜响应 ~611KB，弱网下 8s 易触发瞬时超时） */
const val TIMEOUT_READ_MS = 12_000L

/** OkHttp 整调用超时 */
const val TIMEOUT_CALL_MS = 8_000L

/** 单个取链策略的协程层兜底超时（对阻塞 IO 无效，仅作兜底） */
const val TIMEOUT_RESOLVE_MS = 5_000L

/**
 * 低优先级兜底取链策略（LX 代理 / GD 音乐台）的专用超时。
 *
 * 2026-09-11 自检日志实证：网络不可达时每个兜底策略要白等满全局 8s
 * （lx.wy/lx.kg/netease.gd 三条策略 × 8s = 一首歌降级链尾白白卡 24s+），
 * 这些「远端公益代理」本就波动大，快失败换策略比死等更合理。
 */
const val TIMEOUT_FALLBACK_MS = 3_000L

/** MediaMetadataRetriever 探测的协程层兜底超时 */
const val TIMEOUT_PROBE_MS = 8_000L

/** 榜单拉取超时（与读取超时同步放宽到 12s） */
const val TIMEOUT_RANK_MS = 12_000L

/** 实测时长 ≥ 声明时长 * 0.9 → 判定为全曲（PRD：宁可标试听，不可谎报全曲） */
const val FULL_TRACK_RATIO = 0.90f

// ---------------------------------------------------------------------------
// 业务常量（平台专属 URL / 策略 ID / extras key 已下沉到各平台模块包，
// 见 data/netease|kugou 下的实现类与 XxxModule.kt）
// ---------------------------------------------------------------------------

/** 首页 Top50 */
const val CHART_LIMIT = 50

/** 自检台彩蛋：连点标题次数 */
const val LAB_TAP_COUNT = 7

package com.lelemusic.player

import android.content.Context
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.lelemusic.BuildConfig
import com.lelemusic.repo.PlayUrlResolveUseCase

/**
 * 把「懒解析」能力包进一个 [MediaSource.Factory]，供 `ExoPlayer.Builder` 直接使用
 * （架构文档 附录 A.5）。
 *
 * 数据流：
 * ```
 * ProgressiveMediaSource
 *   └─ ResolvingDataSource（同步问一次 ResolveDataSpecResolver）
 *        └─ SchemeAwareDataSourceFactory（按 scheme 分流）
 *             ├─ http/https → OkHttpDataSource（云端直链，复用取链同款 OkHttpClient）
 *             └─ 其余       → DefaultDataSource（本地 content:// / file://）
 * ```
 *
 * **为什么用 OkHttp 而不是 `DefaultHttpDataSource`**（云端部分）：
 * 取链（NeteaseApi / KugouApi）走的是 `HttpStack.okHttpClient`，自带
 * `UserAgentInterceptor` + `RefererInterceptor`（按 host 自动补 `Referer`、每次重定向都重新注入、
 * 带 cookie 支持）。之前播放用 `DefaultHttpDataSource`（底层 `HttpURLConnection`），两套栈行为不一致：
 * 自检台用 `MediaMetadataRetriever` 走系统媒体栈能播，ExoPlayer 走 `HttpURLConnection` 却报
 * `ERROR_CODE_IO_*`。统一到 OkHttp 后，播放请求与取链请求完全一致（同样的 UA / Referer / 重定向处理），
 * 网易云等 CDN 的风控判断不再因客户端差异而误杀。
 *
 * **本地音乐部分**：OkHttp 播不了 `content://`，因此上游换成 [SchemeAwareDataSourceFactory]
 * 按 scheme 分流；本地曲目下发的 MediaItem 直接带真实 uri（`Song.toMediaItem` 处理），
 * Resolver 对非 `lelemusic://` 占位 scheme 原样放行（见 [ResolveDataSpecResolver]）。
 *
 * @param context   建本地数据源用（ApplicationContext 语义，见 [SchemeAwareDataSourceFactory]）
 * @param trackStore `uid → Song`，供 Resolver 反查曲目
 * @param useCase    取链责任链入口
 * @param store      解析结果 / 失败记录内存表（UI 的「试听片段」胶囊与错误卡片读它）
 */
class ResolvingMediaSourceFactory(
    context: Context,
    trackStore: TrackStore,
    useCase: PlayUrlResolveUseCase,
    store: ResolvedTrackStore
) {

    /**
     * 上游数据源：按 scheme 在 OkHttp（云端直链）与 DefaultDataSource（本地文件）之间分流。
     * 超时、UA、`Referer` 由 OkHttpClient 的拦截器统一处理（见 `HttpStack`）；
     * 云端 `track.headers`（网易云在此带 `Referer`）作为默认请求头注入，与拦截器互补。
     */
    private val upstreamDataSourceFactory = SchemeAwareDataSourceFactory(
        context = context,
        debug = BuildConfig.DEBUG
    )

    /** 交给 `ExoPlayer.Builder` 的成品工厂 */
    val mediaSourceFactory: MediaSource.Factory =
        ProgressiveMediaSource.Factory(
            ResolvingDataSource.Factory(
                upstreamDataSourceFactory,
                ResolveDataSpecResolver(trackStore, useCase, store)
            )
        )
}

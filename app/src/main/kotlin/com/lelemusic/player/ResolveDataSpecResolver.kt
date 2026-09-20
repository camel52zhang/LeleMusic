package com.lelemusic.player

import android.net.Uri
import android.util.Log
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.lelemusic.core.common.AppDispatchers
import com.lelemusic.core.common.LELE_SCHEME
import com.lelemusic.core.common.TIMEOUT_RESOLVE_MS
import com.lelemusic.core.common.errorCodeOf
import com.lelemusic.model.ResolveFailure
import com.lelemusic.model.ResolvedTrack
import com.lelemusic.model.Song
import com.lelemusic.repo.PlayUrlResolveUseCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Media3 **懒解析** `Resolver`（架构文档 §7.8 / 附录 A.5）。
 *
 * `MediaItem` 的 uri 是占位地址 `lelemusic://<uid>`，**不含真实直链**。
 * 每次 ExoPlayer 打开数据源（首次缓冲、切歌、重试）都会回调本类，
 * 由它实时向 [PlayUrlResolveUseCase] 取链后再把真实 URL 塞回 [DataSpec]。
 *
 * **为什么必须 `runBlocking`（不是 `launch`）**
 * - `ResolvingDataSource.Resolver` 是**同步契约**：`resolveDataSpec()` 在 ExoPlayer 的
 *   加载线程被调用，返回时 URL 必须已经就绪。用 `CoroutineScope.launch` 会导致
 *   方法带着未解析的占位 URL 提前返回，播放器直接报 source error。
 * - 加载线程不是主线程，`runBlocking` 在这里是**正确且唯一可行**的做法，不会 ANR。
 *
 * **超时的真相**
 * - `withTimeout` 对**阻塞式网络 IO 无效**（协程没有可中断点），它只是协程层兜底；
 * - 真正生效的超时是 OkHttp 的 `connectTimeout` / `readTimeout` / `callTimeout`
 *   （T01 已配 8s，见 `HttpStack`）。
 *
 * **直链永不落盘**：网易直链 20 分钟过期，本类只在进程内存里停留一次请求的时间。
 */
class ResolveDataSpecResolver(
    private val trackStore: TrackStore,
    private val useCase: PlayUrlResolveUseCase,
    private val store: ResolvedTrackStore
) : ResolvingDataSource.Resolver {

    /**
     * 把占位 `lelemusic://<uid>` 换成真实直链。
     *
     * @throws IOException 占位 URI 非法 / 曲目不在 [TrackStore] / 三级降级全部失败
     */
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        // 兜底：非占位 scheme 直接放行（例如将来接入本地文件或电台流）
        val scheme = dataSpec.uri.scheme
        if (scheme == null || scheme != LELE_SCHEME) return dataSpec

        // 关键：必须用 `uri.authority`，**不要用 `uri.host`**！
        // 占位 URI 是 `lelemusic://<uid>`，而 uid 形如 `netease:12345`（`${platform.id}:${platformSongId}`）。
        // Android 的 `Uri.host` 把冒号当成 host:port 分隔符——`netease:12345` 会被解析成
        // host="netease"、port="12345"，整段 `12345` 被吞掉，
        // 然后 `trackStore.get("netease")` 必然查不到，引发
        // `IOException("song not in TrackStore: netease")` → ExoPlayer 报 2000(IO_UNSPECIFIED)
        // → UI 显示「网络异常，请检查网络连接」。这个误导被我们白排查了四轮。
        // `uri.authority` 返回完整 authority（含 port），保住整个 uid。
        val uid = dataSpec.uri.authority
        if (uid.isNullOrBlank()) {
            throw IOException("bad resolve uri (no authority): ${dataSpec.uri}")
        }

        val song: Song = trackStore.get(uid)
            ?: throw IOException("song not in TrackStore: $uid")

        // 5 分钟 TTL 缓存：ExoPlayer 在 prepare / 切歌 / 进度回 0 时会回调本类多次，
        // 之前每次都重新打上游 API（netease songInfo、kugou playInfo 等）——
        // 短时间高频调用会触发酷狗频控（errcode=1002 "您操作太频繁了，请稍后再试"）。
        // 网易直链 20 分钟过期，5 分钟缓存远在安全期内。
        // **自检台故意不走这条路径**——它要在自检台代码里直接调 resolver，不进这里，所以
        // 自检台仍能反映上游接口的当前真实状态（不被缓存掩盖风控恢复）。
        val cached = store.trackOf(uid)
        if (cached != null && cached.resolvedAt > 0L &&
            System.currentTimeMillis() - cached.resolvedAt < CACHE_TTL_MS
        ) {
            Log.i(
                TAG,
                "resolve cache hit for uid=$uid " +
                    "(${(System.currentTimeMillis() - cached.resolvedAt) / 1000}s old, " +
                    "skip upstream API)"
            )
            return dataSpec.buildUpon()
                .setUri(Uri.parse(cached.url))
                .setHttpRequestHeaders(cached.headers)
                .build()
        }

        val track: ResolvedTrack = runBlocking(AppDispatchers.IO) {
            try {
                withTimeout(TIMEOUT_RESOLVE_MS) { useCase.resolve(song) }
            } catch (throwable: Throwable) {
                // 失败：先把 ResolveFailure 写进内存表（UI 的错误卡片读它），再抛给 Media3。
                // 注意：这里捕获的是 withTimeout 已经结束、runBlocking 即将抛出的异常，
                // 不涉及「吞掉协程取消」，因此捕获 Throwable 是安全的。
                store.putFailure(
                    uid,
                    ResolveFailure(
                        strategyId = STRATEGY_CHAIN,
                        errorCode = errorCodeOf(throwable),
                        message = throwable.message.orEmpty(),
                        elapsedMs = 0L
                    )
                )
                throw IOException("resolve failed for $uid: ${errorCodeOf(throwable)}", throwable)
            }
        }

        // 写缓存：刻上 `resolvedAt` 时间戳，下次 `trackOf(uid)` 命中后即可省一次上游 API。
        store.putTrack(uid, track.copy(resolvedAt = System.currentTimeMillis()))

        return dataSpec.buildUpon()
            .setUri(Uri.parse(track.url))
            .setHttpRequestHeaders(track.headers)   // 网易云直链需要 Referer
            .build()
    }

    /**
     * 上报给播放器的 URI。
     *
     * 直接返回真实 URI：日志与错误上报里要能看到真实地址，便于 T05 自检台排查。
     */
    override fun resolveReportedUri(uri: Uri): Uri = uri

    private companion object {
        /** 责任链整体失败的伪策略 ID（自检台/错误卡片展示用） */
        const val STRATEGY_CHAIN = "chain"

        /** 解析结果 TTL（毫秒），见 [resolveDataSpec] 注释 */
        const val CACHE_TTL_MS = 5L * 60L * 1000L

        /** Logcat tag */
        const val TAG = "ResolveDataSpecResolver"
    }
}

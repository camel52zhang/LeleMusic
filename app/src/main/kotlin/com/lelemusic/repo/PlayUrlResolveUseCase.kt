package com.lelemusic.repo

import android.os.SystemClock
import android.util.Log
import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.TIMEOUT_RESOLVE_MS
import com.lelemusic.core.common.errorCodeOf
import com.lelemusic.data.source.PlayUrlResolver
import com.lelemusic.data.source.ResolverRegistry
import com.lelemusic.model.Platform
import com.lelemusic.model.ResolveFailure
import com.lelemusic.model.ResolvedTrack
import com.lelemusic.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap

/**
 * 取链**降级责任链**编排（架构文档 §3.2 / §7.3 铁律 3）。
 *
 * 行为：
 * 1. 取出该平台当前启用的策略，按 `priority` 升序依次尝试；
 * 2. 中间策略的异常**全部吞掉**并记录到 `lastFailures`，自动降级到下一个；
 * 3. 全部失败才抛 `AppException(PlaySourceUnavailable)`，
 *    且 `detail` 里拼上各策略的错误码，供自检台与日志回溯。
 *
 * 开关实时生效：`ResolverRegistry` 每次判定启用状态时都实时读 `AppSettings` 的覆盖，改完无需重启（T05 要求）。
 */
class PlayUrlResolveUseCase(
    private val registry: ResolverRegistry
) {

    private val TAG = "PlayUrlResolveUseCase"

    /** uid → 上一次解析过程中各策略的失败记录 */
    private val failures = ConcurrentHashMap<String, List<ResolveFailure>>()

    /**
     * 按责任链解析直链。
     *
     * @throws AppException `AppError.PlaySourceUnavailable` —— 三级降级全部失败
     */
    suspend fun resolve(song: Song): ResolvedTrack {
        val chain = buildChain(song.platform)
        if (chain.isEmpty()) {
            throw AppException(
                AppError.PlaySourceUnavailable,
                "no enabled resolver for platform=${song.platform.id}, uid=${song.uid}"
            )
        }

        val localFailures = ArrayList<ResolveFailure>(chain.size)

        for (resolver in chain) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val track = withTimeout(TIMEOUT_RESOLVE_MS) { resolver.resolve(song) }
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                failures[song.uid] = localFailures.toList()
                Log.i(
                    TAG,
                    "resolved uid=${song.uid} by ${resolver.strategyId} " +
                        "isFull=${track.isFull} in ${elapsed}ms"
                )
                return track
            } catch (throwable: Throwable) {
                // 外部协程被取消（如 ViewModel 销毁）：不降级，直接向上抛，保持取消语义。
                // withTimeout 抛出的是「子协程」的 CancellationException，外层仍 active，
                // 因此超时会正常落到下面的降级逻辑。
                if (throwable is CancellationException && !currentCoroutineContext().isActive) {
                    failures[song.uid] = localFailures.toList()
                    throw throwable
                }

                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val code = errorCodeOf(throwable)
                Log.w(
                    TAG,
                    "strategy ${resolver.strategyId} failed for uid=${song.uid}: " +
                        "$code ${throwable.message} (${elapsed}ms)"
                )
                localFailures.add(
                    ResolveFailure(
                        strategyId = resolver.strategyId,
                        errorCode = code,
                        message = throwable.message.orEmpty(),
                        elapsedMs = elapsed
                    )
                )
            }
        }

        failures[song.uid] = localFailures.toList()
        val detail = localFailures.joinToString(separator = " | ") { failure ->
            "${failure.strategyId}:${failure.errorCode} (${failure.message})"
        }
        throw AppException(
            AppError.PlaySourceUnavailable,
            "all strategies failed for uid=${song.uid} [$detail]"
        )
    }

    /** 上一次解析中各策略的失败明细（自检台与错误卡片用） */
    fun lastFailures(uid: String): List<ResolveFailure> = failures[uid].orEmpty()

    /** 当前生效的策略链（按平台过滤后直接按 priority 排序，由 [ResolverRegistry] 保证） */
    private fun buildChain(platform: Platform): List<PlayUrlResolver> =
        registry.enabledFor(platform)
}

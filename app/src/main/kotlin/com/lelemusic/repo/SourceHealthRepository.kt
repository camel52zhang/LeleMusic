package com.lelemusic.repo

import android.os.SystemClock
import android.util.Log
import com.lelemusic.core.common.AppDispatchers
import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.TIMEOUT_RANK_MS
import com.lelemusic.data.source.RankSource
import com.lelemusic.model.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 单个平台的榜单接口健康度。
 *
 * @property platform  所属平台
 * @property ok        true = 该平台榜单接口可用（拿到了非空榜单）
 * @property errorCode 失败时的收敛错误码（`E_TIMEOUT` / `E_NET` / `E_EMPTY` …）；成功时为空串
 * @property message   失败细节原文，只进日志与「维护中」提示，**不参与 UI 分支判断**
 * @property songCount 探活拿到的歌曲数（只用于日志，不进播放队列）
 * @property elapsedMs 本次探活耗时
 */
data class PlatformHealth(
    val platform: Platform,
    val ok: Boolean,
    val errorCode: String = "",
    val message: String = "",
    val songCount: Int = 0,
    val elapsedMs: Long = 0L
)

/**
 * 启动探活：各平台榜单接口是否还活着。
 *
 * **为什么需要它**：三个平台的榜单接口都是**非官方公开接口**，随时可能挂掉或加风控
 * （酷狗已经出现过 `Access Deny`）。与其让用户点进去看到一片空白再一头雾水，
 * 不如在启动时悄悄探一次，把挂掉的平台 Tab 置灰（架构文档 T05 要点 3 / PRD REQ-P0-06 的故障隔离精神）。
 *
 * **为什么不阻塞首屏**：探活要发 3 个网络请求，放在 `Application.onCreate` 里同步跑会拖慢冷启动。
 * 由 `LeLeMusicApp` 用独立 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 发起，
 * 结果通过 [health] 这个 StateFlow 异步推给 UI；探活没回来之前 [unhealthy] 返回空集合，
 * 此时平台 Tab 全部正常可点——**宁可漏报，不可误报禁用**。
 *
 * **失败一律降级为「不知道」**：探活本身失败（比如开机没网）不会把平台标记为不可用。
 *
 * @param sources 榜单数据源（与 [ChartRepository] 共用同一批实例）
 */
class SourceHealthRepository(
    private val sources: List<RankSource>
) {

    private val _health = MutableStateFlow<Map<Platform, PlatformHealth>>(emptyMap())

    /** 各平台最新探活结果；未探活时为空 Map */
    val health: StateFlow<Map<Platform, PlatformHealth>> = _health.asStateFlow()

    @Volatile
    private var probing: Boolean = false

    /**
     * 并发探活所有平台。
     *
     * 幂等：上一次没跑完时直接返回，避免冷启动 + 手动刷新叠成两轮请求。
     * 任何异常都在内部收敛——探活失败不该影响 App 启动。
     */
    suspend fun probeAll() {
        if (probing) {
            Log.i(TAG, "probeAll skipped: already running")
            return
        }
        probing = true
        val startedAt = SystemClock.elapsedRealtime()
        try {
            withContext(AppDispatchers.IO) {
                coroutineScope {
                    sources.forEach { source ->
                        launch { probe(source.platform) }
                    }
                }
            }
            Log.i(
                TAG,
                "probeAll done in ${SystemClock.elapsedRealtime() - startedAt}ms, " +
                    "unhealthy=${unhealthy()}"
            )
        } catch (cancelled: CancellationException) {
            // 外部协程取消必须向上传播，保持取消语义
            throw cancelled
        } catch (throwable: Throwable) {
            Log.w(TAG, "probeAll failed", throwable)
        } finally {
            probing = false
        }
    }

    /** 单平台探活结果；还没探到返回 null */
    fun healthOf(platform: Platform): PlatformHealth? = _health.value[platform]

    /**
     * 已**确认**不可用的平台（供榜单页把平台 Tab 置灰）。
     *
     * 只返回「探活跑完且明确失败」的平台：探活未完成 / 探活本身异常 → 返回空集合，
     * 绝不因为自检代码的错误把正常平台误伤成不可用。
     */
    fun unhealthy(): Set<Platform> = _health.value
        .filterValues { result -> !result.ok }
        .keys
        .toSet()

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    private suspend fun probe(platform: Platform) {
        val result = measure(platform)
        // StateFlow 线程安全（内部有锁），在 IO 线程赋值没问题
        _health.value = _health.value + (platform to result)
    }

    private suspend fun measure(platform: Platform): PlatformHealth {
        val source = sources.firstOrNull { it.platform == platform }
        if (source == null) {
            return PlatformHealth(
                platform = platform,
                ok = false,
                errorCode = AppError.Unknown.code,
                message = "no rank source registered"
            )
        }

        val active = source.charts()
        val chart = active.firstOrNull { it.isDefault } ?: active.firstOrNull()
        if (chart == null) {
            return PlatformHealth(
                platform = platform,
                ok = false,
                errorCode = AppError.EmptyData.code,
                message = "no enabled chart in platform module"
            )
        }

        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val page = withTimeout(TIMEOUT_RANK_MS) {
                source.fetchChart(chart.chartId, PROBE_LIMIT)
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt

            if (page.songs.isEmpty()) {
                Log.w(TAG, "${platform.id} unhealthy: 0 songs in ${elapsed}ms")
                PlatformHealth(
                    platform = platform,
                    ok = false,
                    errorCode = AppError.EmptyData.code,
                    message = "chart ${chart.chartId} returned 0 songs",
                    elapsedMs = elapsed
                )
            } else {
                Log.i(TAG, "${platform.id} healthy: ${page.songs.size} songs in ${elapsed}ms")
                PlatformHealth(
                    platform = platform,
                    ok = true,
                    songCount = page.songs.size,
                    elapsedMs = elapsed
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.w(TAG, "${platform.id} unhealthy: timeout in ${elapsed}ms")
            PlatformHealth(
                platform = platform,
                ok = false,
                errorCode = AppError.Timeout.code,
                message = "rank timeout after ${elapsed}ms",
                elapsedMs = elapsed
            )
        } catch (appError: AppException) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.w(TAG, "${platform.id} unhealthy: ${appError.error.code}", appError)
            PlatformHealth(
                platform = platform,
                ok = false,
                errorCode = appError.error.code,
                message = appError.message.orEmpty(),
                elapsedMs = elapsed
            )
        } catch (cancelled: CancellationException) {
            // 探活被取消（如进程退出）：不写成「不可用」
            throw cancelled
        } catch (throwable: Throwable) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.w(TAG, "${platform.id} unhealthy: unknown", throwable)
            PlatformHealth(
                platform = platform,
                ok = false,
                errorCode = AppError.Unknown.code,
                message = throwable.message.orEmpty(),
                elapsedMs = elapsed
            )
        }
    }

    private companion object {
        const val TAG = "SourceHealthRepo"

        /**
         * 探活只取前几条：目的是「确认接口活着」，不是拉全榜。
         *
         * 取 3 条是权衡——太多会拖慢启动并加大风控风险（酷狗已出现过 `Access Deny`），
         * 太少则可能把「接口通但榜单结构变了」误判成健康。
         */
        const val PROBE_LIMIT = 3
    }
}

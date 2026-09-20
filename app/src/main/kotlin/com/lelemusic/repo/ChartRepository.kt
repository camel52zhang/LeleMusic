package com.lelemusic.repo

import android.util.Log
import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.AppDispatchers
import com.lelemusic.core.common.CHART_LIMIT
import com.lelemusic.core.common.TIMEOUT_RANK_MS
import com.lelemusic.data.source.RankSource
import com.lelemusic.model.ChartDef
import com.lelemusic.model.Platform
import com.lelemusic.model.Song
import com.lelemusic.player.TrackStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * 榜单页面的 UI 状态。
 *
 * 放在 Repository 同文件，是为了避免 T02 反向依赖 T04 的 `ChartViewModel`
 * （跨任务依赖会导致本轮无法独立编译）。T04 的 ViewModel 直接复用本密封类。
 */
sealed class ChartUiState {

    /** 加载中（骨架屏） */
    object Loading : ChartUiState()

    /**
     * 加载成功。
     *
     * @property songs     按排名升序的歌曲列表
     * @property updatedAt 榜单更新时间原文，可能为空串
     */
    data class Success(val songs: List<Song>, val updatedAt: String = "") : ChartUiState()

    /**
     * 加载失败。
     *
     * UI 只认 [error] 的 6 个枚举，禁止解析 [message] 文本来决定分支（架构文档 §7.3 铁律 2）。
     */
    data class Error(val error: AppError, val message: String = "") : ChartUiState()
}

/**
 * 榜单聚合与故障隔离。
 *
 * 三个职责：
 * 1. 把 `List<RankSource>` 编排成「按平台取」的统一入口；
 * 2. **故障隔离**：某一平台挂掉只影响该平台，其他两平台照常可用；
 * 3. 榜单级内存缓存，避免频繁切换 Tab 触发风控（OQ-5，酷狗已出现 "Access Deny"）。
 */
class ChartRepository(
    private val sources: List<RankSource>,
    private val trackStore: TrackStore
) {

    private val cache = ConcurrentHashMap<String, List<Song>>()
    private val updatedAtCache = ConcurrentHashMap<String, String>()

    /**
     * 当前可用平台：**过滤掉没有任何已启用榜单的平台**。
     *
     * 云端平台（网易/酷狗/B站）的 charts 是静态非空，恒在；
     * 本地音乐的 charts 动态来自我的歌单里 origin = import / mount 的条目——
     * 没有命名过的本地歌单时「本地音乐」Tab 自动隐藏，创建后立即出现。
     */
    fun platforms(): List<Platform> =
        sources.map { it.platform }.filter { platform -> chartsOf(platform).isNotEmpty() }

    private fun sourceOf(platform: Platform): RankSource? =
        sources.firstOrNull { it.platform == platform }

    /** 该平台已启用的榜单（榜单数据来自其数据源所归属的平台模块） */
    fun chartsOf(platform: Platform): List<ChartDef> = sourceOf(platform)?.charts().orEmpty()

    /** 该平台默认榜单：优先 `isDefault`，否则取第一个已启用；都没有返回 null */
    fun defaultChartOf(platform: Platform): ChartDef? {
        val enabled = chartsOf(platform)
        if (enabled.isEmpty()) return null
        return enabled.firstOrNull { it.isDefault } ?: enabled.first()
    }

    /**
     * 加载指定平台 + 榜单。
     *
     * @param force true 时忽略内存缓存，强制走网络（下拉刷新用）
     */
    fun load(
        platform: Platform,
        chartId: String,
        force: Boolean = false
    ): Flow<ChartUiState> = flow<ChartUiState> {
        emit(ChartUiState.Loading)

        val key = cacheKey(platform, chartId)
        if (!force) {
            val cached = cache[key]
            if (!cached.isNullOrEmpty()) {
                Log.i(TAG, "load $key from memory cache (${cached.size} songs)")
                emit(ChartUiState.Success(cached, updatedAtCache[key].orEmpty()))
                return@flow
            }
        }

        val source = sourceOf(platform)
            ?: throw AppException(AppError.Unknown, "no rank source for platform=${platform.id}")

        val page = try {
            withTimeout(TIMEOUT_RANK_MS) { source.fetchChart(chartId, CHART_LIMIT) }
        } catch (e: TimeoutCancellationException) {
            throw AppException(AppError.Timeout, "rank timeout: $key", e)
        }

        registerSongs(page.songs)
        cache[key] = page.songs
        updatedAtCache[key] = page.updatedAt

        Log.i(TAG, "load $key success (${page.songs.size} songs)")
        emit(ChartUiState.Success(page.songs, page.updatedAt))
    }.catch { throwable ->
        val appError = (throwable as? AppException)?.error ?: AppError.Unknown
        Log.w(TAG, "load failed: ${appError.code} ${throwable.message}")
        emit(ChartUiState.Error(appError, throwable.message.orEmpty()))
    }.flowOn(AppDispatchers.IO)

    /**
     * 把歌曲注册进 [TrackStore]，让 Service 侧的懒解析能按 uid 反查到 Song。
     */
    fun registerSongs(songs: List<Song>) {
        trackStore.putAll(songs)
    }

    /** 清空榜单缓存（设置页「清除缓存」可用） */
    fun clearCache() {
        cache.clear()
        updatedAtCache.clear()
    }

    private fun cacheKey(platform: Platform, chartId: String): String =
        "${platform.id}:$chartId"

    private companion object {
        const val TAG = "ChartRepository"
    }
}

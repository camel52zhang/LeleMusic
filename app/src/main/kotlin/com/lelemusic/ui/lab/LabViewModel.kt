package com.lelemusic.ui.lab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lelemusic.BuildConfig
import com.lelemusic.R
import com.lelemusic.core.common.AppDispatchers
import com.lelemusic.core.common.AppException
import com.lelemusic.core.common.TIMEOUT_RANK_MS
import com.lelemusic.core.common.errorCodeOf
import com.lelemusic.core.data.AppSettings
import com.lelemusic.data.probe.PlaybackProbe
import com.lelemusic.data.probe.UrlDurationProbe
import com.lelemusic.data.source.PlayUrlResolver
import com.lelemusic.data.source.ResolverEndpoints
import com.lelemusic.data.source.ResolverRegistry
import com.lelemusic.model.Platform
import com.lelemusic.model.Song
import com.lelemusic.repo.ChartRepository
import com.lelemusic.repo.ChartUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自检结果判定。
 *
 * | 判定 | 含义 | 用户该做什么 |
 * |---|---|---|
 * | [FULL]    | 实测时长 ≥ 声明时长 × 90% → **全曲** | 不用管 |
 * | [TRIAL]   | 实测明显短于声明 → **只有试听片段** | 换策略或接受试听 |
 * | [FAILED]  | 取链本身失败 | 看错误码定位 |
 * | [UNKNOWN] | 取链成功但探测不到真实时长 | 看播放探针段定位 |
 */
enum class LabVerdict {
    FULL,
    TRIAL,
    FAILED,
    UNKNOWN
}

/**
 * 自检台表格的一行。
 *
 * 字段覆盖架构文档 §4.3 要求的全部列：
 * 平台 / 策略 / 结果 / 错误码 / 声明时长 / 实测时长 / 判定 / 耗时。
 *
 * @property sampleRank 样本歌曲在榜单里的排名（取的是各平台榜单前几名，因此它就是样本序号）
 * @property diffMs     实测 − 声明（负数 = 比声明短，即疑似试听）
 * @property playOk      播放探针结果：null=未跑（取链就失败了），true=能按 ExoPlayer 的路径拉到流，
 *                       false=取链成功但播不出来（真机「网络异常」的真正原因就藏在这里）
 * @property playError   播放探针失败时的真实异常串（`异常类名: message`），成功时为空
 * @property playBytes   播放探针实际读到的字节数
 */
data class LabRow(
    val platform: Platform,
    val strategyId: String,
    val songTitle: String,
    val sampleRank: Int,
    val ok: Boolean,
    val verdict: LabVerdict,
    val errorCode: String,
    val message: String,
    val declareMs: Long,
    val realMs: Long?,
    val diffMs: Long?,
    val elapsedMs: Long,
    val url: String,
    val quality: String?,
    val playOk: Boolean? = null,
    val playError: String = "",
    val playBytes: Int = 0
)

/** 单个策略的开关状态（是否启用 + 是否被自检台覆盖过） */
data class ResolverToggle(
    val strategyId: String,
    val platform: Platform,
    val priority: Int,
    val enabled: Boolean,
    val overridden: Boolean
)

/** 运行期开关快照（QQ 已于 2026-09 下线，只剩各平台取链策略的启用开关） */
data class LabToggles(
    val resolvers: List<ResolverToggle> = emptyList()
)

/**
 * 自检台 UI 状态。
 *
 * @property running  是否正在跑
 * @property progress "3/12" 形式的进度文本
 * @property rows     已产出的结果行
 * @property ok       三态结论：true=本轮全部通过 / false=有失败 / null=还没跑
 * @property headline 顶部大字结论（各平台判定汇总）
 */
data class LabUiState(
    val running: Boolean = false,
    val progress: String = "",
    val rows: List<LabRow> = emptyList(),
    val ok: Boolean? = null,
    val headline: String = "",
    val toggles: LabToggles = LabToggles()
)

/**
 * 音源自检台 ViewModel（架构文档 §4.3 / T05）。
 *
 * **自检台刻意与 `PlayUrlResolveUseCase` 行为不同**：
 * 1. **忽略 priority 与 enable 状态**——逐个策略都跑一遍，
 *    否则用户永远看不到「关掉它之前它到底是什么错」；
 * 2. **强制探测真实时长**——resolver 没测过就自己测，
 *    不然「直链到底是不是全曲」这个问题就无从回答。
 *
 * @param appContext **必须是 ApplicationContext**（复制剪贴板 / 弹 Toast 用）
 */
class LabViewModel(
    private val appContext: Context,
    private val chartRepository: ChartRepository,
    private val registry: ResolverRegistry,
    private val probe: UrlDurationProbe,
    private val settings: AppSettings
) : ViewModel() {

    private val _uiState = MutableStateFlow(LabUiState())
    val uiState: StateFlow<LabUiState> = _uiState.asStateFlow()

    /** 自检协程句柄，用于支持中途取消 */
    private var checkJob: Job? = null

    init {
        refreshToggles()
    }

    // -----------------------------------------------------------------------
    // 开关（改完立即生效：PlayUrlResolveUseCase 每次调用时实时读 settings）
    // -----------------------------------------------------------------------

    /** 覆盖单个策略的启用状态 */
    fun setResolverEnabled(strategyId: String, enabled: Boolean) {
        registry.toggle(strategyId, enabled)
        Log.i(TAG, "resolver $strategyId enabled -> $enabled")
        refreshToggles()
    }

    /** 清除覆盖，回落到 `PlayUrlResolver.defaultEnabled` */
    fun resetResolver(strategyId: String) {
        registry.reset(strategyId)
        Log.i(TAG, "resolver $strategyId override cleared")
        refreshToggles()
    }

    // -----------------------------------------------------------------------
    // 音源接口地址（自检台「编辑音源地址」，2026-09-12）
    // -----------------------------------------------------------------------

    /** 当前生效的接口地址（用户覆盖 > 代码默认）；未注册返回 null */
    fun currentEndpoint(strategyId: String): String? = ResolverEndpoints.current(strategyId)

    /** 该策略是否被用户自定义过地址 */
    fun isEndpointOverridden(strategyId: String): Boolean =
        ResolverEndpoints.isOverridden(strategyId)

    /**
     * 保存自定义接口地址。合法 = http/https 开头；返回 false 时由 UI 提示。
     * 保存后**下一次请求立即生效**（resolver 每次请求前实时读）。
     */
    fun setEndpoint(strategyId: String, url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isNotBlank() &&
            !trimmed.startsWith("http://", true) &&
            !trimmed.startsWith("https://", true)
        ) {
            return false
        }
        ResolverEndpoints.setOverride(settings, strategyId, trimmed)
        Log.i(TAG, "resolver $strategyId endpoint -> ${if (trimmed.isBlank()) "(default)" else trimmed}")
        return true
    }

    /** 清除自定义地址，回落代码内置默认 */
    fun resetEndpoint(strategyId: String) {
        ResolverEndpoints.clearOverride(settings, strategyId)
        Log.i(TAG, "resolver $strategyId endpoint reset to default")
    }

    private fun refreshToggles() {
        val overrides = settings.resolverOverride
        // 平台排序取注册表顺序（AppGraph.modules），与榜单页 Tab 一致
        val order = chartRepository.platforms()
            .withIndex()
            .associate { (index, platform) -> platform to index }
        val toggles = registry.all()
            .sortedWith(
                compareBy<PlayUrlResolver> { order[it.platform] ?: Int.MAX_VALUE }
                    .thenBy { it.priority }
            )
            .map { resolver ->
                ResolverToggle(
                    strategyId = resolver.strategyId,
                    platform = resolver.platform,
                    priority = resolver.priority,
                    enabled = registry.isEnabled(resolver),
                    overridden = overrides.containsKey(resolver.strategyId)
                )
            }
        _uiState.value = _uiState.value.copy(
            toggles = LabToggles(
                resolvers = toggles
            )
        )
    }

    // -----------------------------------------------------------------------
    // 自检
    // -----------------------------------------------------------------------

    /** 跑一次全量自检。已在跑则忽略重复点击 */
    fun runCheck() {
        if (_uiState.value.running) return
        checkJob = viewModelScope.launch(AppDispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                running = true,
                rows = emptyList(),
                ok = null,
                headline = appContext.getString(R.string.lab_running, "…"),
                progress = ""
            )
            try {
                runCheckInternal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                Log.e(TAG, "runCheck crashed", throwable)
                _uiState.value = _uiState.value.copy(
                    running = false,
                    headline = appContext.getString(R.string.lab_crashed)
                )
            }
        }
    }

    /**
     * 中止正在进行的自检。
     *
     * 取消传播链路已就绪：runCheckInternal 里逐个探测（probeOne）是 suspend 调用，
     * Job 被取消时会在探针处抛 CancellationException，并在 probeOne / runCheckInternal /
     * runCheck 三处逐级 re-throw，因此这里只需 cancel Job + 复位 UI，
     * 不会留下「运行中」卡死、按钮永远点不动的状态。
     */
    fun cancelCheck() {
        if (!_uiState.value.running) return
        checkJob?.cancel()
        checkJob = null
        _uiState.value = _uiState.value.copy(
            running = false,
            headline = appContext.getString(R.string.lab_cancelled)
        )
    }

    private suspend fun runCheckInternal() {
        // 平台集合取注册表（AppGraph.modules），随平台增删自动收敛
        val platforms = chartRepository.platforms()

        // ---- 阶段 1：取样本（每个平台各自的默认榜单前 N 首）----
        val samples = LinkedHashMap<Platform, List<Song>>()
        for (platform in platforms) {
            samples[platform] = sampleSongs(platform)
        }

        // ---- 阶段 2：组装「平台 × 策略 × 样本」任务矩阵 ----
        val jobs = ArrayList<Triple<Platform, PlayUrlResolver, Song>>()
        val emptyPlatforms = ArrayList<Platform>()
        for (platform in platforms) {
            val songs = samples[platform].orEmpty()
            val resolvers = registry.forPlatform(platform)
            if (songs.isEmpty() || resolvers.isEmpty()) {
                emptyPlatforms.add(platform)
                continue
            }
            for (resolver in resolvers) {
                for (song in songs) {
                    jobs.add(Triple(platform, resolver, song))
                }
            }
        }

        val total = jobs.size + emptyPlatforms.size
        val rows = ArrayList<LabRow>(total)

        // 拿不到样本的平台也要留一行，否则用户会以为自检漏跑了这个平台
        for (platform in emptyPlatforms) {
            rows.add(
                LabRow(
                    platform = platform,
                    strategyId = "-",
                    songTitle = "",
                    sampleRank = 0,
                    ok = false,
                    verdict = LabVerdict.FAILED,
                    errorCode = "E_EMPTY",
                    message = appContext.getString(R.string.lab_no_sample),
                    declareMs = 0L,
                    realMs = null,
                    diffMs = null,
                    elapsedMs = 0L,
                    url = "",
                    quality = null
                )
            )
        }

        // ---- 阶段 3：逐个跑，跑一行刷一行（用户能看到进度，不用干等）----
        for (job in jobs) {
            rows.add(probeOne(job.first, job.second, job.third))
            _uiState.value = _uiState.value.copy(
                rows = rows.toList(),
                progress = "${rows.size}/$total"
            )
            // 行间节流：酷狗等平台对短时间密集请求按 IP 限频（errcode=1002），
            // 自检 3 连击若不加间隔会把「自检太密」误报成「平台挂了」。
            // 最后一行不 sleep，纯省 0.8s。
            if (rows.size < total) delay(RATE_LIMIT_GAP_MS)
        }

        val finalRows = rows.toList()
        val allOk = finalRows.isNotEmpty() &&
            finalRows.none { row -> !row.ok } &&
            finalRows.none { row -> row.playOk == false }
        _uiState.value = _uiState.value.copy(
            running = false,
            rows = finalRows,
            ok = allOk,
            headline = buildHeadline(finalRows),
            progress = "$total/$total"
        )
        Log.i(TAG, "runCheck done: ${finalRows.size} rows, allOk=$allOk")
    }

    /**
     * 单个「歌曲 × 策略」的探测。
     *
     * 不抛异常——失败也包成一行返回，保证整张矩阵不会因为一首歌挂掉而中断。
     */
    private suspend fun probeOne(
        platform: Platform,
        resolver: PlayUrlResolver,
        song: Song
    ): LabRow {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val track = withTimeout(LAB_TIMEOUT_MS) { resolver.resolve(song) }

            // resolver 自己测过就复用，没测过就强制测一次。
            // UrlDurationProbe 是阻塞 IO、无内置超时，withTimeout 中断不了它——
            // 真正的兜底是 MediaMetadataRetriever 自身的网络超时。
            val realMs = track.actualDurationMs
                ?: probe.probeDurationMs(track.url, track.headers)

            // 播放探针：用与 ExoPlayer 完全相同的 OkHttpDataSource 真实拉流前 256KB。
            //
            // 为什么必须单独验这一遍：MediaMetadataRetriever 走系统媒体栈（stagefright），
            // 它只能证明「直链有效、能解出时长」，证明不了「ExoPlayer 能播」——
            // 真机上这两者可能不一致（网易云正是如此：自检能读时长，点播却报网络异常）。
            // 这一遍把播放器真正的网络路径跑一遍，失败时把设备真实异常写进日志，
            // 用户重跑自检贴日志就能定位，不用手动 adb logcat。
            val play = PlaybackProbe.probe(track.url, track.headers)

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val declareMs = song.durationMs
            val verdict = when {
                realMs == null -> LabVerdict.UNKNOWN
                probe.isFull(realMs, declareMs) -> LabVerdict.FULL
                else -> LabVerdict.TRIAL
            }
            Log.i(
                TAG,
                "${platform.id}/${resolver.strategyId} ok: declare=$declareMs " +
                    "real=$realMs verdict=$verdict quality=${track.quality} " +
                    "play($play) (${elapsed}ms)"
            )
            LabRow(
                platform = platform,
                strategyId = resolver.strategyId,
                songTitle = song.title,
                sampleRank = song.rank,
                ok = true,
                verdict = verdict,
                errorCode = "",
                message = "",
                declareMs = declareMs,
                realMs = realMs,
                diffMs = realMs?.minus(declareMs),
                elapsedMs = elapsed,
                url = track.url,
                quality = track.quality,
                playOk = play.ok,
                playError = play.error,
                playBytes = play.bytesRead
            )
        } catch (timeout: TimeoutCancellationException) {
            failRow(platform, resolver, song, startedAt, timeout)
        } catch (appError: AppException) {
            failRow(platform, resolver, song, startedAt, appError)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            failRow(platform, resolver, song, startedAt, throwable)
        }
    }

    private fun failRow(
        platform: Platform,
        resolver: PlayUrlResolver,
        song: Song,
        startedAt: Long,
        throwable: Throwable
    ): LabRow {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        Log.w(TAG, "${platform.id}/${resolver.strategyId} failed: ${throwable.message}")
        return LabRow(
            platform = platform,
            strategyId = resolver.strategyId,
            songTitle = song.title,
            sampleRank = song.rank,
            ok = false,
            verdict = LabVerdict.FAILED,
            errorCode = labErrorCode(throwable),
            message = throwable.message.orEmpty(),
            declareMs = song.durationMs,
            realMs = null,
            diffMs = null,
            elapsedMs = elapsed,
            url = "",
            quality = null
        )
    }

    /** 取样本：该平台默认榜单的前 [SAMPLE_COUNT] 首；拿不到返回空列表（不抛） */
    private suspend fun sampleSongs(platform: Platform): List<Song> {
        val chart = chartRepository.defaultChartOf(platform) ?: return emptyList()
        return try {
            val songs = withTimeout(TIMEOUT_RANK_MS) {
                val state = chartRepository
                    .load(platform = platform, chartId = chart.chartId, force = false)
                    .first { emitted -> emitted !is ChartUiState.Loading }
                (state as? ChartUiState.Success)?.songs.orEmpty()
            }
            songs.take(SAMPLE_COUNT)
        } catch (timeout: TimeoutCancellationException) {
            Log.w(TAG, "sample ${platform.id} timeout")
            emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Log.w(TAG, "sample ${platform.id} failed", throwable)
            emptyList()
        }
    }

    /**
     * 顶部大字结论：各平台一个 `平台:判定(X/Y 成功)` 标签。
     *
     * 判定标签取该平台最优结果：任一全曲 → 全曲；否则任一取链成功 → 试听片段；
     * 否则失败/未探测。
     *
     * 括号里的分母口径：判定标签回答「该平台最高能做到什么」，括号回答「本轮 X 首真的通了」——
     * 酷狗 3 首里 2 首付费墙、只有 1 首全曲可播，就显示 `全曲(1/3 成功)`，避免把
     * 「最优可达」误读成「全部通过」。成功 = 取链成功 且 播放探针未失败（playOk != false）。
     *
     * 播放探针的结论要顶到最显眼的位置：取链成功却播不出来，是用户唯一能感知到的失败，
     * 而它在主表里是 ok=OK（绿色），不单独提示就会被漏掉。
     */
    private fun buildHeadline(rows: List<LabRow>): String {
        val labels = rows
            .groupBy { row -> row.platform }
            .map { (platform, platformRows) ->
                val tag = when {
                    platformRows.any { it.verdict == LabVerdict.FULL } ->
                        appContext.getString(R.string.lab_verdict_full)
                    platformRows.any { it.ok } ->
                        appContext.getString(R.string.lab_verdict_trial)
                    platformRows.any { it.verdict == LabVerdict.FAILED } ->
                        appContext.getString(R.string.lab_verdict_failed)
                    else -> appContext.getString(R.string.lab_verdict_unknown)
                }
                val okCount = platformRows.count { it.ok && it.playOk != false }
                val ratio =
                    appContext.getString(R.string.lab_headline_ratio, okCount, platformRows.size)
                "${platform.displayName}:$tag$ratio"
            }
            .joinToString(separator = "  ")

        val playFails = rows.count { row -> row.playOk == false }
        return if (playFails > 0) {
            "$labels\n" + appContext.getString(R.string.lab_headline_play_fail, playFails)
        } else {
            labels
        }
    }

    // -----------------------------------------------------------------------
    // 日志导出
    // -----------------------------------------------------------------------

    /**
     * 把结果表格拼成纯文本并复制到剪贴板。
     *
     * 不用 `ClipboardManager` 的过时 `setText`，统一走 `setPrimaryClip(ClipData.newPlainText(...))`。
     */
    fun copyLog() {
        val rows = _uiState.value.rows
        val text = buildLogText(rows)
        val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            Log.w(TAG, "ClipboardManager unavailable")
            toast(appContext.getString(R.string.lab_copy_failed))
            return
        }
        runCatching {
            manager.setPrimaryClip(ClipData.newPlainText(LAB_LOG_LABEL, text))
        }.onSuccess {
            Log.i(TAG, "lab log copied (${text.length} chars)")
            toast(appContext.getString(R.string.lab_log_copied))
        }.onFailure { throwable ->
            Log.w(TAG, "copy lab log failed", throwable)
            toast(appContext.getString(R.string.lab_copy_failed))
        }
    }

    /**
     * 日志正文：TSV 风格表格 + 开关快照 + 错误明细。
     *
     * 用制表符分隔是为了让用户能直接粘进 Excel / 飞书表格，不用再手工整理。
     */
    private fun buildLogText(rows: List<LabRow>): String {
        val builder = StringBuilder()
        val ok = _uiState.value.ok

        builder.append("# LeLeMusic 音源自检日志\n")
        builder.append(buildStamp()).append('\n')
        builder.append("# generatedAt=").append(System.currentTimeMillis()).append('\n')
        if (ok == null) {
            builder.append(appContext.getString(R.string.lab_never_run)).append('\n')
        } else {
            builder.append(buildHeadline(rows)).append('\n')
        }
        appendToggles(builder)
        builder.append('\n')

        builder.append(
            listOf(
                "platform",
                "strategy",
                "song",
                "rank",
                "ok",
                "errorCode",
                "declareSec",
                "realSec",
                "diffSec",
                "verdict",
                "elapsedMs",
                "quality",
                "url"
            ).joinToString(separator = "\t")
        ).append('\n')

        for (row in rows) {
            builder.append(
                listOf(
                    row.platform.id,
                    row.strategyId,
                    row.songTitle,
                    row.sampleRank.toString(),
                    if (row.ok) "OK" else "FAIL",
                    row.errorCode,
                    seconds(row.declareMs),
                    seconds(row.realMs),
                    seconds(row.diffMs),
                    row.verdict.name.lowercase(Locale.US),
                    row.elapsedMs.toString(),
                    row.quality.orEmpty(),
                    row.url
                ).joinToString(separator = "\t")
            ).append('\n')
        }

        appendPlaybackProbe(builder, rows)

        val failures = rows.filter { row -> row.message.isNotBlank() }
        if (failures.isNotEmpty()) {
            builder.append("\n# 错误明细\n")
            for (row in failures) {
                builder.append("${row.platform.id}/${row.strategyId}: ${row.message}\n")
            }
        }
        return builder.toString()
    }

    /**
     * 构建指纹行：`# build=v1.0.0(1) builtAt=2026-09-04 21:40:12 probe=on`。
     *
     * **为什么需要它**：`versionName` / `versionCode` 是常量，区分不出「哪一次构建」。
     * 我们已经不止一次因为「贴回来的自检日志其实是旧包跑的」而白排查一轮
     * （build33/build34 那次：日志里 URL 还是 https，说明跑的是 build33，没反映 http 修复）。
     * 把构建时刻烧进日志后，一眼就能确认用户装的是不是最新包；`probe=on` 则确认该包含播放探针。
     */
    private fun buildStamp(): String {
        val builtAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(Date(BuildConfig.BUILD_TIME_MILLIS))
        return "# build=v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
            "builtAt=$builtAt probe=on"
    }

    /**
     * 播放探针明细段。
     *
     * **这是定位「取链成功但真机播不出来」的关键段落**：主表里 `ok=OK` 只代表取链与时长探测通过，
     * 播不播得出来要看这一段。两者不一致（ok=OK 但 play=FAIL）时，`err` 就是设备上的真实异常，
     * 直接指向修复方向（CDN 风控 / 明文 http 被拦 / 超时 / 证书等）。
     */
    private fun appendPlaybackProbe(builder: StringBuilder, rows: List<LabRow>) {
        val probed = rows.filter { row -> row.playOk != null }
        if (probed.isEmpty()) return
        builder.append("\n# 播放探针（用与 ExoPlayer 相同的 OkHttpDataSource 拉流前 256KB）\n")
        builder.append("# play=PASS 才代表真机能播；主表 ok=OK 但这里 play=FAIL，就是「取链成功却播不出来」\n")
        for (row in probed) {
            builder.append("platform=").append(row.platform.id)
                .append("\tstrategy=").append(row.strategyId)
                .append("\tsong=").append(row.songTitle)
                .append("\tplay=").append(if (row.playOk == true) "PASS" else "FAIL")
                .append("\tbytes=").append(row.playBytes.toString())
                .append("\terr=").append(row.playError)
                .append('\n')
        }
    }

    private fun seconds(ms: Long?): String =
        if (ms == null) "" else (ms / 1000L).toString()

    private fun appendToggles(builder: StringBuilder) {
        val toggles = _uiState.value.toggles
        for (resolver in toggles.resolvers) {
            builder.append("# resolver ")
                .append(resolver.strategyId)
                .append('=')
                .append(resolver.enabled)
            if (resolver.overridden) builder.append(" (overridden)")
            builder.append('\n')
        }
    }

    private fun toast(text: String) {
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }

    /**
     * 把异常收敛成「一眼能看出是哪个环节挂了」的错误码串。
     *
     * `AppException.error.code` 只有 6 个枚举，而上游返回的业务码（如 QQ `retcode=104009`）
     * 才是真正定位问题的钥匙，因此把 detail 里的 `retcode` 也抽出来拼上去，
     * 形如 `E_NO_SOURCE retcode=104009`。
     */
    private fun labErrorCode(throwable: Throwable): String {
        val base = errorCodeOf(throwable)
        val detail = (throwable as? AppException)?.detail.orEmpty()
        val retcode = RETCODE_REGEX.find(detail)?.groupValues?.getOrNull(1)
        return if (retcode.isNullOrBlank()) base else "$base retcode=$retcode"
    }

    private companion object {
        const val TAG = "LabViewModel"
        const val LAB_LOG_LABEL = "LeLeMusic-Lab-Log"

        /**
         * 自检的单个策略超时。
         *
         * 刻意比线上的 `TIMEOUT_RESOLVE_MS`(5s) 宽松：自检的目的是**看清真实错误**，
         * 而不是快速降级。5s 太紧，弱网下会把正常的慢请求误报成超时。
         */
        const val LAB_TIMEOUT_MS = 15_000L

        /**
         * 每个平台取的样本歌曲数。
         *
         * 取 3 首是为了区分「这个策略整体不行」和「这首歌恰好被版权拦了」——
         * 单样本很容易把个别歌曲的付费墙误判成策略失败。
         * 若真机上酷狗触发风控（已出现过 `Access Deny` / `errcode=1002`），把这个数调成 1 即可。
         */
        const val SAMPLE_COUNT = 3

        /**
         * 自检相邻请求的最小间隔。
         *
         * 酷狗按 IP 限频（`errcode=1002 您操作太频繁了`），自检矩阵里同一平台的多首歌
         * 原本在几百毫秒内连打，极易把自己打进制裁名单、把「限频」误报成「平台不可用」。
         */
        const val RATE_LIMIT_GAP_MS = 800L

        /** 从 `AppException.detail` 里抽上游返回码（`retcode=xxx`） */
        val RETCODE_REGEX = Regex("retcode=(-?\\d+)")
    }
}

/**
 * [LabViewModel] 的工厂（手工 DI，无 Hilt）。
 *
 * 只重写单参数 `create(modelClass)`：它在 `ViewModelProvider.Factory` 里从 1.0 就存在，
 * 而 `create(modelClass, extras)` 的默认实现就是转发到它，因此只重写这一个最稳。
 */
fun labViewModelFactory(
    appContext: Context,
    chartRepository: ChartRepository,
    registry: ResolverRegistry,
    probe: UrlDurationProbe,
    settings: AppSettings
): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LabViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return LabViewModel(appContext, chartRepository, registry, probe, settings) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

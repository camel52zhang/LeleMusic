package com.lelemusic.ui.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lelemusic.R
import com.lelemusic.core.common.AppDispatchers
import com.lelemusic.core.common.AppError
import com.lelemusic.core.di.AppGraph
import com.lelemusic.model.Lyric
import com.lelemusic.model.LyricParsers
import com.lelemusic.model.LyricResult
import com.lelemusic.model.LocalMedia
import com.lelemusic.model.PlaybackMode
import com.lelemusic.model.ResolveFailure
import com.lelemusic.model.Song
import com.lelemusic.model.toSong
import com.lelemusic.model.toStorableSong
import com.lelemusic.player.PlaybackController
import com.lelemusic.player.ResolvedTrackStore
import com.lelemusic.player.TrackStore
import com.lelemusic.repo.QueueSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放器页的 UI 状态（一次性给 `PlayerScreen` / `MiniPlayerBar` 消费，避免 Composable 里散落七八个 collect）。
 *
 * @property song       当前曲目；null = 还没播过任何歌
 * @property isPlaying  是否正在播放
 * @property connected  是否已连上 `PlaybackService`；false 时 UI 显示「连接中」
 * @property positionMs 当前进度
 * @property durationMs 总时长；0 = 未知（UI 显示 `--:--`）
 * @property isTrial    true = 当前是试听片段（橙色胶囊）
 * @property failure    取链失败记录（来自 `ResolvedTrackStore`）；非 null 时显示错误卡片
 * @property error      播放器错误（来自 `PlaybackController`）
 * @property mode       当前播放模式
 */
data class PlayerUiState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val connected: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isTrial: Boolean = false,
    val failure: ResolveFailure? = null,
    val error: AppError? = null,
    val mode: PlaybackMode = PlaybackMode.LIST_LOOP,
    /**
     * ExoPlayer **原始错误码**诊断串，形如
     * `错误码 2000 (ERROR_CODE_IO_UNSPECIFIED) · cause=IOException: resolve failed for ...`。
     *
     * [AppError] 把 5 种 IO 错误折叠成了一个 `Network`，丢掉了定位所需的关键信息
     * （网易云排查因此被误导了四轮）。这一项保留原始证据，直接显示在错误卡片上供截图回传。
     */
    val playerErrorDetail: String? = null
)

/**
 * 播放页歌词面板状态。
 *
 * - [Idle]       还没有歌曲 / 曲目未知
 * - [Loading]    正在从平台拉取歌词
 * - [Ready]      歌词就绪；[fromLocal] = true 表示来自本地 .lrc/.srt 文件（显示「本地」徽标）
 * - [Message]    非歌词态（纯音乐 / 获取失败 / 平台不支持），[text] 为用户可见文案
 */
sealed interface LyricUi {
    data object Idle : LyricUi
    data object Loading : LyricUi
    data class Ready(val lyric: Lyric, val fromLocal: Boolean = false) : LyricUi
    /**
     * 占位消息（暂无歌词 / 获取失败 / 平台不支持等）。
     * [canMatchOnline] = true 时 UI 显示「联网匹配歌词」可点提示（2026-09-20 用户需求）。
     */
    data class Message(val text: String, val canMatchOnline: Boolean = false) : LyricUi
}

/**
 * 播放器 ViewModel。
 *
 * 由 `RootNav` **在 Activity 作用域**创建（`viewModel()` 在 `NavHost` 之外调用，
 * 拿到的是 `ComponentActivity` 的 ViewModelStore），
 * 因此榜单页、播放器页、迷你播放条共享**同一个** [PlaybackController] 与同一份状态，
 * 不会各建一个 `MediaController`。
 *
 * @param appContext **必须是 ApplicationContext**：本 ViewModel 的生命周期长于 Activity
 */
class PlayerViewModel(
    private val appContext: Context
) : ViewModel() {

    private val trackStore: TrackStore = AppGraph.trackStore
    private val resolvedStore: ResolvedTrackStore = AppGraph.resolvedStore

    // -----------------------------------------------------------------------
    // 队列记忆（进程被杀后恢复现场）
    // -----------------------------------------------------------------------

    /** 队列记忆仓库单例（`filesDir/queue.json`） */
    private val queueMemory = AppGraph.queueMemoryStore

    /** 当前播放队列的内存副本（决定「下一首」走向 + 快照 index 定位） */
    private var activeQueue: List<Song> = emptyList()

    /**
     * T03 的遥控器。
     *
     * `trackStore` / `resolvedStore` 用默认值（即 `AppGraph` 单例，与 Service 侧同一份），
     * `modeStore` 显式注入 `AppGraph.playbackModeStore`，保证与 T05 自检台共享同一实例。
     */
    private val controller = PlaybackController(
        context = appContext,
        modeStore = AppGraph.playbackModeStore
    )

    // -----------------------------------------------------------------------
    // 歌词（云端自动拉取 + 本地 .lrc/.srt 覆盖）
    // -----------------------------------------------------------------------

    private val _lyric = MutableStateFlow<LyricUi>(LyricUi.Idle)
    /** 播放页歌词面板状态；随 `controller.currentUid` 变化自动拉取 */
    val lyric: StateFlow<LyricUi> = _lyric.asStateFlow()

    /** 云端歌词缓存：uid → 最终状态，同一首歌只拉一次，切走再切回不重复请求 */
    private val cloudLyrics = HashMap<String, LyricUi>()

    /** 本地歌词覆盖：uid → 解析结果；存在时优先展示（忽略云端） */
    private val localLyrics = HashMap<String, Lyric>()

    /** 当前歌词拉取任务；切歌时取消上一个 */
    private var lyricJob: Job? = null

    private val base: Flow<PlayerUiState> = combine(
        controller.currentUid,
        controller.isPlaying,
        controller.connected,
        controller.playbackMode,
        controller.error
    ) { uid: String?, isPlaying: Boolean, connected: Boolean,
        mode: PlaybackMode, error: AppError? ->
        PlayerUiState(
            song = uid?.let { key -> trackStore.get(key) },
            isPlaying = isPlaying,
            connected = connected,
            mode = mode,
            error = error
        )
    }

    /**
     * 对外状态。
     *
     * `isTrial` / `failure` 来自 [ResolvedTrackStore]——它是普通 `ConcurrentHashMap`，**不是 Flow**，
     * 因此搭 `positionMs` 的「顺风车」刷新：播放中 `positionMs` 每 500ms 一跳，
     * 解析结果一落地最多 500ms 就会反映到 UI 上；暂停且不播放时靠 `error` / `mode` 等事件驱动刷新。
     */
    val uiState: StateFlow<PlayerUiState> = base
        .combine(controller.durationMs) { state: PlayerUiState, duration: Long ->
            state.copy(durationMs = duration)
        }
        .combine(controller.positionMs) { state: PlayerUiState, position: Long ->
            state.copy(positionMs = position)
        }
        // ExoPlayer 原始错误码：与 error 平行的一条独立流（combine 最多支持 5 个异构流，
        // base 里已经用满，因此按项目既有风格链式追加）。
        .combine(controller.playerErrorDetail) { state: PlayerUiState, detail: String? ->
            state.copy(playerErrorDetail = detail)
        }
        .map { state: PlayerUiState ->
            val uid = state.song?.uid
            // 封面回填（2026-09-12）：搜索结果等来源可能无封面（song.coverUrl = null），
            // 取链成功后 resolvedStore 里有 playInfo 的 album_img——借 positionMs 的
            // 500ms 顺风车刷进 UI（播放页大图 / 迷你条 / 通知 metadata 同源）。
            val resolvedCover = uid?.let { resolvedStore.trackOf(it)?.coverUrl }
            val song = state.song?.let { s ->
                if (s.coverUrl == null && !resolvedCover.isNullOrBlank()) {
                    s.copy(coverUrl = resolvedCover)
                } else {
                    s
                }
            }
            state.copy(
                song = song,
                isTrial = uid != null && resolvedStore.isTrial(uid),
                failure = uid?.let { key -> resolvedStore.failureOf(key) }
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
            initialValue = PlayerUiState()
        )

    init {
        controller.connect()
        // 歌词跟随当前曲目：切歌 / 播放列表变化都重新计算歌词状态。
        viewModelScope.launch {
            controller.currentUid.collectLatest { uid -> onSongChanged(uid) }
        }
        // 队列记忆：启动时恢复上次现场（服务异步连接，restoreQueue 内部会等连上再补执行）
        viewModelScope.launch { restoreQueueSnapshot() }
        // 队列记忆：切歌即存快照（自动下一首 / 手动切歌都会经过 currentUid）
        viewModelScope.launch {
            controller.currentUid.collect { uid ->
                if (uid != null) persistQueueAt(uid)
            }
        }
        // 队列记忆：播放中每 10s 更新位置；转暂停 / 结束 / 出错时补存一次（保 wasPlaying 语义）
        viewModelScope.launch {
            controller.isPlaying.collectLatest { playing ->
                if (playing) {
                    while (true) {
                        delay(QUEUE_SAVE_INTERVAL_MS)
                        val uid = controller.currentUid.value ?: break
                        persistQueueAt(uid, forcePosition = controller.positionMs.value)
                    }
                } else {
                    val uid = controller.currentUid.value
                    if (uid != null) persistQueueAt(uid, forcePosition = controller.positionMs.value)
                }
            }
        }
    }

    /** 播放某首歌；[queue] 是上下文队列，决定「播完下一首」的走向 */
    fun playSong(song: Song, queue: List<Song> = listOf(song)) {
        val effectiveQueue = queue.ifEmpty { listOf(song) }
        activeQueue = effectiveQueue
        controller.playSong(song = song, queue = effectiveQueue)
        persistQueueAt(song.uid, forceWasPlaying = true)
    }

    /** 播放 / 暂停切换 */
    fun togglePlayPause() {
        controller.togglePlayPause()
    }

    fun skipNext() {
        controller.skipNext()
    }

    fun skipPrevious() {
        controller.skipPrevious()
    }

    /** 跳转到指定进度（ms） */
    fun seekTo(positionMs: Long) {
        controller.seekTo(positionMs)
    }

    /** 解析失败后重试：清失败态 + 重新 `prepare()` 触发一次新的懒解析 */
    fun retry() {
        controller.retry()
    }

    /** 循环切换播放模式，返回切换后的模式（模式键图标/文字随 uiState.mode 实时更新） */
    fun cyclePlaybackMode(): PlaybackMode = controller.cyclePlaybackMode()

    /** 当前播放倍速（1.0 = 常速；慢放 / 快进），随服务侧变化自动同步 */
    val speed: StateFlow<Float> = controller.speed

    /** 设置播放倍速（快进 >1 / 慢放 <1，变速不变调） */
    fun setSpeed(speed: Float) {
        controller.setSpeed(speed)
    }

    /**
     * 跳转到平台官方页面收听（PRD：播放失败时的兜底出口）。
     *
     * 用 ApplicationContext 启动，因此必须加 `FLAG_ACTIVITY_NEW_TASK`。
     */
    fun openInPlatform(song: Song) {
        val url = song.platform.songPageUrl(song.platformSongId)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(intent)
            Log.i(TAG, "openInPlatform: $url")
        } catch (notFound: ActivityNotFoundException) {
            Log.w(TAG, "no browser to open $url", notFound)
            Toast.makeText(
                appContext,
                R.string.error_open_link_failed,
                Toast.LENGTH_SHORT
            ).show()
        } catch (throwable: SecurityException) {
            Log.e(TAG, "openInPlatform rejected: $url", throwable)
        }
    }

    // -----------------------------------------------------------------------
    // 队列记忆（快照读写）
    // -----------------------------------------------------------------------

    /**
     * 启动时从磁盘恢复上次的队列 + 位置 + 播放态。
     *
     * 是否自动续播由「启动自动播放」开关（[DisplayPrefs.autoPlayOnLaunch]，默认开）决定：
     * 开 = 恢复后接着上次进度自动播放；关 = 恢复队列但停在暂停态，等用户手动点播放。
     */
    private suspend fun restoreQueueSnapshot() {
        val snapshot = queueMemory.snapshot() ?: return
        val songs = snapshot.songs.mapNotNull { storable -> storable.toSong(AppGraph.platformById) }
        if (songs.isEmpty()) return
        if (snapshot.index !in songs.indices) return
        activeQueue = songs
        // 启动自动续播开关（2026-09-12 用户需求，默认开）：
        // 关闭时仍恢复队列和进度，但停在暂停态，等用户手动点播放
        val autoplay = AppGraph.displayPrefs.autoPlayOnLaunch.value
        controller.restoreQueue(
            songs = songs,
            startIndex = snapshot.index,
            startPositionMs = snapshot.positionMs,
            autoplay = autoplay
        )
        Log.i(TAG, "queue restored: index=${snapshot.index} pos=${snapshot.positionMs} autoplay=$autoplay")
    }

    /**
     * 按当前播放 uid 定位队列下标并落一次快照。
     * 写盘丢到 IO 线程，避免在大队列时卡主线程。
     */
    private fun persistQueueAt(
        uid: String,
        forcePosition: Long? = null,
        forceWasPlaying: Boolean? = null
    ) {
        val index = activeQueue.indexOfFirst { it.uid == uid }
        if (index < 0) return
        viewModelScope.launch {
            withContext(AppDispatchers.IO) {
                queueMemory.save(
                    QueueSnapshot(
                        songs = activeQueue.map { it.toStorableSong() },
                        index = index,
                        positionMs = forcePosition ?: controller.positionMs.value,
                        wasPlaying = forceWasPlaying ?: controller.isPlaying.value,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 歌词逻辑
    // -----------------------------------------------------------------------

    /**
     * 当前曲目变化：清掉上一个拉取任务，按「本地覆盖 → 云端缓存 → 重新拉取」的顺序出状态。
     */
    private fun onSongChanged(uid: String?) {
        lyricJob?.cancel()
        lyricJob = null
        if (uid == null) {
            _lyric.value = LyricUi.Idle
            return
        }
        localLyrics[uid]?.let { local ->
            _lyric.value = LyricUi.Ready(lyric = local, fromLocal = true)
            return
        }
        cloudLyrics[uid]?.let { cached ->
            _lyric.value = cached
            return
        }
        _lyric.value = LyricUi.Loading
        lyricJob = viewModelScope.launch {
            val song = AppGraph.trackStore.get(uid)
            val next = if (song == null) {
                LyricUi.Idle
            } else {
                loadCloudLyric(song)
            }
            _lyric.value = next
            cloudLyrics[uid] = next
        }
    }

    /** 走平台歌词源拉取；无源 / 无词 / 失败都收敛成可展示的 [LyricUi]（无词/失败时给出联网匹配入口） */
    private suspend fun loadCloudLyric(song: Song): LyricUi {
        val source = AppGraph.lyricSources.firstOrNull { it.platform.id == song.platform.id }
        if (source == null) {
            return LyricUi.Message(
                appContext.getString(R.string.lyric_platform_unsupported),
                canMatchOnline = true
            )
        }
        val primary = source.lyric(song)
        // M2c：主链路无词 / 出错时，用「歌名 + 歌手」在平台内搜一次有词版本兜底
        val finalResult = when (primary) {
            is LyricResult.Success -> primary
            is LyricResult.NoLyric ->
                if (song.title.isNotBlank()) {
                    source.lyricBySearch(song.title, song.artist, song.durationMs)
                } else {
                    primary
                }

            is LyricResult.Error ->
                if (song.title.isNotBlank()) {
                    source.lyricBySearch(song.title, song.artist, song.durationMs)
                } else {
                    primary
                }
        }
        return when (finalResult) {
            is LyricResult.Success -> LyricUi.Ready(lyric = finalResult.lyric)
            is LyricResult.NoLyric -> {
                val reason = finalResult.reason
                LyricUi.Message(
                    if (reason.isBlank() || reason == NO_SEARCH_REASON) {
                        appContext.getString(R.string.lyric_empty)
                    } else {
                        reason
                    },
                    canMatchOnline = true
                )
            }
            is LyricResult.Error -> LyricUi.Message(
                appContext.getString(R.string.lyric_fetch_failed),
                canMatchOnline = true
            )
        }
    }

    /**
     * 「暂无歌词」点击兜底（2026-09-20 用户需求，图 008）：按「歌名 + 歌手」遍历
     * 云端歌词源（网易云 → 酷狗）联网匹配。
     *
     * 主要服务**本地导入歌曲**——本地文件没有平台 songId，自动链路只认同目录旁路字幕；
     * 而导入文件名常是「歌名@歌手」（如「爱情讯息@郭静」），artist 为空时按 @ 拆成
     * 关键词再搜，命中率显著提高。命中即写入 [cloudLyrics] 缓存（同曲不重复搜）；
     * 全部源都没命中则给创意提示，可再次点击重试。
     */
    fun matchOnlineLyric() {
        val uid = controller.currentUid.value ?: return
        if (localLyrics.containsKey(uid)) return // 本地歌词优先，不打扰
        val song = AppGraph.trackStore.get(uid) ?: return
        lyricJob?.cancel()
        _lyric.value = LyricUi.Loading
        lyricJob = viewModelScope.launch {
            val (title, artist) = matchKeywordsOf(song)
            val cloudSources = AppGraph.lyricSources
                .filter { it.platform.id != LocalMedia.PLATFORM_ID }
            var matched: LyricUi? = null
            for (source in cloudSources) {
                val result = source.lyricBySearch(title, artist, song.durationMs)
                if (result is LyricResult.Success) {
                    matched = LyricUi.Ready(lyric = result.lyric)
                    break
                }
            }
            // 匹配期间用户可能已切歌：过期结果直接丢弃
            if (controller.currentUid.value != uid) return@launch
            val next = matched ?: LyricUi.Message(
                appContext.getString(R.string.lyric_match_failed),
                canMatchOnline = true
            )
            _lyric.value = next
            if (matched != null) cloudLyrics[uid] = next
        }
    }

    /** 匹配关键词：artist 为空且歌名含「@」时拆成 歌名@歌手（本地导入文件名惯例） */
    private fun matchKeywordsOf(song: Song): Pair<String, String> {
        val title = song.title.trim()
        val artist = song.artist.trim()
        if (artist.isNotBlank() || title.isEmpty()) return title to artist
        val at = title.lastIndexOf('@')
        if (at in 1 until title.length - 1) {
            val t = title.substring(0, at).trim()
            val a = title.substring(at + 1).trim()
            if (t.isNotEmpty() && a.isNotEmpty()) return t to a
        }
        return title to artist
    }

    /** 从文件选择器选中的 Uri 读取并解析本地歌词，覆盖云端结果绑定到当前歌曲 */
    fun loadLocalLyric(uri: Uri) {
        val uid = controller.currentUid.value
        if (uid == null) {
            toastText(appContext.getString(R.string.lyric_no_song))
            return
        }
        viewModelScope.launch {
            val parsed = withContext(AppDispatchers.IO) { parseLocalFile(uri) }
            when (parsed) {
                is LocalParse.Ok -> {
                    localLyrics[uid] = parsed.lyric
                    cloudLyrics.remove(uid)
                    _lyric.value = LyricUi.Ready(lyric = parsed.lyric, fromLocal = true)
                    toastText(appContext.getString(R.string.lyric_imported))
                }
                LocalParse.BadType -> toastText(appContext.getString(R.string.lyric_bad_format))
                LocalParse.Empty -> toastText(appContext.getString(R.string.lyric_empty_file))
                LocalParse.ReadFailed -> toastText(appContext.getString(R.string.lyric_read_failed))
            }
        }
    }

    /** 清除当前歌曲的本地歌词覆盖，回落到平台歌词 */
    fun clearLocalLyric() {
        val uid = controller.currentUid.value ?: return
        if (localLyrics.remove(uid) == null) return
        cloudLyrics.remove(uid)
        onSongChanged(uid)
    }

    private sealed interface LocalParse {
        data class Ok(val lyric: Lyric) : LocalParse
        data object BadType : LocalParse
        data object Empty : LocalParse
        data object ReadFailed : LocalParse
    }

    /** 读 SAF Uri 内容并按扩展名解析（调用方必须已在 IO 调度器上） */
    private fun parseLocalFile(uri: Uri): LocalParse {
        val resolver = appContext.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        val lower = name?.lowercase().orEmpty()
        val supported = lower.endsWith(".lrc") || lower.endsWith(".srt")
        if (!supported) return LocalParse.BadType
        val text = runCatching {
            resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                reader.readText()
            }
        }.getOrNull() ?: return LocalParse.ReadFailed
        val lyric = LyricParsers.parseFile(name, text)
        return if (lyric.isEmpty) LocalParse.Empty else LocalParse.Ok(lyric)
    }

    private fun toastText(text: String) {
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }

    override fun onCleared() {
        super.onCleared()
        // 退出前的最后一次快照（同步写，规避 viewModelScope 已取消的时序）：
        // 没有这一次补存，从最后一次定时快照到真正退出之间最多丢 10 秒进度。
        val uid = controller.currentUid.value
        if (uid != null) {
            val index = activeQueue.indexOfFirst { it.uid == uid }
            if (index >= 0) {
                queueMemory.save(
                    QueueSnapshot(
                        songs = activeQueue.map { it.toStorableSong() },
                        index = index,
                        positionMs = controller.positionMs.value,
                        wasPlaying = controller.isPlaying.value,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
                Log.i(TAG, "final queue snapshot flushed on cleared: uid=$uid pos=${controller.positionMs.value}")
            }
        }
        controller.release()
    }

    private companion object {
        const val TAG = "PlayerViewModel"

        /** 播放中更新队列位置快照的间隔（ms）：太频繁伤盘，太疏恢复误差大 */
        const val QUEUE_SAVE_INTERVAL_MS = 10_000L

        /** [com.lelemusic.data.source.LyricSource] 默认 lyricBySearch 的 NoLyric reason */
        const val NO_SEARCH_REASON = "search_unsupported"
    }
}

/**
 * [PlayerViewModel] 的工厂（手工 DI，无 Hilt）。
 *
 * 只重写单参数 `create(modelClass)`：它在 `ViewModelProvider.Factory` 里从 1.0 就存在，
 * 而 `create(modelClass, extras)` 的**默认实现就是转发到它**，因此只重写这一个最稳。
 */
fun playerViewModelFactory(appContext: Context): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return PlayerViewModel(appContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

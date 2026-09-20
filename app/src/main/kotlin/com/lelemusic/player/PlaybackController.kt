package com.lelemusic.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lelemusic.core.common.AppDispatchers
import com.lelemusic.core.common.AppError
import com.lelemusic.core.di.AppGraph
import com.lelemusic.model.PlaybackMode
import com.lelemusic.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * UI 侧的播放遥控器：把 [PlaybackService] 里的 `MediaSession` 包装成
 * 一组 StateFlow + 命令方法，供 T04 的 `PlayerViewModel` / `MiniPlayerBar` 直接消费。
 *
 * **连接写法严格照抄架构文档 §7.7 第 1 条**：`buildAsync()` + `addListener` +
 * `ContextCompat.getMainExecutor`。要点：
 * - `buildAsync()` 返回 Guava 的 `ListenableFuture`，但这里**不 import 任何 Guava 类**，
 *   只用它的 `addListener(Runnable, Executor)` 接口方法 → 零额外依赖、编译面最小；
 * - 严禁在主线程直接 `future.get()`（会 ANR），必须在 listener 回调里取；
 * - 所有 `Player` 方法都要求**主线程**，因此本类所有方法都应在主线程调用。
 *
 * 曲目登记：`setPlaylist` / `playSong` 会先把 `Song` 写进 [TrackStore]，
 * ExoPlayer 加载线程上的 `ResolveDataSpecResolver` 才能按 uid 反查到曲目。
 * **顺序不能反**——先登记再下发 MediaItem。
 *
 * @param context 用于建 `SessionToken` 与 `MediaController`；建议传 ApplicationContext
 * @param trackStore 曲目内存表（默认取 `AppGraph.trackStore`）
 * @param resolvedStore 解析结果内存表（UI 读「试听片段」胶囊 / 错误卡片；重试时用于清空失败态）
 * @param modeStore 播放模式持久化（默认基于 `AppGraph.settings`）
 */
class PlaybackController(
    private val context: Context,
    private val trackStore: TrackStore = AppGraph.trackStore,
    private val resolvedStore: ResolvedTrackStore = AppGraph.resolvedStore,
    private val modeStore: PlaybackModeStore = PlaybackModeStore(AppGraph.settings)
) {

    @Volatile
    private var controller: MediaController? = null

    /**
     * 是否**正在连接中**。
     *
     * 必须单独记一状态：`controller` 只有在连接真正成功后才非空，
     * 若只用 `controller != null` 判重，连续两次 `setPlaylist` 会建出两个
     * `MediaController`（第二个永远拿不到回调，成了泄漏的僵尸连接）。
     */
    @Volatile
    private var connecting: Boolean = false

    /** 进度轮询作用域；`release()` 后可在 `connect()` 里重新创建 */
    private var scope: CoroutineScope? = null
    private var progressJob: Job? = null

    /** 连接尚未完成时下发的指令，连上后自动补执行 */
    private var pendingAction: (() -> Unit)? = null

    private val _connected = MutableStateFlow(false)
    /** 是否已连上服务；UI 应据此显示「连接中」 */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    /** 是否正在播放（含「准备中但 playWhenReady=true」） */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentUid = MutableStateFlow<String?>(null)
    /** 当前曲目的 uid（`Song.uid`，与 `MediaItem.mediaId` 一致） */
    val currentUid: StateFlow<String?> = _currentUid.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    /** 当前播放进度（ms），播放中每 500ms 更新一次 */
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    /** 当前曲目总时长（ms）；未知为 0，UI 显示 `--:--` */
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playbackMode = MutableStateFlow(modeStore.current)
    /** 当前播放模式（列表循环 / 单曲循环 / 随机） */
    val playbackMode: StateFlow<PlaybackMode> = _playbackMode.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    /** 当前播放倍速（1.0 = 常速；<1 慢放，>1 快进） */
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _error = MutableStateFlow<AppError?>(null)
    /** 最近一次播放错误；重新开始播放或 [retry] 时清空 */
    val error: StateFlow<AppError?> = _error.asStateFlow()

    /**
     * 最近一次 **ExoPlayer 原始错误**，形如
     * `错误码 2000 (ERROR_CODE_IO_UNSPECIFIED) · cause=IOException: resolve failed for ...`。
     *
     * **为什么必须单独留一份**：[toAppError] 会把 5 种不同的 IO 错误码全部折叠成
     * [AppError.Network]，UI 只能显示「网络异常，请检查网络连接」。网易云那次排查里，
     * 这句笼统文案把我们误导了整整四轮——真实原因压根不在网络层（播放探针证明直链能拉 256KB）。
     * 原始错误码是**唯一**能区分「取链失败被包成 IOException」与「真正的网络故障」的证据，
     * 且用户没有 adb，看不到 Logcat，因此必须显示在错误卡片上，靠截图回传。
     */
    private val _playerErrorDetail = MutableStateFlow<String?>(null)
    val playerErrorDetail: StateFlow<String?> = _playerErrorDetail.asStateFlow()

    // -----------------------------------------------------------------------
    // 连接 / 释放
    // -----------------------------------------------------------------------

    /**
     * 异步连接 `PlaybackService`（幂等：已连接或正在连接时直接返回）。
     *
     * 严格照抄架构文档 §7.7 第 1 条。
     */
    fun connect() {
        if (controller != null || connecting) return
        connecting = true

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                // 在 listener 里 get() 不会阻塞：此刻 future 已完成
                runCatching { future.get() }
                    .onSuccess { connectedController ->
                        controller = connectedController
                        connectedController.addListener(playerListener)
                        modeStore.applyTo(connectedController)

                        _currentUid.value = connectedController.currentMediaItem?.mediaId
                        _isPlaying.value = connectedController.isPlaying
                        _positionMs.value = connectedController.currentPosition.coerceAtLeast(0L)
                        _durationMs.value = connectedController.duration
                            .takeIf { duration -> duration > 0L } ?: 0L
                        _playbackMode.value = modeStore.current
                        _connected.value = true
                        connecting = false
                        Log.i(TAG, "MediaController connected")

                        // 同步服务侧真实倍速（覆盖安装 / 其它客户端改过时保持一致）
                        _speed.value = connectedController.playbackParameters.speed.coerceIn(0.25f, 3f)

                        if (connectedController.isPlaying) startProgressPolling()

                        pendingAction?.invoke()
                        pendingAction = null
                    }
                    .onFailure { throwable ->
                        Log.w(TAG, "MediaController connect failed", throwable)
                        connecting = false
                        _connected.value = false
                        _error.value = AppError.Unknown
                    }
            },
            ContextCompat.getMainExecutor(context)   // androidx.core:core-ktx，已显式依赖
        )
    }

    /** 释放连接；可再次 [connect]。建议在 `ViewModel.onCleared` 里调用 */
    fun release() {
        Log.i(TAG, "release")
        stopProgressPolling()
        pendingAction = null
        connecting = false
        scope?.cancel()
        scope = null

        val current = controller
        controller = null
        if (current != null) {
            runCatching { current.removeListener(playerListener) }
                .onFailure { throwable -> Log.w(TAG, "removeListener failed", throwable) }
            runCatching { current.release() }
                .onFailure { throwable -> Log.w(TAG, "controller.release failed", throwable) }
        }
        _connected.value = false
        _isPlaying.value = false
    }

    // -----------------------------------------------------------------------
    // 播放控制
    // -----------------------------------------------------------------------

    /**
     * 设置播放队列并从 [startIndex] 开始播放。
     *
     * 先把曲目登记进 [TrackStore]，再通过 `MediaController` 下发（顺序不可反）。
     * 未连接时暂存为 `pendingAction`，连上后自动补执行（UI 不必自己处理时序）。
     */
    fun setPlaylist(songs: List<Song>, startIndex: Int = 0, autoPlay: Boolean = true) {
        if (songs.isEmpty()) {
            Log.w(TAG, "setPlaylist with empty list -> ignored")
            return
        }
        trackStore.putAll(songs)

        if (controller == null) {
            pendingAction = { applyPlaylist(songs, startIndex, autoPlay) }
            connect()
            return
        }
        applyPlaylist(songs, startIndex, autoPlay)
    }

    /**
     * 播放单曲。[queue] 是上下文队列（默认只放这一首），决定「播完自动下一首」的走向。
     *
     * 兜底：队列为空（例如 `TrackStore` 被清空）时退化成「只放这一首」，
     * 宁可只播一首，也不能什么都不播还静默返回。
     */
    fun playSong(song: Song, queue: List<Song> = listOf(song)) {
        val effectiveQueue = queue.ifEmpty { listOf(song) }
        val index = effectiveQueue.indexOfFirst { item -> item.uid == song.uid }
        setPlaylist(effectiveQueue, index.coerceAtLeast(0), autoPlay = true)
    }

    /**
     * 按 uid 播放；曲目必须已经由榜单仓库登记进 [TrackStore]。
     *
     * @param queue 播放队列，传 null 时用 [TrackStore.ordered]（按写入顺序的快照）
     */
    fun playUid(uid: String, queue: List<Song>? = null) {
        val song = trackStore.get(uid)
        if (song == null) {
            Log.w(TAG, "playUid: $uid not in TrackStore")
            _error.value = AppError.EmptyData
            return
        }
        playSong(song, queue ?: trackStore.ordered())
    }

    /**
     * 恢复上次会话的播放现场（进程被杀后的「队列记忆」）。
     *
     * 与 [setPlaylist] 的差别：起始位置用 [startPositionMs]（从上次听的地方续），
     * 是否自动开播由 [autoplay]（= 快照里的 wasPlaying）决定。
     * 同样先登记 [TrackStore] 再下发；未连接时走 `pendingAction` 补执行。
     */
    fun restoreQueue(
        songs: List<Song>,
        startIndex: Int,
        startPositionMs: Long,
        autoplay: Boolean
    ) {
        if (songs.isEmpty()) {
            Log.w(TAG, "restoreQueue with empty list -> ignored")
            return
        }
        trackStore.putAll(songs)
        val action = { applyRestoreQueue(songs, startIndex, startPositionMs, autoplay) }
        if (controller == null) {
            pendingAction = action
            connect()
            return
        }
        action()
    }

    /** 播放 / 暂停切换 */
    fun togglePlayPause() {
        val current = controller ?: return
        runCatching {
            if (current.isPlaying) current.pause() else current.play()
        }.onFailure { throwable ->
            Log.w(TAG, "togglePlayPause failed", throwable)
            _error.value = AppError.Unknown
        }
    }

    /**
     * 从暂停恢复播放。
     *
     * 注意区分「STATE_ENDED 后重播」：此时直接 `play()` 不会重开数据源，
     * 必须先 `seekTo(0)`。
     */
    fun play() {
        val current = controller ?: return
        runCatching {
            if (current.playbackState == Player.STATE_ENDED) {
                current.seekTo(0L)
            }
            current.play()
        }.onFailure { throwable ->
            Log.w(TAG, "play failed", throwable)
            _error.value = AppError.Unknown
        }
    }

    fun pause() {
        val current = controller ?: return
        runCatching { current.pause() }
            .onFailure { throwable ->
                Log.w(TAG, "pause failed", throwable)
                _error.value = AppError.Unknown
            }
    }

    /** 下一首；列表循环下播到末尾会自动回卷到第 0 首 */
    fun skipNext() {
        val current = controller ?: return
        runCatching { current.seekToNextMediaItem() }
            .onFailure { throwable ->
                Log.w(TAG, "skipNext failed", throwable)
                _error.value = AppError.Unknown
            }
    }

    /** 上一首 */
    fun skipPrevious() {
        val current = controller ?: return
        runCatching { current.seekToPreviousMediaItem() }
            .onFailure { throwable ->
                Log.w(TAG, "skipPrevious failed", throwable)
                _error.value = AppError.Unknown
            }
    }

    /** 跳转到指定进度（ms） */
    fun seekTo(positionMs: Long) {
        val current = controller ?: return
        runCatching { current.seekTo(positionMs.coerceAtLeast(0L)) }
            .onFailure { throwable -> Log.w(TAG, "seekTo failed", throwable) }
        _positionMs.value = positionMs.coerceAtLeast(0L)
    }

    /** 停止播放并清空队列（保留服务与通知：通知会回到「无曲目」态） */
    fun stop() {
        val current = controller ?: return
        runCatching {
            current.stop()
            current.clearMediaItems()
        }.onFailure { throwable -> Log.w(TAG, "stop failed", throwable) }
        stopProgressPolling()
        _isPlaying.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
    }

    /**
     * 解析失败后重试：清掉该曲目的失败记录，重新 `prepare()` 触发一次新的懒解析。
     *
     * 关键：播放器报错后会进入 `STATE_IDLE`，只调 `play()` 不会重新打开数据源，
     * 必须重新 `prepare()` 才会再走一次 `ResolveDataSpecResolver`。
     */
    fun retry() {
        if (controller == null) {
            pendingAction = { retryInternal() }
            connect()
            return
        }
        retryInternal()
    }

    private fun retryInternal() {
        val current = controller
        if (current == null) {
            pendingAction = { retryInternal() }
            return
        }
        val uid = _currentUid.value
        if (uid != null) resolvedStore.invalidate(uid)
        _error.value = null
        _playerErrorDetail.value = null
        runCatching {
            current.prepare()
            current.play()
        }.onFailure { throwable ->
            Log.w(TAG, "retry failed", throwable)
            _error.value = AppError.Unknown
        }
    }

    /** 循环切换播放模式（列表循环 → 单曲循环 → 随机），并立即下发到播放器 */
    fun cyclePlaybackMode(): PlaybackMode {
        val next = modeStore.cycle()
        _playbackMode.value = next
        controller?.let { current ->
            runCatching { modeStore.applyTo(current) }
                .onFailure { throwable -> Log.w(TAG, "apply playback mode failed", throwable) }
        }
        Log.i(TAG, "playback mode -> ${next.name}")
        return next
    }

    /**
     * 设置播放倍速（快进 / 慢放）。Media3 会把音调保持（pitch = 1），变速不变调。
     * 未连接时暂存并自动补执行（连上后先取服务侧速度覆盖，再应用本值——见 [connect]）。
     */
    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        _speed.value = clamped
        val current = controller
        if (current == null) {
            pendingAction = { applySpeed(clamped) }
            connect()
            return
        }
        applySpeed(clamped)
    }

    private fun applySpeed(speed: Float) {
        val current = controller ?: return
        runCatching {
            current.setPlaybackParameters(PlaybackParameters(speed))
        }.onFailure { throwable ->
            Log.w(TAG, "setPlaybackParameters($speed) failed", throwable)
        }
        _speed.value = speed
    }

    /** 当前曲目；未开始播放返回 null */
    fun currentSong(): Song? = _currentUid.value?.let { uid -> trackStore.get(uid) }

    /** 当前曲目是否为试听片段（供 MiniPlayerBar / PlayerScreen 渲染橙色胶囊） */
    fun isCurrentTrial(): Boolean {
        val uid = _currentUid.value ?: return false
        return resolvedStore.isTrial(uid)
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    private fun applyPlaylist(songs: List<Song>, startIndex: Int, autoPlay: Boolean) {
        val current = controller
        if (current == null) {
            pendingAction = { applyPlaylist(songs, startIndex, autoPlay) }
            return
        }
        val items: List<MediaItem> = MediaItemFactory.from(songs)
        if (items.isEmpty()) return

        val index = startIndex.coerceIn(0, items.lastIndex)
        _error.value = null
        _playerErrorDetail.value = null
        runCatching {
            current.setMediaItems(items, index, 0L)
            current.prepare()
            if (autoPlay) current.play()
        }.onFailure { throwable ->
            Log.w(TAG, "applyPlaylist failed", throwable)
            _error.value = AppError.Unknown
        }
        _currentUid.value = items[index].mediaId
        _positionMs.value = 0L
    }

    private fun applyRestoreQueue(
        songs: List<Song>,
        startIndex: Int,
        startPositionMs: Long,
        autoplay: Boolean
    ) {
        val current = controller
        if (current == null) {
            pendingAction = { applyRestoreQueue(songs, startIndex, startPositionMs, autoplay) }
            return
        }
        // 服务还活着且已有播放现场（典型：划掉任务后前台服务仍在播）时，
        // 不用旧快照重置它——直接接管现有会话，否则会把正在播的歌拉回 10 秒前的快照位置。
        if (current.currentMediaItem != null) {
            Log.i(TAG, "restoreQueue skipped: service already has a live session")
            return
        }
        val items: List<MediaItem> = MediaItemFactory.from(songs)
        if (items.isEmpty()) return

        val index = startIndex.coerceIn(0, items.lastIndex)
        val position = startPositionMs.coerceAtLeast(0L)
        _error.value = null
        _playerErrorDetail.value = null
        runCatching {
            current.setMediaItems(items, index, position)
            current.prepare()
            if (autoplay) current.play()
        }.onFailure { throwable ->
            Log.w(TAG, "restoreQueue failed", throwable)
            _error.value = AppError.Unknown
        }
        _currentUid.value = items[index].mediaId
        _positionMs.value = position
        Log.i(TAG, "restored queue startIndex=$index positionMs=$position autoplay=$autoplay songs=${items.size}")
    }

    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) startProgressPolling() else stopProgressPolling()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _speed.value = playbackParameters.speed.coerceIn(MIN_SPEED, MAX_SPEED)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _currentUid.value = mediaItem?.mediaId
            // 用播放器真实位置而非硬置 0：恢复现场（setMediaItems(index, position)）后
            // 该回调会异步触发，置 0 会让迷你条/播放页进度条闪回 00:00。
            _positionMs.value = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            _durationMs.value = 0L
            _error.value = null
            _playerErrorDetail.value = null
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val duration = controller?.duration ?: 0L
                    _durationMs.value = duration.takeIf { it > 0L } ?: 0L
                }
                Player.STATE_ENDED -> stopProgressPolling()
                Player.STATE_IDLE -> Unit
                Player.STATE_BUFFERING -> Unit
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "player error: ${error.errorCode} ${error.message}")
            // 故意先写 detail 再写 error：detail 是后续 combine 的另一个输入，
            // 先落它可避免出现「error 已更新但 detail 还是上一首歌的旧码」的中间态。
            _playerErrorDetail.value = formatPlayerError(error)
            _error.value = toAppError(error)
            stopProgressPolling()
            _isPlaying.value = false
        }
    }

    private fun startProgressPolling() {
        if (progressJob?.isActive == true) return
        val activeScope = scope ?: CoroutineScope(
            SupervisorJob() + AppDispatchers.Main.immediate
        ).also { created -> scope = created }

        progressJob = activeScope.launch {
            while (isActive) {
                val current = controller ?: break
                _positionMs.value = current.currentPosition.coerceAtLeast(0L)
                val duration = current.duration
                if (duration > 0L) _durationMs.value = duration
                delay(PROGRESS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    /**
     * `PlaybackException` → [AppError]。
     *
     * 只认 Media3 的错误码常量，不解析 message 文本（§7.3 铁律 2）。
     */
    private fun toAppError(exception: PlaybackException): AppError = when (exception.errorCode) {
        PlaybackException.ERROR_CODE_TIMEOUT -> AppError.Timeout

        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> AppError.Network

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> AppError.Parse

        else -> AppError.Unknown
    }

    /**
     * 把 `PlaybackException` 格式化成「一眼能定位」的诊断串，显示在错误卡片上。
     *
     * 只取**结构化字段**（`errorCode` / `errorCodeName` / cause），不解析 message 文本做分支
     * （架构文档 §7.3 铁律 2）——这里只是把信息展示给人看，不是让程序据此决策。
     *
     * 带上 cause 很关键：`ResolveDataSpecResolver` 会把取链失败包成
     * `IOException("resolve failed for ...")`，此时 errorCode 是 2000(IO_UNSPECIFIED)，
     * 光看码分不清是网络问题还是取链问题，必须看到 cause 才能定案。
     */
    private fun formatPlayerError(error: PlaybackException): String {
        val cause = error.cause
        val causeText = if (cause == null) {
            ""
        } else {
            " · cause=${cause.javaClass.simpleName}: ${cause.message ?: "no message"}"
        }
        return "错误码 ${error.errorCode} (${error.errorCodeName})$causeText"
    }

    private companion object {
        const val TAG = "PlaybackController"

        /** 进度轮询间隔（ms）：够细腻又不至于每帧刷新 */
        const val PROGRESS_POLL_INTERVAL_MS = 500L

        /** 倍速可调范围：0.25x ~ 3x（对应"慢放"与"快进"两侧极限） */
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 3f
    }
}

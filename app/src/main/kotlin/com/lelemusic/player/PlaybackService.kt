package com.lelemusic.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lelemusic.R
import com.lelemusic.core.common.AppError
import com.lelemusic.core.di.AppGraph

/**
 * 播放服务（架构文档 §6 T03 / §7.7 / 附录 B）。
 *
 * 继承 `MediaSessionService` 的原因：它会**自动**帮我们做三件麻烦事——
 * 1. 播放期间把服务提到前台（`startForeground`），退后台 / 关屏都不会被系统回收；
 * 2. 维护通知栏（`DefaultMediaNotificationProvider` 自带播放/暂停/上一首/下一首）；
 * 3. 对外暴露标准 `MediaSession`，系统控件（锁屏、耳机线控、Android Auto）直接可用。
 *
 * 生命周期与关键点：
 * - `onCreate`：建通知渠道 → 注入通知 Provider → 建 ExoPlayer（挂懒解析工厂）
 *   → 设 AudioAttributes（`handleAudioFocus = true`，来电自动让出焦点、挂断自动恢复）
 *   → 建 MediaSession → 动态注册 [BecomingNoisyReceiver]；
 * - `onTaskRemoved`：最近任务被划掉时，若**没在播放**就停服务（避免残留一条死通知）；
 * - `onDestroy`：注销 Receiver → 释放 Player → 释放 Session。
 *
 * ⚠️ 所有 Media3 API 必须在**主线程**调用；本类的方法均由系统在主线程回调。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var noisyReceiver: BecomingNoisyReceiver? = null
    private var modeStore: PlaybackModeStore? = null

    /**
     * 连续「受限自动跳过」计数（上限 = 队列长度，防整个队列都不给源时无限快跳）。
     *
     * 计数的窗口语义：只有**没进入 READY 就被跳过**的曲目会计数，
     * 任意一首真正开始播放（STATE_READY）或自然播完（STATE_ENDED）就归零——
     * 因此它量化的是「连续 N 首全被上游拒源」这个事实，上限 `mediaItemCount` 恰好兜住一圈。
     */
    private var consecutiveRestrictedSkips = 0

    // -----------------------------------------------------------------------
    // 生命周期
    // -----------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        // Android 8+ 必须先建通知渠道，否则通知根本不显示（REQ-P0-13）。
        createPlaybackNotificationChannel()

        // 通知栏：小图标必须是单色白 vector（res/drawable/ic_notification.xml）。
        // 必须在 onCreate 内注入，晚于这个时机第一条通知会用默认图标。
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )

        val mediaSourceFactory = ResolvingMediaSourceFactory(
            context = this,
            trackStore = AppGraph.trackStore,
            useCase = AppGraph.resolveUseCase,
            store = AppGraph.resolvedStore
        ).mediaSourceFactory

        val player = ExoPlayer.Builder(this, mediaSourceFactory)
            // 关屏后仍要保持 CPU / Wi-Fi 唤醒，否则音频流会断（REQ-P0-13：关屏持续播放）。
            // 需要 Manifest 声明 android.permission.WAKE_LOCK。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // 关掉 ExoPlayer 自带的 becoming-noisy 处理，统一由 BecomingNoisyReceiver 负责。
            .setHandleAudioBecomingNoisy(false)
            .build()
            .apply {
                // handleAudioFocus = true：
                // 来电 / 其他音乐 App 抢占 → 自动暂停；对方释放 → 自动恢复（REQ-P0-13）。
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true
                )
                addListener(playbackStateListener)
            }
        exoPlayer = player

        val store = PlaybackModeStore(AppGraph.settings)
        store.applyTo(player)
        modeStore = store

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(buildSessionActivityIntent())
            .setCallback(SessionCallback())
            .build()

        // 动态注册：Android 8+ 静态广播受限，且静态注册会在「没放歌」时平白拉起进程。
        val receiver = BecomingNoisyReceiver(player)
        receiver.register(this)
        noisyReceiver = receiver

        Log.i(TAG, "onCreate done: wakeMode=NETWORK, audioFocus=true, mode=${store.current}")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * 最近任务被划掉。
     *
     * 只有在**没在播放**时才停掉服务（否则残留一条没人管的通知 + 一个空服务）；
     * 正在播放时保持存活，让用户「划掉卡片」不等于「停止听歌」（REQ-P0-13）。
     *
     * 说明：架构文档 §6 T03 要点 2 的原文是「onTaskRemoved / onDestroy 里 player.release()」，
     * 这里把 onTaskRemoved 的语义收敛为「没在播就 stopSelf()」——
     * release 统一发生在 onDestroy，避免正在播放时被划卡片直接掐断。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val player = exoPlayer
        val playing = player != null &&
            player.playbackState != Player.STATE_IDLE &&
            player.playWhenReady &&
            player.mediaItemCount > 0

        if (playing) {
            Log.i(TAG, "onTaskRemoved: still playing, keep service alive")
        } else {
            Log.i(TAG, "onTaskRemoved: not playing -> stopSelf")
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: releasing receiver / player / session")
        noisyReceiver?.unregister(this)
        noisyReceiver = null

        val player = exoPlayer
        exoPlayer = null
        if (player != null) {
            runCatching { player.release() }
                .onFailure { throwable -> Log.w(TAG, "player.release failed", throwable) }
        }

        val session = mediaSession
        mediaSession = null
        if (session != null) {
            runCatching { session.release() }
                .onFailure { throwable -> Log.w(TAG, "session.release failed", throwable) }
        }

        modeStore = null
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    /**
     * 播完自动下一首（T03 要点 4）。
     *
     * - 单曲循环（`REPEAT_MODE_ONE`）：播放器自己循环，这里不动手；
     * - 其余（`REPEAT_MODE_ALL` / 随机）：`seekToNextMediaItem()`。
     *   列表循环下播到最后一首时 Media3 会回卷到第 0 首，正好是「列表循环」语义；
     *   试听片段播完同样走这里 → 自动切下一首（T03 验收项）。
     */
    private val playbackStateListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                // 有歌真正播起来了，之前的连续受限跳过作废。
                if (consecutiveRestrictedSkips > 0) {
                    consecutiveRestrictedSkips = 0
                    Log.i(TAG, "READY -> reset restricted-skip counter")
                }
                return
            }
            if (playbackState != Player.STATE_ENDED) return

            consecutiveRestrictedSkips = 0
            val player = exoPlayer ?: return
            if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                Log.i(TAG, "STATE_ENDED with REPEAT_MODE_ONE -> keep looping current item")
                return
            }
            if (player.hasNextMediaItem()) {
                Log.i(TAG, "STATE_ENDED -> seekToNextMediaItem")
                player.seekToNextMediaItem()
            } else {
                Log.i(TAG, "STATE_ENDED without next item -> stay ended")
            }
        }

        /**
         * **受限歌曲自动跳过**（用户需求：上游付费/试听限制导致不能播放时自动切下一首）。
         *
         * 判据（全部走结构化字段，不解析 message 文本）：
         * 1. `REPEAT_MODE_ONE` → 不跳（用户明确要单曲重复）；
         * 2. 队列只有 1 首 → 无可跳，保留错误卡片；
         * 3. 当前曲目在 [ResolvedTrackStore] 里有**取链失败**记录且错误码是
         *    `E_NO_SOURCE`（`AppError.PlaySourceUnavailable`，即上游拒源/全链失败）；
         *    取链成功后的真网络故障**没有**失败记录 → 不跳，保留错误卡片供排查；
         * 4. 连续跳过次数未达队列长度（防整个队列都被拒源时无限快跳，最后停在错误卡片上）。
         *
         * ExoPlayer 报错后进入 `STATE_IDLE`，必须 `seekToNextMediaItem()` + `prepare()`
         * 才会重新打开下一首的数据源。
         */
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "player error: code=${error.errorCode} ${error.message}")
            val player = exoPlayer ?: return
            if (player.repeatMode == Player.REPEAT_MODE_ONE) return
            if (player.mediaItemCount <= 1) return

            val uid = player.currentMediaItem?.mediaId ?: return
            val failure = AppGraph.resolvedStore.failureOf(uid) ?: return
            if (failure.errorCode != AppError.PlaySourceUnavailable.code) return

            if (consecutiveRestrictedSkips >= player.mediaItemCount) {
                Log.w(
                    TAG,
                    "restricted-skip budget exhausted (${player.mediaItemCount}) " +
                        "-> keep error card for uid=$uid"
                )
                return
            }
            consecutiveRestrictedSkips++

            val title = AppGraph.trackStore.get(uid)?.title ?: uid
            Toast.makeText(
                this@PlaybackService,
                getString(R.string.msg_auto_skip_restricted, title),
                Toast.LENGTH_SHORT
            ).show()
            Log.i(
                TAG,
                "restricted uid=$uid -> auto skip " +
                    "($consecutiveRestrictedSkips/${player.mediaItemCount})"
            )
            runCatching {
                player.seekToNextMediaItem()
                player.prepare()
            }.onFailure { throwable ->
                Log.w(TAG, "auto skip after restricted error failed", throwable)
            }
        }
    }

    /**
     * MediaSession 回调。
     *
     * 本 App 的播放指令全部来自**自己进程内的** `PlaybackController`，
     * 且下发的 `MediaItem` 已由 [MediaItemFactory] 组装好完整 uri + 元数据，
     * 因此不需要重写 `onAddMediaItems`（那套是给 `MediaBrowser` / 语音助手
     * 「只给 mediaId 让你自己补全」的场景用的）。留空实现即可满足 `setCallback` 契约。
     */
    private inner class SessionCallback : MediaSession.Callback

    /**
     * Android 8+ 通知渠道。
     *
     * 渠道名直接复用 `app_name`（"LeLeMusic"），**不新增字符串资源**——
     * 一是渠道名展示的就是应用名，语义正确；二是避免与 T04 的 `strings.xml` 改动冲突。
     * `IMPORTANCE_LOW` = 不响铃不震动，符合音乐播放通知的预期。
     */
    private fun createPlaybackNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager == null) {
            Log.w(TAG, "NotificationManager unavailable, skip channel creation")
            return
        }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.app_name)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
        Log.i(TAG, "notification channel created: $CHANNEL_ID")
    }

    /**
     * 点击通知栏 / 锁屏控件回到 App。
     *
     * `FLAG_IMMUTABLE` 是 Android 12（targetSdk 34 强制）的要求；
     * 取不到启动 Intent 时返回 null（`MediaSession.Builder.setSessionActivity` 接受 null）。
     */
    private fun buildSessionActivityIntent(): PendingIntent {
        val launchIntent = checkNotNull(packageManager?.getLaunchIntentForPackage(packageName)) {
            "No launch intent for package $packageName"
        }
        return checkNotNull(
            PendingIntent.getActivity(
                this,
                REQUEST_CODE_SESSION_ACTIVITY,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ) { "Failed to create session activity PendingIntent" }
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val CHANNEL_ID = "lelemusic_playback"
        const val REQUEST_CODE_SESSION_ACTIVITY = 1001
    }
}

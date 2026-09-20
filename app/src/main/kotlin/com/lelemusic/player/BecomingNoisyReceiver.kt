package com.lelemusic.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.Player

/**
 * 耳机拔出（`ACTION_AUDIO_BECOMING_NOISY`）→ 暂停播放（REQ-P0-13）。
 *
 * **为什么是动态注册而不是 Manifest 静态注册**：
 * - Android 8+ 对静态注册的隐式广播做了限制，`ACTION_AUDIO_BECOMING_NOISY`
 *   虽在豁免清单内，但静态注册的 Receiver 会**拉起进程**，
 *   在「没在播放」时也会平白唤醒一次 App，属于典型的电量浪费；
 * - 动态注册的生命周期与 `PlaybackService` 严格一致：
 *   服务活着（= 有可能在放歌）才收广播，服务销毁即注销，零泄漏。
 *
 * 因此 Manifest 里**没有**、也不应该有对应的 `<receiver>` 标签（架构文档 附录 B）。
 *
 * 用法（`PlaybackService`）：
 * ```kotlin
 * private var noisyReceiver: BecomingNoisyReceiver? = null
 * // onCreate:  val r = BecomingNoisyReceiver(player); r.register(this); noisyReceiver = r
 * // onDestroy: noisyReceiver?.unregister(this); noisyReceiver = null
 * ```
 *
 * 注：`ExoPlayer` 自带一套 `handleAudioBecomingNoisy`（默认开），
 * `PlaybackService` 里已显式 `setHandleAudioBecomingNoisy(false)`，
 * 由本类**单点**负责，避免两套逻辑互相打架。
 *
 * @param player 暂停动作作用到的播放器（`ExoPlayer` 或 `MediaController` 均可）
 */
class BecomingNoisyReceiver(private val player: Player) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        if (action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
        Log.i(TAG, "audio becoming noisy -> pause playback")
        // 只在「确实在响」的时候暂停，避免把用户手动暂停的状态改坏；
        // 跑在 Main 线程（动态注册的 Receiver 默认走主线程 Looper），符合 Media3 的线程要求。
        runCatching {
            if (player.playWhenReady) {
                player.pause()
            }
        }.onFailure { throwable ->
            Log.w(TAG, "pause on becoming-noisy failed", throwable)
        }
    }

    /** 动态注册；重复注册由 `runCatching` 兜住，不会因异常打断 Service 启动 */
    fun register(context: Context) {
        runCatching {
            context.applicationContext.registerReceiver(this, INTENT_FILTER)
            Log.i(TAG, "registered")
        }.onFailure { throwable ->
            Log.w(TAG, "registerReceiver failed", throwable)
        }
    }

    /** 动态注销；已经注销过或从未注册成功也不会抛异常打断 `onDestroy` */
    fun unregister(context: Context) {
        runCatching {
            context.applicationContext.unregisterReceiver(this)
            Log.i(TAG, "unregistered")
        }.onFailure { throwable ->
            Log.w(TAG, "unregisterReceiver failed", throwable)
        }
    }

    private companion object {
        const val TAG = "BecomingNoisyReceiver"

        /** 复用同一个 IntentFilter 实例：注册/注销成对调用，过滤条件恒定 */
        val INTENT_FILTER: IntentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
    }
}

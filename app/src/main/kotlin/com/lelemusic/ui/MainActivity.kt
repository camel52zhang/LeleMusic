package com.lelemusic.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.lelemusic.player.NotificationPermission
import com.lelemusic.ui.common.EXPANDED_MIN_WIDTH_DP
import com.lelemusic.ui.common.LocalExpandedScreen
import com.lelemusic.ui.common.LocalWindowWidthDp
import com.lelemusic.ui.common.PHONE_MAX_SMALLEST_WIDTH_DP
import com.lelemusic.ui.theme.LeLeMusicTheme

/**
 * 唯一 Activity。
 *
 * - 方向策略（2026-09-12 平板适配）：手机（最短边 < 600dp）保持竖屏锁定——
 *   由这里动态 `requestedOrientation`，Manifest 只声明 `fullUser`；
 *   平板（小米平板 5 Pro 最短边约 927dp）不锁方向，横竖屏跟随系统自动旋转开关。
 *   旋转会重建 Activity：`PlayerViewModel` 是 Activity 作用域 ViewModel，跨重建存活，
 *   播放由前台 Service 承载，不中断（满足 PRD REQ-P0-08 的精神）。
 * - Android 13（API 33）及以上动态申请通知权限——没有它，
 *   `MediaSessionService` 的媒体通知不会显示（架构文档 T01 要点 4）。
 *
 * 通知权限统一走 [NotificationPermission]（T03 的封装），
 * 不在 Activity 里另写一套 `registerForActivityResult`，避免两处逻辑漂移。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须在 setContent 之前：媒体通知权限缺失会导致后台播放时通知栏完全空白
        NotificationPermission.requestIfNeeded(this)

        // 手机锁竖屏（等价于旧 Manifest portrait，但平板不受影响）
        if (resources.configuration.smallestScreenWidthDp < PHONE_MAX_SMALLEST_WIDTH_DP) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // Expanded 档（平板横竖屏均满足）全局下发，播放页双栏 / 列表限宽都读它；
        // 窗口宽度一并下发（小米平板 5 Pro 密度 340：横屏约 1204dp、竖屏约 753dp）
        val windowWidthDp = resources.configuration.screenWidthDp
        val isExpanded = windowWidthDp >= EXPANDED_MIN_WIDTH_DP

        setContent {
            LeLeMusicTheme {
                CompositionLocalProvider(
                    LocalExpandedScreen provides isExpanded,
                    LocalWindowWidthDp provides windowWidthDp
                ) {
                    RootNav()
                }
            }
        }
    }
}

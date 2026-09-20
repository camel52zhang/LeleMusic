package com.lelemusic.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Android 13（API 33）+ 的通知运行时权限（REQ-P0-13 的前置条件）。
 *
 * **为什么必须有它**：Manifest 里声明 `POST_NOTIFICATIONS` 只是「有权申请」，
 * Android 13+ 默认**拒绝**。不弹一次系统授权框，播放通知**一条都不会出现**，
 * 「通知栏可播放/暂停/切歌」这条 P0 验收直接挂掉。
 *
 * 用法（T04 在 `MainActivity.onCreate` 里调用一次即可）：
 * ```kotlin
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     NotificationPermission.requestIfNeeded(this)
 *     ...
 * }
 * ```
 *
 * 只依赖 `androidx.core:core-ktx`（已是显式依赖），不引入任何新库。
 */
object NotificationPermission {

    /** 系统授权回调的 requestCode */
    const val REQUEST_CODE = 2001

    /**
     * 是否已获得通知权限。
     *
     * Android 12L（API 32）及以下永远返回 true——那时还没有这个运行时权限。
     */
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 未授权时弹出系统授权框；已授权则直接返回（重复弹框会被系统判定为骚扰）。
     *
     * @param activity 宿主 Activity（`requestPermissions` 只能由 Activity 发起）
     * @param requestCode 回调标识，默认 [REQUEST_CODE]
     */
    fun requestIfNeeded(activity: Activity, requestCode: Int = REQUEST_CODE) {
        if (isGranted(activity)) return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            requestCode
        )
    }

    /** 解析 `onRequestPermissionsResult` 的回调结果 */
    fun isGrantedResult(grantResults: IntArray): Boolean =
        grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
}

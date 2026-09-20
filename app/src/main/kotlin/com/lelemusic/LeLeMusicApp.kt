package com.lelemusic

import android.app.Application
import android.util.Log
import com.lelemusic.core.common.AppDispatchers
import com.lelemusic.core.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application 入口。
 *
 * 两件事：
 * 1. 初始化手工 DI 容器 [AppGraph]；
 * 2. 后台发起启动探活 `SourceHealthRepository.probeAll()`。
 *
 * 探活**刻意不阻塞首屏**：它要发 3 个网络请求，同步跑会拖慢冷启动。
 * 这里用一次性 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 发起——
 * - 用 [SupervisorJob] 是为了让三个平台的探活互不影响（一个崩了不影响另外两个）；
 * - 不保存到字段是因为 Application 生命周期与进程等长，这个 Scope 不需要被取消，
 *   存下来反而会被 Lint 提示「未使用」。
 *
 * 探活结果经 StateFlow 异步推给榜单页；没探完之前平台 Tab 全部正常可点（宁可漏报，不可误报禁用）。
 */
class LeLeMusicApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        Log.i(TAG, "LeLeMusicApp onCreate: AppGraph initialized")

        CoroutineScope(SupervisorJob() + AppDispatchers.IO).launch {
            AppGraph.sourceHealthRepository.probeAll()
        }
    }

    private companion object {
        const val TAG = "LeLeMusicApp"
    }
}

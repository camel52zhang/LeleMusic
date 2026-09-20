package com.lelemusic.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.lelemusic.ui.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌面歌词（悬浮窗）控制器——2026-09-12 用户需求：「LRC 打开是激活桌面歌词」。
 *
 * **实现方式**：[WindowManager] + `TYPE_APPLICATION_OVERLAY` 悬浮 TextView。
 * 项目 minSdk 26，`TYPE_APPLICATION_OVERLAY`（API 26+）无版本分支；悬浮窗
 * 不接收触摸（`FLAG_NOT_TOUCHABLE`），纯展示，不需要前台服务——
 * 歌词行由播放页（PlayerScreen）随进度推进时调用 [updateLine] 推送。
 *
 * **权限**：需要 `SYSTEM_ALERT_WINDOW`（清单已声明）；用户须在系统设置里
 * 手动授予「显示在其他应用上层」（[Settings.canDrawOverlays] 判断），
 * 未授予时 UI 层引导跳转 [Settings.ACTION_MANAGE_OVERLAY_PERMISSION]。
 *
 * 生命周期：App 进程存活期间悬浮窗持续显示；开关状态是内存级（不持久化），
 * 进程被杀后悬浮窗随进程消失，重启后默认关闭——可接受。
 */
object DesktopLyricController {

    /** 默认垂直位置（px，底部偏移）：避开手势条与通知栏 */
    private const val DEFAULT_Y = 420

    /** 拖动上界（px）：不越过屏幕顶（底部偏移越大越靠上，给个足够大的钳制值） */
    private const val MAX_Y = 4000

    /** 拖动下界（px）：不让歌词底边贴到手势条以下 */
    private const val MIN_Y = 0

    private val _enabled = MutableStateFlow(false)

    /** 桌面歌词当前是否开启（UI 用它切换 LRC 图标的斜杠/高亮态） */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _line = MutableStateFlow("")

    /** 当前应显示的歌词行（空串 = 暂无可展示内容） */
    val line: StateFlow<String> = _line.asStateFlow()

    /**
     * 双击歌词请求（2026-09-12 用户需求：**双击桌面歌词进入歌词界面**）。
     * 播放页收集该流后把 HorizontalPager 平滑切到歌词页。
     * `extraBufferCapacity = 1`：双击发生在主线程触摸回调里，即时发射不挂起。
     */
    val doubleTapRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var windowManager: WindowManager? = null
    private var lyricView: TextView? = null

    /** 歌词窗垂直位置（px，底部偏移）。拖动后更新，重开时保持用户摆放的位置 */
    private var positionY: Int = DEFAULT_Y

    /** 是否已授予「显示在其他应用上层」权限 */
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context.applicationContext)

    /**
     * 开关桌面歌词。**调用前须先用 [canDrawOverlays] 确认权限**，
     * 未授权时直接调用不会崩溃（show 内部兜底 no-op）但也不会生效。
     */
    fun toggle(context: Context) {
        if (_enabled.value) {
            hide(context)
        } else {
            show(context)
        }
    }

    /** 显示悬浮歌词窗（权限不足时静默不生效，UI 层负责引导授权） */
    fun show(context: Context) {
        val appContext = context.applicationContext
        if (!canDrawOverlays(appContext)) return

        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (lyricView != null) {
            _enabled.value = true
            return
        }

        val view = TextView(appContext).apply {
            // 白字黑影：任意壁纸上都可读（桌面歌词的标准做法）
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 1.5f, 1.5f, Color.BLACK)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(24, 8, 24, 8)
            text = _line.value
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // minSdk 26，无需判断版本
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE：不抢按键/输入焦点；NOT_TOUCH_MODAL：歌词区域外的触摸
            // 全部穿透给下层窗口——歌词区域内的触摸则被本 view 消费用于拖动
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 底部居中；y 为用户上次拖放到的位置（首次用默认值）
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = positionY
        }

        try {
            wm.addView(view, params)
            attachDragHandler(view, params, wm)
            windowManager = wm
            lyricView = view
            _enabled.value = true
        } catch (_: Exception) {
            // 极少数 ROM 在权限判定通过后 addView 仍可能抛异常；保持关闭态即可
            _enabled.value = false
        }
    }

    /**
     * 给歌词 view 挂**上下拖动**手势（2026-09-12 用户需求：歌词位置可上下移动）。
     *
     * 只取 `rawY` 增量调 [WindowManager.updateViewLayout]，x 保持居中不动；
     * 拖动中实时写回 [positionY]，关闭再开不丢位置。顶部越界钳到 0。
     *（必须在主线程调用；WindowManager 触摸回调本身就在主线程。）
     */
    private fun attachDragHandler(
        view: TextView,
        params: WindowManager.LayoutParams,
        wm: WindowManager
    ) {
        var touchStartRawY = 0f
        var dragStartY = 0

        // 2026-09-12：双击歌词 → 回 App 进歌词页（拖动与双击共用一套触摸流，
        // GestureDetector 只识别双击、不影响 MOVE 的拖动逻辑）
        val detector = GestureDetector(
            view.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val ctx = view.context
                    // App 可能在后台：先以 singleTop 拉回前台，再由播放页切到歌词页
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            )
                        }
                    )
                    doubleTapRequests.tryEmit(Unit)
                    return true
                }
            }
        )

        view.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartRawY = event.rawY
                    dragStartY = params.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.y = (dragStartY + (touchStartRawY - event.rawY))
                        .toInt()
                        .coerceIn(MIN_Y, MAX_Y)
                    positionY = params.y
                    wm.updateViewLayout(v, params)
                    true
                }

                else -> false
            }
        }
    }

    /** 隐藏并移除悬浮歌词窗 */
    fun hide(context: Context) {
        val view = lyricView ?: run {
            _enabled.value = false
            return
        }
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
            // view 已被系统回收等场景：忽略，状态仍收敛为关闭
        }
        lyricView = null
        windowManager = null
        _enabled.value = false
    }

    /**
     * 推送当前歌词行（播放页在进度/歌词变化时调用；必须在主线程）。
     * 未开启时只缓存文本，等 show 时一并显示。
     */
    fun updateLine(text: String) {
        val trimmed = text.trim()
        if (trimmed == _line.value) return
        _line.value = trimmed
        lyricView?.text = trimmed
    }
}

package com.lelemusic.ui.player

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lelemusic.R
import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.formatClock
import com.lelemusic.core.common.normalizeToHttps
import com.lelemusic.core.common.orUnknown
import com.lelemusic.model.Lyric
import com.lelemusic.model.PlaybackMode
import com.lelemusic.model.Song
import com.lelemusic.ui.common.HeartAddGlyph
import com.lelemusic.ui.common.LrcGlyph
import com.lelemusic.ui.common.LocalExpandedScreen
import com.lelemusic.ui.common.NoteGlyph
import com.lelemusic.ui.common.PauseGlyph
import com.lelemusic.ui.common.PlayGlyph
import com.lelemusic.ui.common.RepeatGlyph
import com.lelemusic.ui.common.RepeatOneGlyph
import com.lelemusic.ui.common.ShuffleGlyph
import com.lelemusic.ui.common.SkipNextGlyph
import com.lelemusic.ui.common.SkipPreviousGlyph
import com.lelemusic.ui.common.TrialChip
import com.lelemusic.ui.common.appErrorText
import com.lelemusic.ui.common.glyphContentDescription
import com.lelemusic.ui.library.AddToPlaylistDialog
import com.lelemusic.ui.library.LibraryViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 全屏播放器页（PRD 5.4）。
 *
 * 结构自上而下：返回条 → HorizontalPager（第 0 页：大封面 + 标题 + 错误卡片；
 * 第 1 页：歌词面板，左右滑动切换）→ 进度条 → 三大控制键 → 倍速/模式（仅封面页）。
 *
 * 所有状态都来自 [PlayerViewModel.uiState]，页面本身**不持有任何播放状态**，
 * 因此从迷你条进来、从榜单点进来、系统返回键退出，播放都不会中断。
 *
 * @param viewModel 由 `RootNav` 在 Activity 作用域创建并传入
 * @param onBack    收起播放器（返回榜单页）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val lyricUi by viewModel.lyric.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val context = LocalContext.current

    // 快捷加歌：待加入歌单的当前歌曲（入口在「列表循环」右侧的「＋」）
    var quickAddSong by remember { mutableStateOf<Song?>(null) }

    // 2026-09-12：LRC = 桌面歌词开关（悬浮窗歌词），不是切换歌词页
    val desktopLyricOn by DesktopLyricController.enabled.collectAsState()

    // 封面 / 歌词 改为 HorizontalPager 左右滑动切换（2026-09-11 用户需求）。
    // 第 0 页 = 封面，第 1 页 = 歌词；次要控件（倍速/模式）只在封面页展示。
    // 注：foundation Pager 在本 BOM 版本仍是 Experimental API；旋转重建后回到封面页，可接受。
    val pagerState = rememberPagerState(initialPage = 0) { PAGE_COUNT }
    val pagerScope = rememberCoroutineScope()
    val onCoverPage = pagerState.currentPage == 0

    // 本地歌词文件选择器（.lrc / .srt），选中后交给 ViewModel 解析并绑定当前歌曲。
    val localLyricLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.loadLocalLyric(it) } }

    // 2026-09-12：桌面歌词行——随进度推进把当前行推给悬浮窗（未开启时只缓存不展示）。
    // 进度每 500ms 一跳、indexAt 线性比较开销可忽略（与 LyricLines 内同款算法）。
    val currentLyricLine: String = when (val l = lyricUi) {
        is LyricUi.Ready -> {
            val index = l.lyric.indexAt(uiState.positionMs)
            if (index >= 0) l.lyric.lines.getOrNull(index)?.text.orEmpty() else ""
        }

        else -> ""
    }
    LaunchedEffect(currentLyricLine) {
        DesktopLyricController.updateLine(currentLyricLine)
    }

    // 2026-09-12：双击桌面歌词 → 拉起 App（控制器里已做）并平滑切到歌词页；
    // 进歌词页后悬浮歌词自动隐藏（用户需求：App 内有完整歌词页，悬浮窗多余）
    LaunchedEffect(Unit) {
        DesktopLyricController.doubleTapRequests.collect {
            DesktopLyricController.hide(context)
            pagerScope.launch { pagerState.animateScrollToPage(1) }
        }
    }

    // 三种模式的中文标签必须在**组合期**解析完（`stringResource` 不能在点击事件里调），
    // 存成 Map 后点击时按返回的模式取值即可。
    val modeLabels: Map<PlaybackMode, String> = mapOf(
        PlaybackMode.LIST_LOOP to stringResource(id = R.string.label_mode_list_loop),
        PlaybackMode.SINGLE_LOOP to stringResource(id = R.string.label_mode_single_loop),
        PlaybackMode.SHUFFLE to stringResource(id = R.string.label_mode_shuffle)
    )

    // 桌面歌词开关逻辑两个布局分支共用（开/关悬浮窗，未授权引导去系统设置）
    val toggleDesktopLyric: () -> Unit = {
        val appContext = context.applicationContext
        if (DesktopLyricController.canDrawOverlays(appContext)) {
            DesktopLyricController.toggle(appContext)
        } else {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.msg_desktop_lyric_permission),
                Toast.LENGTH_LONG
            ).show()
            appContext.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${appContext.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    if (LocalExpandedScreen.current) {
        // ------------------------------------------------------------------
        // 平板 Expanded（横/竖屏均 ≥840dp）：双栏布局——左栏封面+全套控制，右栏完整歌词。
        // 全部子组件复用竖屏实现（TopBar/BigCover/TitleBlock/FailureCard/…），只重排版。
        // ------------------------------------------------------------------
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(color = MaterialTheme.colorScheme.surface)
                .padding(horizontal = 24.dp)
        ) {
            // 左栏：封面 + 控制（图 004 反馈：整体与中缝分隔线拉开 24dp，避免曲名/进度条贴线）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 24.dp)
            ) {
                TopBar(
                    song = uiState.song,
                    connected = uiState.connected,
                    onBack = onBack
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    BigCover(
                        song = uiState.song,
                        // 双栏下高度是稀缺资源：封面由可用高度驱动取正方形（宽度富余）
                        modifier = Modifier.fillMaxHeight()
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                TitleBlock(song = uiState.song, isTrial = uiState.isTrial)

                Spacer(modifier = Modifier.height(8.dp))

                FailureCard(
                    uiState = uiState,
                    onRetry = { viewModel.retry() },
                    onOpenInPlatform = { song -> viewModel.openInPlatform(song) }
                )

                InlineLyricLine(
                    line = currentLyricLine,
                    // 歌词已在右栏完整展示，内嵌行无需再跳转
                    onClick = {},
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                ProgressSection(
                    positionMs = uiState.positionMs,
                    durationMs = uiState.durationMs,
                    onSeek = { positionMs -> viewModel.seekTo(positionMs) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                ControlRow(
                    isPlaying = uiState.isPlaying,
                    onPrevious = { viewModel.skipPrevious() },
                    onToggle = { viewModel.togglePlayPause() },
                    onNext = { viewModel.skipNext() }
                )

                Spacer(modifier = Modifier.height(14.dp))

                SpeedRow(
                    speed = speed,
                    onSlower = { viewModel.setSpeed(speed - SPEED_STEP) },
                    onFaster = { viewModel.setSpeed(speed + SPEED_STEP) },
                    onReset = { viewModel.setSpeed(1f) }
                )

                Spacer(modifier = Modifier.height(14.dp))

                ModeRow(
                    mode = uiState.mode,
                    label = modeLabels[uiState.mode].orEmpty(),
                    // 2026-09-12 用户反馈：切模式不再弹 Toast——模式键图标/文字本身已实时变化
                    onCycle = { viewModel.cyclePlaybackMode() },
                    desktopLyricOn = desktopLyricOn,
                    onToggleDesktopLyric = toggleDesktopLyric,
                    // 2026-09-12：行内快捷加歌入口从每首歌右侧统一收编到这里（列表循环右侧）
                    addTarget = uiState.song,
                    onAddToPlaylist = { song -> quickAddSong = song }
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            // 中缝分隔线（上下留空的细线）
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .padding(vertical = 32.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            // 右栏：完整歌词面板（「返回封面」在双栏下无意义，传 null 隐藏；
            // 图 004 反馈：与中缝分隔线拉开 24dp）
            LyricsPanel(
                song = uiState.song,
                lyricUi = lyricUi,
                positionMs = uiState.positionMs,
                onSeekTo = { viewModel.seekTo(it) },
                onMatchOnline = { viewModel.matchOnlineLyric() },
                onBackToCover = null,
                onPickLocal = { localLyricLauncher.launch(arrayOf("*/*")) },
                onClearLocal = { viewModel.clearLocalLyric() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 24.dp)
            )
        }
    } else {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.surface)
            .padding(horizontal = 24.dp)
    ) {
        TopBar(
            song = uiState.song,
            connected = uiState.connected,
            onBack = onBack
        )

        // 歌词页把曲名行上移收紧（16dp → 4dp），视觉上与顶栏更协调；
        // 封面页保持原间距给封面留呼吸感（2026-09-11 用户反馈）。
        Spacer(modifier = Modifier.height(if (onCoverPage) 16.dp else 4.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            if (page == 0) {
                // 图 003 修复：Medium 竖屏（平板竖屏 753dp 限宽 680）下封面按宽度撑满 680dp，
                // 高度超出 pager 视口，曲名下方的歌手行被底边裁切。封面边长改为
                // min(可用宽, 可用高 - 曲名区预留)：
                //   手机竖屏：宽 < 可用高 → 宽度驱动，与旧行为一致；
                //   平板竖屏：可用高更小 → 高度驱动，封面自动缩小让出曲名区。
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val titleReserve = 110.dp // 封面→曲名 20 + 标题 ~34 + 歌手 ~20 + 间距 8 + 余量
                    val coverEdge = minOf(maxWidth, maxHeight - titleReserve)
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            BigCover(
                                song = uiState.song,
                                onClick = { pagerScope.launch { pagerState.animateScrollToPage(1) } },
                                modifier = Modifier.size(coverEdge)
                            )
                        }

                        // 2026-09-12 图 027：封面页竖向空间紧张（歌手行曾被挤压裁切），
                        // 封面→曲名→错误卡 的间距整体收紧（24→16、12→8）；
                        // an 轮微调：曲名下方各模块放宽后，封面→曲名回调到 20dp 保持呼吸感
                        Spacer(modifier = Modifier.height(20.dp))

                        TitleBlock(song = uiState.song, isTrial = uiState.isTrial)

                        Spacer(modifier = Modifier.height(8.dp))

                        FailureCard(
                            uiState = uiState,
                            onRetry = { viewModel.retry() },
                            onOpenInPlatform = { song -> viewModel.openInPlatform(song) }
                        )
                    }
                }
            } else {
                LyricsPanel(
                    song = uiState.song,
                    lyricUi = lyricUi,
                    positionMs = uiState.positionMs,
                    onSeekTo = { viewModel.seekTo(it) },
                    onMatchOnline = { viewModel.matchOnlineLyric() },
                    onBackToCover = {
                        pagerScope.launch { pagerState.animateScrollToPage(0) }
                    },
                    onPickLocal = { localLyricLauncher.launch(arrayOf("*/*")) },
                    onClearLocal = { viewModel.clearLocalLyric() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 2026-09-12 图 025 修复：内嵌歌词行放主 Column（pager 与进度条之间）——
        // 原先放在 pager 第 0 页内部会被 pager 底边裁切（图 025 歌词下半被吃掉）。
        // 视觉位置不变（歌词页隐藏），且不在 pager 内受封面高度挤压。
        // 2026-09-12 微调：pager（歌手名）与歌词行贴近（8→4），歌词行下方模块间距放宽
        if (onCoverPage) {
            InlineLyricLine(
                line = currentLyricLine,
                onClick = { pagerScope.launch { pagerState.animateScrollToPage(1) } },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        ProgressSection(
            positionMs = uiState.positionMs,
            durationMs = uiState.durationMs,
            onSeek = { positionMs -> viewModel.seekTo(positionMs) }
        )

        // 2026-09-12 an/ap 两轮微调后定稿：进度→控制键 10dp
        Spacer(modifier = Modifier.height(10.dp))

        ControlRow(
            isPlaying = uiState.isPlaying,
            onPrevious = { viewModel.skipPrevious() },
            onToggle = { viewModel.togglePlayPause() },
            onNext = { viewModel.skipNext() }
        )

        // 倍速 / 模式是次要控件，只在封面页展示：歌词页把这两行让给歌词区，
        // 需要时左滑回封面页即可操作（2026-09-11 用户需求：增大歌词区占比）。
        if (onCoverPage) {
            Spacer(modifier = Modifier.height(14.dp))

            SpeedRow(
                speed = speed,
                onSlower = { viewModel.setSpeed(speed - SPEED_STEP) },
                onFaster = { viewModel.setSpeed(speed + SPEED_STEP) },
                onReset = { viewModel.setSpeed(1f) }
            )

            Spacer(modifier = Modifier.height(14.dp))

            ModeRow(
                mode = uiState.mode,
                label = modeLabels[uiState.mode].orEmpty(),
                onCycle = { viewModel.cyclePlaybackMode() },
                // 2026-09-12：LRC = 桌面歌词开关；未授予悬浮窗权限时引导去系统设置
                desktopLyricOn = desktopLyricOn,
                onToggleDesktopLyric = toggleDesktopLyric,
                // 2026-09-12：行内快捷加歌入口从每首歌右侧统一收编到这里（列表循环右侧）
                addTarget = uiState.song,
                onAddToPlaylist = { song -> quickAddSong = song }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
    } // else：竖屏（手机）布局，内容与适配前完全一致

    // 快捷加歌对话框：把当前播放的歌加入「我的歌单」（可新建或选已有）
    quickAddSong?.let { song ->
        AddToPlaylistDialog(
            songs = listOf(song),
            viewModel = libraryViewModel,
            onDismiss = { quickAddSong = null }
        )
    }
}

// ---------------------------------------------------------------------------
// 顶部返回条
// ---------------------------------------------------------------------------

@Composable
private fun TopBar(
    song: Song?,
    connected: Boolean,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .glyphContentDescription(stringResource(id = R.string.cd_close_player))
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (song != null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(
                    text = stringResource(
                        id = R.string.label_source_from,
                        song.platform.displayName
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.size(1.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        if (!connected) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
        } else {
            Spacer(modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 大封面
// ---------------------------------------------------------------------------

@Composable
private fun BigCover(
    song: Song?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(20.dp)
    // 尺寸由调用方决定（竖屏 fillMaxWidth 撑满、平板双栏 fillMaxHeight 取可用高度的正方形），
    // 这里只固定 1:1 比例——平板横屏宽度富余，若仍按宽度驱动封面会超出可视高度。
    val base = modifier
        .aspectRatio(1f)
        .clip(shape)
        .background(color = MaterialTheme.colorScheme.surfaceVariant)
    val clickable = if (onClick != null) {
        base.clickable(onClick = onClick)
    } else {
        base
    }
    Box(
        modifier = clickable,
        contentAlignment = Alignment.Center
    ) {
        val cover = song?.coverUrl
        if (cover.isNullOrBlank()) {
            NoteGlyph(
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
        } else {
            AsyncImage(
                model = normalizeToHttps(cover),
                contentDescription = stringResource(id = R.string.cd_album_cover),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 标题 + 副标题 + 试听胶囊
// ---------------------------------------------------------------------------

@Composable
private fun TitleBlock(song: Song?, isTrial: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = song?.title.orUnknown(stringResource(id = R.string.label_no_playing)),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start
        )
        if (song != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        id = R.string.label_artist_album,
                        song.artist.orUnknown(stringResource(id = R.string.label_unknown_artist)),
                        song.album.orUnknown(stringResource(id = R.string.label_unknown_album))
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isTrial) {
                    Spacer(modifier = Modifier.width(8.dp))
                    TrialChip()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 失败卡片（取链失败 / 播放器报错）
// ---------------------------------------------------------------------------

@Composable
private fun FailureCard(
    uiState: PlayerUiState,
    onRetry: () -> Unit,
    onOpenInPlatform: (Song) -> Unit
) {
    val song = uiState.song ?: return
    val appError = uiState.error
    val failure = uiState.failure
    if (appError == null && failure == null) return

    val message = if (failure != null) {
        // failure.strategyId / errorCode 只进日志（自检台用），用户只需要一句人话
        failure.message.ifBlank { appErrorText(AppError.PlaySourceUnavailable) }
    } else {
        appErrorText(appError ?: AppError.Unknown)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.error_no_source),
                style = MaterialTheme.typography.titleMedium
            )
            if (message.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // ExoPlayer 原始错误码：这句「网络异常」可能是取链失败被包成 IOException 后的误报，
            // 只有原始码 + cause 能区分。用户没有 adb，靠截图把这一行回传给我们。
            val detail = uiState.playerErrorDetail
            if (!detail.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onOpenInPlatform(song) }) {
                    Text(
                        text = stringResource(
                            id = R.string.action_open_in_platform,
                            song.platform.displayName
                        )
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onRetry) {
                    Text(text = stringResource(id = R.string.action_retry))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 进度条
// ---------------------------------------------------------------------------

@Composable
private fun ProgressSection(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    // 拖拽期间用本地值渲染（PRD：拖拽时时间实时预览），松手才真正 seek，
    // 否则每一帧都会往 MediaController 发一次 seek。
    var draggingValue by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val maxValue = durationMs.toFloat().coerceAtLeast(1f)
    val shownValue = if (dragging) draggingValue else positionMs.toFloat()
    val safeValue = shownValue.coerceIn(0f, maxValue)
    val endText = if (durationMs > 0L) {
        formatClock(durationMs)
    } else {
        stringResource(id = R.string.label_unknown_duration)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = safeValue,
            onValueChange = { newValue ->
                dragging = true
                draggingValue = newValue
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .glyphContentDescription(stringResource(id = R.string.cd_progress)),
            enabled = durationMs > 0L,
            valueRange = 0f..maxValue,
            onValueChangeFinished = {
                dragging = false
                onSeek(draggingValue.toLong())
            },
            colors = SliderDefaults.colors()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = formatClock(safeValue.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = endText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 上一首 / 播放暂停 / 下一首
// ---------------------------------------------------------------------------

@Composable
private fun ControlRow(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevious,
            modifier = Modifier
                .size(56.dp)
                .glyphContentDescription(stringResource(id = R.string.cd_skip_previous))
        ) {
            SkipPreviousGlyph(
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.width(20.dp))

        FilledIconButton(
            onClick = onToggle,
            modifier = Modifier
                .size(64.dp)
                .glyphContentDescription(stringResource(id = R.string.cd_play_pause))
        ) {
            if (isPlaying) {
                PauseGlyph(
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                PlayGlyph(
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Spacer(modifier = Modifier.width(20.dp))

        IconButton(
            onClick = onNext,
            modifier = Modifier
                .size(56.dp)
                .glyphContentDescription(stringResource(id = R.string.cd_skip_next))
        ) {
            SkipNextGlyph(
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 播放倍速（慢放 / 快进）
// ---------------------------------------------------------------------------

/** 倍速步进：0.25 起调，落在 0.25x ~ 3x（与 PlaybackController 的 MIN/MAX 一致） */
private const val SPEED_STEP = 0.25f

/** 封面 / 歌词 两页的 Pager 页数 */
private const val PAGE_COUNT = 2

/** 把 1.25f → "1.25"、1.0f → "1.0"：去尾零但保留一位小数，显示稳定 */
private fun formatSpeed(value: Float): String {
    val rounded = (value * 100).roundToInt() / 100f
    return if (rounded == rounded.toInt().toFloat()) {
        "${rounded.toInt()}.0"
    } else {
        rounded.toString()
    }
}

@Composable
private fun SpeedRow(
    speed: Float,
    onSlower: () -> Unit,
    onFaster: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onSlower,
            modifier = Modifier.glyphContentDescription(stringResource(id = R.string.cd_speed_down))
        ) {
            Text(
                text = stringResource(id = R.string.action_slower),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 中间当前倍速：等于 1.0x 显示「常速」，点它回正常速度
        FilledTonalButton(
            onClick = onReset,
            modifier = Modifier.glyphContentDescription(
                stringResource(id = R.string.cd_speed_reset)
            )
        ) {
            Text(
                text = if (kotlin.math.abs(speed - 1f) < 0.01f) {
                    stringResource(id = R.string.label_speed_normal)
                } else {
                    stringResource(id = R.string.label_speed_value, formatSpeed(speed))
                },
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        FilledTonalButton(
            onClick = onFaster,
            modifier = Modifier.glyphContentDescription(stringResource(id = R.string.cd_speed_up))
        ) {
            Text(
                text = stringResource(id = R.string.action_faster),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 播放模式
// ---------------------------------------------------------------------------

@Composable
private fun ModeRow(
    mode: PlaybackMode,
    label: String,
    onCycle: () -> Unit,
    desktopLyricOn: Boolean,
    onToggleDesktopLyric: () -> Unit,
    addTarget: Song?,
    onAddToPlaylist: (Song) -> Unit
) {
    // 2026-09-12 图 023 反馈：三段等权会把「列表循环」压到 1/3 宽导致换行、间距过大。
    // 改为**居中组排**：LRC | 模式键 | 爱心 以内容自身宽度排成一组居中，
    // 组内间距与 SpeedRow（慢放/常速）一致偏大一点（16dp），模式键文字强制单行。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左：LRC 桌面歌词开关（关闭态带斜杠；未授权悬浮窗时点击引导去系统设置）
        IconButton(
            onClick = onToggleDesktopLyric,
            modifier = Modifier
                .size(40.dp)
                .glyphContentDescription(stringResource(id = R.string.cd_toggle_lyric))
        ) {
            LrcGlyph(
                lyricsOn = desktopLyricOn,
                modifier = Modifier.size(width = 38.dp, height = 22.dp),
                tint = if (desktopLyricOn) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // 中：播放模式（列表循环 / 单曲循环带「1」/ 随机）
        FilledTonalButton(
            onClick = onCycle,
            modifier = Modifier.glyphContentDescription(
                stringResource(id = R.string.cd_playback_mode)
            )
        ) {
            when (mode) {
                PlaybackMode.SHUFFLE -> ShuffleGlyph(
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )

                PlaybackMode.LIST_LOOP -> RepeatGlyph(
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )

                // 2026-09-12：单曲循环 = 循环图形内侧带「1」，与列表循环一眼可区分
                PlaybackMode.SINGLE_LOOP -> RepeatOneGlyph(
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                softWrap = false
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // 右：快捷加歌（线框爱心 + 右下角小加号融合图形）
        IconButton(
            onClick = { addTarget?.let(onAddToPlaylist) },
            enabled = addTarget != null,
            modifier = Modifier
                .size(40.dp)
                .glyphContentDescription(
                    stringResource(id = R.string.cd_add_current_to_playlist)
                )
        ) {
            HeartAddGlyph(
                modifier = Modifier.size(24.dp),
                tint = if (addTarget != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 封面页内嵌歌词行
// ---------------------------------------------------------------------------

/**
 * 封面页「歌曲信息 ↔ 进度条」之间的内嵌歌词行（2026-09-12 图 024 用户需求）。
 *
 * 与桌面歌词同源数据（[currentLyricLine] 的上游计算），随进度逐行刷新；
 * **固定高度占位**——无歌词 / 歌词未加载时留白，避免布局上下跳动。
 * 长句启用**跑马灯滚动**（图 025 反馈：单行省略号会藏住内容，跑马灯能完整展示）。
 * 字号 18sp + 主题色（图 028 反馈：14sp 灰色太弱，跟唱时看不清）。
 * 点击进入歌词页（与封面点击、双击桌面歌词行为一致）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InlineLyricLine(
    line: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (line.isNotBlank()) {
            Text(
                text = line,
                style = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .basicMarquee(iterations = Int.MAX_VALUE)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 歌词面板（封面 / 歌词 切换后的全屏歌词区）
// ---------------------------------------------------------------------------

@Composable
private fun LyricsPanel(
    song: Song?,
    lyricUi: LyricUi,
    positionMs: Long,
    // 点击歌词行回跳到该行时间点播放（未同步歌词由 LyricLines 内部忽略）
    onSeekTo: (Long) -> Unit,
    // 「暂无歌词」占位区点击 → 联网按歌名+歌手匹配（2026-09-20 用户需求）
    onMatchOnline: () -> Unit,
    // 平板双栏布局下歌词独立成右栏，「返回封面」无意义 → 传 null 隐藏按钮
    onBackToCover: (() -> Unit)?,
    onPickLocal: () -> Unit,
    onClearLocal: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // 顶栏：曲目信息 + 返回封面
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song?.title.orUnknown(stringResource(id = R.string.label_no_playing)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (song != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            id = R.string.label_artist_album,
                            song.artist.orUnknown(stringResource(id = R.string.label_unknown_artist)),
                            song.album.orUnknown(stringResource(id = R.string.label_unknown_album))
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (onBackToCover != null) {
                TextButton(onClick = onBackToCover) {
                    Text(
                        text = stringResource(id = R.string.label_cover),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (lyricUi) {
                LyricUi.Idle -> CenterMessage(
                    message = stringResource(id = R.string.label_no_playing)
                )
                LyricUi.Loading -> CenterColumn {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(id = R.string.lyric_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is LyricUi.Message -> CenterColumn {
                    Text(
                        text = lyricUi.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = if (lyricUi.canMatchOnline) {
                            Modifier.clickable(onClick = onMatchOnline)
                        } else {
                            Modifier
                        }
                    )
                    // 2026-09-20 图 008：无词/失败占位下给「联网匹配」入口（主文字也可点）
                    if (lyricUi.canMatchOnline) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(id = R.string.lyric_match_hint),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = onMatchOnline)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                is LyricUi.Ready -> LyricLines(
                    lyric = lyricUi.lyric,
                    positionMs = positionMs,
                    onSeekTo = onSeekTo
                )
            }
        }

        // 底部操作：本地歌词入口始终可用；当前歌词来自本地文件时额外提供「清除」
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if ((lyricUi as? LyricUi.Ready)?.fromLocal == true) {
                TextButton(onClick = onClearLocal) {
                    Text(
                        text = stringResource(id = R.string.action_clear_local_lyric),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            TextButton(onClick = onPickLocal) {
                Text(
                    text = stringResource(id = R.string.action_local_lyric),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/** 歌词区垂直居中的文案占位（无歌 / 纯文本提示等） */
@Composable
private fun CenterMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 歌词区垂直居中的内容块（加载中 / 提示语） */
@Composable
private fun CenterColumn(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}

/**
 * 歌词正文：带时间轴（[Lyric.synced] = true）时随 [positionMs] 高亮当前行并自动滚动；
 * 纯文本歌词（无时间轴）则静态展示，不设高亮。
 */
@Composable
private fun LyricLines(lyric: Lyric, positionMs: Long, onSeekTo: (Long) -> Unit) {
    val listState = rememberLazyListState()

    // 每 500ms 进度一跳都会重算一次；indexAt 只做线性比较，几十行开销可忽略。
    val activeIndex = lyric.indexAt(positionMs)

    // 只在「当前行下标变化」时才滚动（进度跳动但没换行时不打扰用户手动浏览）。
    LaunchedEffect(activeIndex) {
        if (activeIndex > 0) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (!lyric.synced) {
            Text(
                text = stringResource(id = R.string.lyric_plain_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp)
        ) {
            itemsIndexed(lyric.lines) { index, line ->
                val active = lyric.synced && index == activeIndex
                // 点击歌词行回跳播放（仅带时间轴的行可点；纯文本行 timeMs=-1 不可点）
                val lineClickable = lyric.synced && line.timeMs >= 0
                Text(
                    text = line.text,
                    // 2026-09-12 图 029：当前行放大到 26sp 加粗（原 titleMedium 22sp 不够突出），
                    // 其余行保持 bodyLarge 16sp
                    style = if (active) {
                        TextStyle(
                            fontSize = 26.sp,
                            lineHeight = 34.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .then(
                            if (lineClickable) {
                                Modifier.clickable { onSeekTo(line.timeMs) }
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }
    }
}

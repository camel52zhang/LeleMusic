package com.lelemusic.ui.chart

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lelemusic.R
import com.lelemusic.core.di.AppGraph
import com.lelemusic.data.plugin.PluginSource
import com.lelemusic.model.Platform
import com.lelemusic.model.Song
import com.lelemusic.repo.ChartUiState
import com.lelemusic.ui.common.ChartSkeletonList
import com.lelemusic.ui.common.EmptyState
import com.lelemusic.ui.common.ErrorState
import com.lelemusic.ui.common.SongRow
import com.lelemusic.ui.common.appErrorText
import com.lelemusic.ui.player.PlayerViewModel
import kotlinx.coroutines.delay

/** 彩蛋：标题连点次数（架构文档 T04 要点 2） */
private const val LAB_TAP_COUNT = 7

/** 彩蛋：连点有效窗口（ms），超时就重新计数 */
private const val LAB_TAP_WINDOW_MS = 2_000L

/** 分隔线左缩进：与 `SongRow` 的文字起始位置对齐（16 内边距 + 32 排名 + 4 + 56 封面 + 12） */
private const val DIVIDER_INSET = 120

/**
 * 榜单页（首页，PRD 5.1）。
 *
 * Top50 平铺在首页——MVP 不做独立榜单详情页（PRD 5.2 已确认的取舍）。
 *
 * **切平台 / 切榜单只更新列表，不碰播放器**（T04 要点 4）：
 * 播放指令由 [playerViewModel] 下发，榜单数据流由本页自己的 [ChartViewModel] 提供，
 * 两条链路互不干扰，换榜时正在播放的曲目不会中断。
 *
 * @param playerViewModel  Activity 作用域的播放器 ViewModel（由 `RootNav` 传入）
 * @param onOpenLab        进入音源自检台的入口；**T05 才实现界面**，本轮只打日志
 * @param onOpenLibrary    进入「我的歌单」列表
 */
@Composable
fun ChartScreen(
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    onOpenLab: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSearch: () -> Unit
) {
    val chartFactory = remember { chartViewModelFactory(repository = AppGraph.chartRepository) }
    val chartViewModel: ChartViewModel = viewModel(factory = chartFactory)

    val platforms by chartViewModel.platforms.collectAsState()
    val selectedPlatform by chartViewModel.selectedPlatform.collectAsState()
    val charts by chartViewModel.charts.collectAsState()
    val selectedChartId by chartViewModel.selectedChartId.collectAsState()
    val uiState by chartViewModel.uiState.collectAsState()

    val playerUiState by playerViewModel.uiState.collectAsState()
    val currentUid = playerUiState.song?.uid.orEmpty()

    // T05：启动探活结果。探活没跑完时为空 Map，此时平台 Tab 全部正常可点
    // （宁可漏报，不可因为自检代码出错把正常平台误伤成不可用）。
    val health by AppGraph.sourceHealthRepository.health.collectAsState()
    val unhealthyPlatforms: Set<Platform> = remember(health) {
        health.filterValues { result -> !result.ok }.keys.toSet()
    }

    // 用户已启用并作为「模块」展示的音源（实时跟随音源管理里的开关）
    val sourceModules by chartViewModel.sourceModules.collectAsState()
    val selectedSourceModule by chartViewModel.selectedSourceModule.collectAsState()

    // ---- 标题连点彩蛋：连点 7 次进 Lab（与顶栏齿轮走同一个 onOpenLab）----
    var tapCount by remember { mutableStateOf(0) }
    LaunchedEffect(key1 = tapCount) {
        if (tapCount <= 0) return@LaunchedEffect
        if (tapCount >= LAB_TAP_COUNT) {
            Log.i(TAG, "Lab easter egg triggered ($LAB_TAP_COUNT taps)")
            tapCount = 0
            onOpenLab()
        } else {
            delay(LAB_TAP_WINDOW_MS)
            tapCount = 0
        }
    }

    // 行内快捷加歌已于 2026-09-12 移除（加歌入口统一收进播放页「列表循环」右侧的「＋」）

    Column(modifier = modifier.fillMaxSize()) {
        TitleBar(
            onTitleClick = { tapCount += 1 },
            onOpenLibrary = onOpenLibrary,
            onOpenLab = onOpenLab,
            onOpenSearch = onOpenSearch
        )

        PlatformTabRow(
            platforms = platforms,
            sourceModules = sourceModules,
            selectedPlatform = selectedPlatform,
            selectedSourceModule = selectedSourceModule,
            unavailable = unhealthyPlatforms,
            onSelectPlatform = { platform -> chartViewModel.selectPlatform(platform) },
            onSelectSourceModule = { source -> chartViewModel.selectSourceModule(source) }
        )

        // 音源模块没有榜单：选中模块时隐藏榜单 Tab 行与「更新于」条，避免残留上一个平台的榜单
        if (selectedSourceModule == null) {
            ChartTabRow(
                charts = charts.map { chart -> chart.chartId to chart.title },
                selectedChartId = selectedChartId,
                onSelect = { chartId -> chartViewModel.selectChart(chartId) }
            )

            UpdateBar(
                uiState = uiState,
                onRefresh = { chartViewModel.refresh() }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // 委托属性有自定义 getter 不能 smart cast，先落成局部 val
            val activeModule = selectedSourceModule
            if (activeModule != null) {
                // 音源模块面板：该音源是取链代理，无独立榜单/搜索，展示说明而非歌曲列表
                ModulePanel(
                    source = activeModule,
                    onOpenSources = onOpenSources
                )
            } else when (val state = uiState) {
                ChartUiState.Loading -> ChartSkeletonList()

                is ChartUiState.Error -> ErrorState(
                    message = appErrorText(state.error),
                    onRetry = { chartViewModel.refresh() }
                )

                is ChartUiState.Success -> {
                    if (state.songs.isEmpty()) {
                        EmptyState(
                            message = stringResource(id = R.string.label_empty_chart),
                            hint = stringResource(id = R.string.label_empty_chart_hint)
                        )
                    } else {
                        SongList(
                            songs = state.songs,
                            currentUid = currentUid,
                            onSongClick = { song ->
                                playerViewModel.playSong(song = song, queue = state.songs)
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 顶部标题栏
// ---------------------------------------------------------------------------

@Composable
private fun TitleBar(
    onTitleClick: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenSearch: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable { onTitleClick() }
                .padding(horizontal = 8.dp, vertical = 12.dp)
        )
        IconButton(
            onClick = onOpenSearch,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(id = R.string.cd_open_search),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        IconButton(
            onClick = onOpenLibrary,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.List,
                contentDescription = stringResource(id = R.string.cd_open_library),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        IconButton(
            onClick = onOpenLab,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(id = R.string.cd_settings),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 平台 Tab（固定三等分）
// ---------------------------------------------------------------------------

/**
 * 平台 Tab（固定三等分）。
 *
 * @param platforms   全部已接入平台
 * @param selected    当前选中
 * @param unavailable 启动探活**确认**不可用的平台（T05）；这些 Tab 文字置灰并挂「维护中」角标。
 *                    仍然**可以点击**——置灰只是提示，用户想重试就该让他点。
 */
/**
 * 平台 Tab 行。
 *
 * `ScrollableTabRow`：已接入平台（网易 / 酷狗 / B站 / 本地音乐）+ 用户已启用的音源模块。
 * 原右侧「＋ 添加模块」入口已于 2026-09-12 移除——与「设置 → 音源 → 音源管理」功能重复，
 * 音源增删统一走设置入口。
 *
 * @param platforms           已接入平台（本地音乐 Tab 在有本地歌单时自动出现）
 * @param sourceModules       用户已启用且为单源脚本的音源（作为模块 Tab）
 * @param selectedPlatform    当前选中的平台
 * @param selectedSourceModule 当前选中的音源模块（非 null 时优先高亮对应源 Tab）
 * @param unavailable         启动探活确认不可用的平台（置灰 + 维护中角标）
 * @param onSelectPlatform    选中平台
 * @param onSelectSourceModule 选中音源模块
 */
@Composable
private fun PlatformTabRow(
    platforms: List<Platform>,
    sourceModules: List<PluginSource>,
    selectedPlatform: Platform,
    selectedSourceModule: PluginSource?,
    unavailable: Set<Platform>,
    onSelectPlatform: (Platform) -> Unit,
    onSelectSourceModule: (PluginSource) -> Unit
) {
    if (platforms.isEmpty() && sourceModules.isEmpty()) return

    val entries: List<ChartTabEntry> =
        platforms.map { ChartTabEntry.PlatformEntry(it) } +
            sourceModules.map { ChartTabEntry.SourceEntry(it) }

    val selectedIndex = if (selectedSourceModule != null) {
        val idx = sourceModules.indexOf(selectedSourceModule)
        if (idx >= 0) platforms.size + idx else 0
    } else {
        platforms.indexOf(selectedPlatform).coerceAtLeast(0)
    }

    ScrollableTabRow(
        selectedTabIndex = selectedIndex.coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth(),
        edgePadding = 0.dp
    ) {
        entries.forEachIndexed { index, entry ->
            val isSelected = index == selectedIndex
            Tab(
                selected = isSelected,
                onClick = {
                    when (entry) {
                        is ChartTabEntry.PlatformEntry -> onSelectPlatform(entry.platform)
                        is ChartTabEntry.SourceEntry -> onSelectSourceModule(entry.source)
                    }
                },
                modifier = Modifier.height(48.dp),
                text = {
                    val isDown = entry is ChartTabEntry.PlatformEntry &&
                        unavailable.contains(entry.platform)
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            isDown -> MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = 0.45f)

                            isSelected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1
                    )
                }
            )
        }
    }
}

/** 平台 Tab 区里的统一 Tab 项：要么是平台，要么是用户添加的音源模块 */
private sealed interface ChartTabEntry {
    val label: String
    data class PlatformEntry(val platform: Platform) : ChartTabEntry {
        override val label: String get() = platform.displayName
    }
    data class SourceEntry(val source: PluginSource) : ChartTabEntry {
        override val label: String get() = source.name
    }
}

// ---------------------------------------------------------------------------
// 榜单 Tab（横向可滚动，不引入 Pager）
// ---------------------------------------------------------------------------

@Composable
private fun ChartTabRow(
    charts: List<Pair<String, String>>,
    selectedChartId: String,
    onSelect: (String) -> Unit
) {
    if (charts.isEmpty()) return
    val rawIndex = charts.indexOfFirst { pair -> pair.first == selectedChartId }
    val selectedIndex = if (rawIndex < 0) 0 else rawIndex

    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth()
    ) {
        charts.forEach { pair ->
            val isSelected = pair.first == selectedChartId
            Tab(
                selected = isSelected,
                onClick = { onSelect(pair.first) },
                modifier = Modifier.height(44.dp),
                text = {
                    Text(
                        text = pair.second,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1
                    )
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 更新时间 + 刷新
// ---------------------------------------------------------------------------

@Composable
private fun UpdateBar(
    uiState: ChartUiState,
    onRefresh: () -> Unit
) {
    val success = uiState as? ChartUiState.Success
    val updatedAt = success?.updatedAt.orEmpty()
    val count = success?.songs?.size ?: 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (updatedAt.isBlank()) {
                stringResource(id = R.string.label_updated_unknown)
            } else {
                stringResource(id = R.string.label_updated_at, updatedAt)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (count > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(id = R.string.label_song_count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(id = R.string.cd_refresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 歌曲列表
// ---------------------------------------------------------------------------

@Composable
private fun SongList(
    songs: List<Song>,
    currentUid: String,
    onSongClick: (Song) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top
    ) {
        itemsIndexed(items = songs) { index, song ->
            SongRow(
                song = song,
                isCurrent = song.uid == currentUid,
                onClick = { onSongClick(song) }
            )
            if (index < songs.lastIndex) {
                Divider(
                    modifier = Modifier.padding(start = DIVIDER_INSET.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 音源模块面板（用户添加并启用的单源音源）
// ---------------------------------------------------------------------------

/**
 * 音源模块面板：用户从「音源管理」添加并启用的 LX/MusicFree 单源音源，作为一个「模块」出现在
 * 平台 Tab 区；点进来看到的就是这个面板。
 *
 * **为什么不是歌曲列表**：这些音源本质是 REST 取链代理（只提供播放直链，无独立榜单 / 搜索能力），
 * 真正能「浏览歌曲」需要 JS 引擎执行脚本搜索——而 Maven Central 的 QuickJS 绑定不排空 Promise
 * 微任务、本机无 NDK，故暂缓。这里如实说明它的能力边界：播放网易云 / 酷狗歌曲、原生取链失败时，
 * 会自动作为降级策略取链（策略 `lx.wy.*` / `lx.kg.*`，可在自检台单独开关）。
 */
@Composable
private fun ModulePanel(
    source: PluginSource,
    onOpenSources: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = source.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                ModuleBadge(text = runtimeLabel(source.runtime))
                Spacer(modifier = Modifier.width(6.dp))
                if (source.version != null) {
                    ModuleBadge(text = source.version)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = source.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 文案按「是否真的挂了取链策略」区分：
            // api-host（非 .js/.json）→ AppGraph 会为它生成 LxProxyResolver，是真取链代理；
            // 其余（LX / MusicFree 脚本）→ 脚本本身未被执行（无 JS 引擎），当前不提供取链能力。
            val hintRes = if (isApiHostUrl(source.url)) {
                R.string.module_proxy_hint
            } else {
                R.string.module_inactive_hint
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = stringResource(id = hintRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(id = R.string.module_no_browse),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onOpenSources,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(text = stringResource(id = R.string.action_open_sources))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ModuleBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun runtimeLabel(runtime: String): String = when (runtime) {
    PluginSource.RUNTIME_LX -> stringResource(R.string.sources_runtime_lx)
    PluginSource.RUNTIME_MUSICFREE -> stringResource(R.string.sources_runtime_musicfree)
    else -> stringResource(R.string.sources_runtime_unknown)
}

/** 与 AppGraph.lxProxyResolvers 的 api-host 判定保持一致：非 .js/.json 的 http(s) 地址才会挂取链策略 */
private fun isApiHostUrl(url: String): Boolean =
    url.startsWith("http", ignoreCase = true) &&
        !url.endsWith(".js", ignoreCase = true) &&
        !url.endsWith(".json", ignoreCase = true)

private const val TAG = "ChartScreen"

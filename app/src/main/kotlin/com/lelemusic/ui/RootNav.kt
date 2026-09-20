package com.lelemusic.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lelemusic.core.di.AppGraph
import com.lelemusic.ui.chart.ChartScreen
import com.lelemusic.ui.common.LocalExpandedScreen
import com.lelemusic.ui.common.LocalWindowWidthDp
import com.lelemusic.ui.common.PHONE_MAX_SMALLEST_WIDTH_DP
import com.lelemusic.ui.lab.LabScreen
import com.lelemusic.ui.library.LibraryScreen
import com.lelemusic.ui.library.LibraryViewModel
import com.lelemusic.ui.library.PlaylistDetailScreen
import com.lelemusic.ui.library.libraryViewModelFactory
import com.lelemusic.ui.player.MiniPlayerBar
import com.lelemusic.ui.player.PlayerScreen
import com.lelemusic.ui.player.PlayerViewModel
import com.lelemusic.ui.player.playerViewModelFactory
import com.lelemusic.ui.search.SearchScreen
import com.lelemusic.ui.sources.SourceManagerScreen

/** 导航路由常量 */
object Route {
    const val CHART = "chart"
    const val PLAYER = "player"
    const val LAB = "lab"
    const val LIBRARY = "library"
    /** 音源管理（用户自行添加的音源插件） */
    const val SOURCES = "sources"

    /** 搜索页（网易云云搜索，复用取链降级链播放） */
    const val SEARCH = "search"

    /** 歌单详情路由模板（带 playlistId 参数），见 [playlistDetail] */
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"

    /** 拼歌单详情路由 */
    fun playlistDetail(playlistId: String): String = "playlist/$playlistId"
}

/**
 * 应用根容器：`NavHost`（chart / lab / player 三个路由）+ `Scaffold.bottomBar`（迷你播放条）。
 *
 * **为什么 `PlayerViewModel` 在这里创建**：
 * `viewModel()` 在 `NavHost` **之外**调用，拿到的 `ViewModelStoreOwner` 是 `ComponentActivity`，
 * 因此它是 Activity 作用域的单例。榜单页、播放器页、迷你条共用同一个 `PlaybackController`，
 * 不会各建一个 `MediaController`——这是「切榜不清空播放状态」的结构性保证。
 */
@Composable
fun RootNav() {
    val navController: NavHostController = rememberNavController()
    val appContext = LocalContext.current.applicationContext

    // factory 用 remember 固定：ViewModel 本身缓存在 ViewModelStore 里不会重建，
    // 但固定 factory 可以避免每次重组都新建一个对象，减少无谓的 remember 失效。
    val playerFactory = remember(appContext) { playerViewModelFactory(appContext = appContext) }
    val playerViewModel: PlayerViewModel = viewModel(factory = playerFactory)
    val playerUiState by playerViewModel.uiState.collectAsState()

    // 「我的歌单」ViewModel：Activity 作用域单例，列表页 / 详情页 / 榜单快捷加歌共用
    val libraryFactory = remember { libraryViewModelFactory(repository = AppGraph.libraryRepository) }
    val libraryViewModel: LibraryViewModel = viewModel(factory = libraryFactory)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // 迷你播放条覆盖：榜单页 / 我的歌单列表 / 歌单详情（播放器全屏页本身就有控制条）
    val routeShowsMiniPlayer = currentRoute == Route.CHART ||
        currentRoute == Route.LIBRARY ||
        currentRoute == Route.PLAYLIST_DETAIL
    val showMiniPlayer = routeShowsMiniPlayer && playerUiState.song != null

    // 平板适配 v2（2026-09-12，据 001/002 截图反推）：
    // 小米平板 5 Pro 密度实为 340（非标准 276）——横屏约 1204dp（Expanded）、
    // 竖屏约 753dp（Medium）。v1 只在 Expanded 限宽导致竖屏全宽铺满（002 截图）。
    // 现改为：窗口 ≥600dp 一律限宽居中——Medium 竖屏 680dp、Expanded 横屏 760dp；
    // 例外一：Expanded 播放页（自带双栏布局，需要全宽）；
    // 例外二：自检台「显示」开关「大屏铺满到边」打开（车机场景，全部铺满）。
    val isExpanded = LocalExpandedScreen.current
    val windowWidthDp = LocalWindowWidthDp.current
    val fillWideScreen by AppGraph.displayPrefs.fillWideScreen.collectAsState()
    val playerTwoPane = isExpanded && currentRoute == Route.PLAYER
    val capContentWidth = windowWidthDp >= PHONE_MAX_SMALLEST_WIDTH_DP &&
        !fillWideScreen && !playerTwoPane
    val maxContentWidth = if (isExpanded) 760.dp else 680.dp

    Scaffold(
        bottomBar = {
            if (showMiniPlayer) {
                MiniPlayerBar(
                    song = playerUiState.song,
                    isPlaying = playerUiState.isPlaying,
                    isTrial = playerUiState.isTrial,
                    onOpen = { navController.navigate(Route.PLAYER) { launchSingleTop = true } },
                    onToggle = { playerViewModel.togglePlayPause() },
                    onNext = { playerViewModel.skipNext() }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Route.CHART,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(innerPadding)
                    .then(
                        if (capContentWidth) Modifier.widthIn(max = maxContentWidth) else Modifier
                    )
            ) {
            composable(route = Route.CHART) {
                ChartScreen(
                    playerViewModel = playerViewModel,
                    onOpenLab = {
                        navController.navigate(Route.LAB) { launchSingleTop = true }
                    },
                    onOpenLibrary = {
                        navController.navigate(Route.LIBRARY) { launchSingleTop = true }
                    },
                    onOpenSources = {
                        navController.navigate(Route.SOURCES) { launchSingleTop = true }
                    },
                    onOpenSearch = {
                        navController.navigate(Route.SEARCH) { launchSingleTop = true }
                    }
                )
            }

            // 我的歌单列表（榜单页顶栏歌单图标进入）
            composable(route = Route.LIBRARY) {
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenPlaylist = { playlistId ->
                        navController.navigate(Route.playlistDetail(playlistId)) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            // 歌单详情（路由参数 playlistId）
            composable(
                route = Route.PLAYLIST_DETAIL,
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
            ) { entry ->
                val playlistId = entry.arguments?.getString("playlistId").orEmpty()
                PlaylistDetailScreen(
                    playlistId = playlistId,
                    libraryViewModel = libraryViewModel,
                    playerViewModel = playerViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            // T05：音源自检台。两个入口（顶栏齿轮 / 标题连点 7 次）都接到同一个
            // onOpenLab 回调，因此这里只有一处 navigate。
            composable(route = Route.LAB) {
                LabScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSources = {
                        navController.navigate(Route.SOURCES) { launchSingleTop = true }
                    }
                )
            }

            // 音源管理（自检台顶栏「音源」进入）：添加 / 抓取校验 / 展开注册表 / 启停删除
            composable(route = Route.SOURCES) {
                SourceManagerScreen(onBack = { navController.popBackStack() })
            }

            // 搜索页（榜单页顶栏放大镜进入）：网易云云搜索 + 既有取链链路播放
            composable(route = Route.SEARCH) {
                SearchScreen(
                    playerViewModel = playerViewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(route = Route.PLAYER) {
                PlayerScreen(
                    viewModel = playerViewModel,
                    libraryViewModel = libraryViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            }
        }
    }
}

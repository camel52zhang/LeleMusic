package com.lelemusic.ui.chart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lelemusic.core.common.AppError
import com.lelemusic.core.di.AppGraph
import com.lelemusic.data.local.LocalPlatform
import com.lelemusic.data.plugin.PluginSource
import com.lelemusic.model.Playlist
import com.lelemusic.model.ChartDef
import com.lelemusic.model.Platform
import com.lelemusic.repo.ChartRepository
import com.lelemusic.repo.ChartUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 榜单页 ViewModel。
 *
 * **重要约定：本 ViewModel 完全不持有 `PlaybackController`**（架构文档 T04 要点 4）。
 * 切平台 / 切榜单只会取消旧的加载协程并开一个新的，**不碰播放器**，
 * 因此正在播放的曲目不会因为用户换榜而中断。
 *
 * @param repository 由 `AppGraph.chartRepository` 注入
 */
class ChartViewModel(
    private val repository: ChartRepository
) : ViewModel() {

    /**
     * 已接入平台（网易 / 酷狗 / B站 / 本地音乐…），顺序与 `AppGraph.modules` 一致。
     *
     * **响应式**：订阅 [AppGraph.libraryRepository.playlists]——「导入 / 挂载 → 命名」
     * 第一个本地歌单后，「本地音乐」Tab 立即出现在哔哩哔哩右侧；在我的歌单里
     * 删光本地歌单则自动隐藏（重命名也会同步刷新榜单 Tab 名）。
     */
    private val _platforms = MutableStateFlow(repository.platforms())
    val platforms: StateFlow<List<Platform>> = _platforms.asStateFlow()

    /**
     * 用户「音源管理」里**已启用**且为单源脚本的音源——作为主页平台 Tab 区右侧的「模块」展示。
     *
     * 这些 LX/MusicFree 音源本质是 REST 取链代理（只提供播放直链，无独立榜单/搜索），
     * 故它们以「模块」形态出现在 Tab 区，点进去是说明面板（见 `ChartScreen.ModulePanel`），
     * 播放网易云 / 酷狗歌曲、原生取链失败时自动作为降级策略取链。
     * 实时跟随 `pluginSourceRepository.sources`（开关切一下就刷新）。
     */
    val sourceModules: StateFlow<List<PluginSource>> = AppGraph.pluginSourceRepository.sources
        .map { list -> list.filter { it.enabled && it.type == PluginSource.TYPE_SINGLE } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _selectedSourceModule = MutableStateFlow<PluginSource?>(null)
    /** 当前选中的音源模块（非 null 时主页内容区显示该模块的说明面板，而非榜单列表） */
    val selectedSourceModule: StateFlow<PluginSource?> = _selectedSourceModule.asStateFlow()

    private val _selectedPlatform = MutableStateFlow(
        _platforms.value.firstOrNull() ?: error("no platform registered in AppGraph.modules")
    )
    /** 当前选中的平台 */
    val selectedPlatform: StateFlow<Platform> = _selectedPlatform.asStateFlow()

    private val _selectedChartId = MutableStateFlow(
        repository.defaultChartOf(_selectedPlatform.value)?.chartId.orEmpty()
    )
    /** 当前选中的榜单 ID */
    val selectedChartId: StateFlow<String> = _selectedChartId.asStateFlow()

    private val _charts = MutableStateFlow(repository.chartsOf(_selectedPlatform.value))
    /** 当前平台下已启用的榜单（供 `ScrollableTabRow` 渲染） */
    val charts: StateFlow<List<ChartDef>> = _charts.asStateFlow()

    private val _uiState = MutableStateFlow<ChartUiState>(ChartUiState.Loading)
    /** 榜单列表状态：Loading / Success / Error */
    val uiState: StateFlow<ChartUiState> = _uiState.asStateFlow()

    /** 当前加载协程；切榜时先取消，避免旧请求回填到新榜单上 */
    private var loadJob: Job? = null

    init {
        reload(force = false)
        // 本地歌单（我的歌单里 origin = import / mount 的条目）变化 →
        // 平台 Tab 显隐 / 本地榜单列表 / 当前选中的联动刷新。
        // 本地平台命中时强制走仓库（绕过内存缓存），保证刚导入的歌立刻可见。
        AppGraph.libraryRepository.playlists.onEach { playlists ->
            val localCharts = playlists.filter { it.isLocalChart }
            _platforms.value = repository.platforms()
            if (_selectedPlatform.value == LocalPlatform) {
                if (localCharts.isEmpty()) {
                    // 本地歌单被清空：本地 Tab 消失，回落到第一个可用平台
                    _platforms.value.firstOrNull()?.let { first ->
                        _selectedPlatform.value = first
                        _charts.value = repository.chartsOf(first)
                        _selectedChartId.value = repository.defaultChartOf(first)?.chartId.orEmpty()
                        reload(force = false)
                    }
                } else {
                    _charts.value = repository.chartsOf(LocalPlatform)
                    if (_selectedChartId.value !in localCharts.map { it.id }) {
                        _selectedChartId.value = repository.defaultChartOf(LocalPlatform)?.chartId.orEmpty()
                    }
                    reload(force = true)
                }
            }
        }.launchIn(viewModelScope)
    }

    /**
     * 切换平台。
     *
     * 榜单 Tab 自动重置为该平台的默认榜单（PRD 5.1：平台 Tab 与榜单 Tab 联动）。
     * 同一平台重复点击直接忽略，避免无谓的网络请求。
     */
    fun selectPlatform(platform: Platform) {
        if (platform == _selectedPlatform.value && _selectedSourceModule.value == null) return
        _selectedPlatform.value = platform
        // 切回平台 Tab 时清掉音源模块选中态，内容区回到榜单列表
        _selectedSourceModule.value = null
        _charts.value = repository.chartsOf(platform)
        _selectedChartId.value = repository.defaultChartOf(platform)?.chartId.orEmpty()
        reload(force = false)
    }

    /** 选中某个音源模块（主页平台 Tab 区里用户添加并启用的音源） */
    fun selectSourceModule(source: PluginSource) {
        _selectedSourceModule.value = source
    }

    /** 切换榜单（同平台内） */
    fun selectChart(chartId: String) {
        if (chartId == _selectedChartId.value) return
        _selectedChartId.value = chartId
        reload(force = false)
    }

    /** 手动刷新：忽略内存缓存，强制走网络（PRD 5.1 的 [↻ 刷新]） */
    fun refresh() {
        reload(force = true)
    }

    private fun reload(force: Boolean) {
        loadJob?.cancel()
        val platform = _selectedPlatform.value
        val chartId = _selectedChartId.value
        if (chartId.isEmpty()) {
            _uiState.value = ChartUiState.Error(
                error = AppError.EmptyData,
                message = "no chart for platform=${platform.id}"
            )
            return
        }
        _uiState.value = ChartUiState.Loading
        loadJob = repository.load(platform = platform, chartId = chartId, force = force)
            .onEach { state -> _uiState.value = state }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        loadJob = null
    }
}

/**
 * [ChartViewModel] 的工厂（手工 DI，无 Hilt）。
 *
 * 只重写单参数 `create(modelClass)`：它在 `ViewModelProvider.Factory` 里从 1.0 就存在，
 * 而 `create(modelClass, extras)` 的**默认实现就是转发到它**，因此只重写这一个最稳。
 */
fun chartViewModelFactory(repository: ChartRepository): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChartViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return ChartViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

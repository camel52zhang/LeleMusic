package com.lelemusic.ui.search

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lelemusic.R
import com.lelemusic.core.common.AppError
import com.lelemusic.core.common.AppException
import com.lelemusic.core.di.AppGraph
import com.lelemusic.data.netease.NeteasePlatform
import com.lelemusic.data.remote.NeteaseApi
import com.lelemusic.data.remote.dto.NeteaseSearchSong
import com.lelemusic.model.PlayableStatus
import com.lelemusic.model.Song
import com.lelemusic.ui.common.EmptyState
import com.lelemusic.ui.common.ErrorState
import com.lelemusic.ui.common.SongRow
import com.lelemusic.ui.common.appErrorText
import com.lelemusic.ui.player.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException

/** 搜索接口常量（cloudsearch，2026-09-11 curl 实测匿名可用） */
private const val CLOUDSEARCH_URL = "https://music.163.com/api/cloudsearch/pc"

/** 每页结果数 */
private const val SEARCH_LIMIT = 30

/**
 * 搜索页 ViewModel。
 *
 * 通道：网易云 `POST /api/cloudsearch/pc`（匿名可用，无 JS 引擎依赖）；
 * 搜出歌曲即网易云侧 Song（uid=`netease:<id>`），点播走既有取链降级链
 * （`netease.eapi` → `netease.320` → `lx.wy.onrender` → `netease.gd`）。
 */
class SearchViewModel(
    private val api: NeteaseApi
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val loading: Boolean = false,
        val searched: Boolean = false,
        val results: List<Song> = emptyList(),
        val error: AppError? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    /** 触发搜索（IME 搜索键 / 搜索图标）；空关键词直接忽略 */
    fun search() {
        val keyword = _uiState.value.query.trim()
        if (keyword.isEmpty() || _uiState.value.loading) return

        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.cloudSearchSong(CLOUDSEARCH_URL, keyword, limit = SEARCH_LIMIT)
                }
                val songs = resp.result?.songs.orEmpty()
                    .filter { it.id != null }
                    .mapIndexed { index, dto -> dto.toSong(rank = index + 1) }
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    searched = true,
                    results = songs
                )
            } catch (t: Throwable) {
                // 按 §7.3 约定收敛为 AppError 枚举，UI 层不接触原始异常
                val appError = when (t) {
                    is AppException -> t.error
                    is SocketTimeoutException -> AppError.Timeout
                    is IOException -> AppError.Network
                    else -> AppError.Unknown
                }
                _uiState.value = _uiState.value.copy(loading = false, searched = true, error = appError)
            }
        }
    }

    private fun NeteaseSearchSong.toSong(rank: Int): Song = Song(
        uid = "${NeteasePlatform.id}:$id",
        platform = NeteasePlatform,
        platformSongId = id?.toString().orEmpty(),
        title = name.orEmpty().ifBlank { "未知歌曲" },
        artist = artists.orEmpty().mapNotNull { it.name }.joinToString(" / ")
            .ifBlank { "未知歌手" },
        album = album?.name.orEmpty().ifBlank { "未知专辑" },
        durationMs = duration ?: 0L,
        coverUrl = album?.picUrl,
        rank = rank,
        playable = PlayableStatus.UNKNOWN
    )
}

fun searchViewModelFactory(api: NeteaseApi): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return SearchViewModel(api) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

/**
 * 搜索页（入口：榜单页顶栏放大镜）。
 *
 * 复用 [SongRow] 与播放链：点击结果即以「本次搜索结果」为队列起播，
 * 付费墙歌曲由服务侧自动跳过逻辑兜底（与榜单播放一致）。
 */
@Composable
fun SearchScreen(
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val factory = remember { searchViewModelFactory(api = AppGraph.neteaseApi) }
    val viewModel: SearchViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsState()
    val playerUiState by playerViewModel.uiState.collectAsState()
    val currentUid = playerUiState.song?.uid.orEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.surface)
    ) {
        SearchTopBar(
            query = state.query,
            loading = state.loading,
            onQueryChange = viewModel::onQueryChange,
            onSearch = viewModel::search,
            onBack = onBack
        )

        if (state.loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
        }

        val searchError = state.error
        when {
            searchError != null -> ErrorState(
                message = appErrorText(searchError),
                onRetry = viewModel::search
            )

            !state.searched -> EmptyState(
                message = stringResource(id = R.string.search_empty),
                hint = stringResource(id = R.string.search_empty_hint)
            )

            state.results.isEmpty() -> EmptyState(
                message = stringResource(id = R.string.search_no_result),
                hint = stringResource(id = R.string.search_empty_hint)
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items = state.results) { index, song ->
                    SongRow(
                        song = song,
                        isCurrent = song.uid == currentUid,
                        onClick = {
                            playerViewModel.playSong(song = song, queue = state.results)
                        }
                    )
                    if (index < state.results.lastIndex) {
                        Divider(
                            modifier = Modifier.padding(start = 120.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTopBar(
    query: String,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit
) {
    // 2026-09-12 图 026 修复：行高 64dp 扣掉上下 8dp 内边距只剩 48dp，
    // 而 OutlinedTextField 最小高度 56dp——文字底部被裁掉一截（截图 26）。
    // 行高提到 72dp，让输入框拿到完整的 56dp。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = stringResource(id = R.string.cd_back),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            placeholder = { Text(text = stringResource(id = R.string.search_hint)) },
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                IconButton(onClick = onSearch, enabled = !loading) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(id = R.string.cd_run_search),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        )
    }
}

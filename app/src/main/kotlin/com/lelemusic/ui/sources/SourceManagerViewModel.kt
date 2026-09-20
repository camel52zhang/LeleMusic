package com.lelemusic.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lelemusic.data.plugin.PluginSourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 音源管理页 VM：只做「输入 → 仓库操作 → 状态回显」，不碰网络细节 */
class SourceManagerViewModel(
    private val repository: PluginSourceRepository
) : ViewModel() {

    data class UiState(
        val sources: List<com.lelemusic.data.plugin.PluginSource> = emptyList(),
        val input: String = "",
        val message: String = "",
        val refreshing: Boolean = false,
        val adding: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    init {
        viewModelScope.launch {
            repository.sources.collect { list ->
                _uiState.update { it.copy(sources = list) }
            }
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun addFromInput() {
        val text = _uiState.value.input
        if (text.isBlank()) return
        _uiState.update { it.copy(adding = true, message = "") }
        viewModelScope.launch {
            val summary = repository.addFromText(text)
            val msg = when {
                summary.added > 0 && summary.skipped > 0 ->
                    "已添加 ${summary.added} 个，跳过重复 ${summary.skipped} 个"

                summary.added > 0 -> "已添加 ${summary.added} 个音源"
                summary.skipped > 0 -> "全部 ${summary.skipped} 个都已存在"
                else -> "没有识别到可用的 URL"
            }
            _uiState.update { it.copy(adding = false, input = "", message = msg) }
            if (summary.added > 0) refreshAll()
        }
    }

    fun refreshAll() {
        _uiState.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            runCatching { repository.refreshAll() }
            _uiState.update { it.copy(refreshing = false) }
        }
    }

    fun refreshOne(id: String) {
        viewModelScope.launch { runCatching { repository.refreshOne(id) } }
    }

    fun toggle(id: String, enabled: Boolean) {
        repository.setEnabled(id, enabled)
    }

    fun remove(id: String) {
        repository.remove(id)
    }

    fun expand(id: String) {
        viewModelScope.launch {
            val added = runCatching { repository.expandRegistry(id) }.getOrDefault(0)
            _uiState.update {
                it.copy(
                    message = if (added > 0) "已展开 $added 个插件" else "没有新的插件（可能已全部安装或抓取失败）"
                )
            }
        }
    }
}

fun sourceManagerViewModelFactory(
    repository: PluginSourceRepository
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SourceManagerViewModel(repository) as T
    }
}

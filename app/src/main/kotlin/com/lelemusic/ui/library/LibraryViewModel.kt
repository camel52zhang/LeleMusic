package com.lelemusic.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lelemusic.model.Platform
import com.lelemusic.model.Playlist
import com.lelemusic.model.Song
import com.lelemusic.repo.LibraryRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 「我的歌单」ViewModel：薄封装 [LibraryRepository]，供歌单列表页 / 详情页 / 榜单快捷加歌共用。
 *
 * 与 [com.lelemusic.ui.player.PlayerViewModel] 一样在 `RootNav`（Activity 作用域）创建，
 * 所以三处入口看到的是同一份歌单状态。
 *
 * @param repository 由 `RootNav` 注入的 `AppGraph.libraryRepository` 单例
 */
class LibraryViewModel(
    private val repository: LibraryRepository
) : ViewModel() {

    /** 歌单全集（增删改后自动推送） */
    val playlists: StateFlow<List<Playlist>> = repository.playlists

    /** `platform.id → Platform` 反查表：详情页把持久化 DTO 还原成 [Song] 用 */
    val platformById: Map<String, Platform> = repository.platformById

    fun createPlaylist(name: String) {
        viewModelScope.launch { repository.create(name) }
    }

    /**
     * 创建「本地歌单」（导入 / 挂载命名后调用，2026-09-12 需求）。
     *
     * 歌单落在我的歌单列表（带「导入」/「挂载」徽标，可重命名/删除），
     * 同时作为首页「本地音乐」Tab 的下一级榜单；[origin] 取
     * [com.lelemusic.model.Playlist.Companion.ORIGIN_IMPORT] 或 ORIGIN_MOUNT。
     */
    fun createLocalChart(name: String, songs: List<Song>, origin: String) {
        viewModelScope.launch { repository.createLocalChart(name, songs, origin) }
    }

    /** 新建歌单并立刻把 [song] 加进去（榜单快捷加歌的「新建并加入」用）；返回是否成功 */
    suspend fun createPlaylistAndAdd(name: String, song: Song): Boolean {
        val playlist = repository.create(name) ?: return false
        return repository.addSong(playlist.id, song)
    }

    /** 把一批歌加进歌单（按 uid 去重）；返回真正加入的数量（重复自动跳过） */
    suspend fun addSongs(playlistId: String, songs: List<Song>): Int {
        var added = 0
        for (song in songs) {
            if (repository.addSong(playlistId, song)) added++
        }
        return added
    }

    /**
     * 批量加歌并返回「是否全部已在歌单中」。
     *
     * [addSongs] 之外再判断：当 [songs] 不是空表且一首都没真正加入（全是重复）时返回 true，
     * 供调用方触发「重挂载同一文件夹 → 按本次顺序刷新歌单」的镜像语义。
     */
    suspend fun addSongsOrAllDup(playlistId: String, songs: List<Song>): Pair<Int, Boolean> {
        if (songs.isEmpty()) return 0 to true
        val added = addSongs(playlistId, songs)
        return added to (added == 0)
    }

    /**
     * 批量加入结果：真正加入几首、是否全部已在目标歌单里（触发镜像刷新）。
     * 新建歌单撞重名时目标歌单 = 已存在的同名歌单。
     */
    data class BatchAddResult(
        val playlist: Playlist?,
        val added: Int,
        val allDup: Boolean
    )

    /**
     * 新建歌单并加入一批歌（名称重名时返回已存在的同名歌单）。
     * 命中重名歌单且这批歌全部已存在 → 按本次顺序重排（镜像刷新）。
     */
    suspend fun createPlaylistAndAddAll(name: String, songs: List<Song>): BatchAddResult {
        val playlist = repository.create(name) ?: return BatchAddResult(null, 0, false)
        val (added, allDup) = addSongsOrAllDup(playlist.id, songs)
        if (allDup && songs.size > 1) {
            repository.reorderBatch(playlist.id, songs)
        }
        return BatchAddResult(playlist, added, allDup)
    }

    /** 把某歌单里本批歌曲按 [songs] 顺序原地重排（[repository.reorderBatch] 的薄封装） */
    suspend fun reorderBatch(playlistId: String, songs: List<Song>): Boolean =
        repository.reorderBatch(playlistId, songs)

    fun renamePlaylist(id: String, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    /** 加歌；返回是否真的加入（false = 已在歌单中）。挂起由调用方协程管理。 */
    suspend fun addSong(playlistId: String, song: Song): Boolean =
        repository.addSong(playlistId, song)

    /** 从歌单移除；uid = `platformId:platformSongId` */
    fun removeSong(playlistId: String, uid: String) {
        viewModelScope.launch { repository.removeSong(playlistId, uid) }
    }

    /** 歌单内歌曲按标题自然序重排并持久化（详情页「按名称排序」按钮） */
    fun sortSongsByTitle(playlistId: String) {
        viewModelScope.launch { repository.sortByTitle(playlistId) }
    }
}

/**
 * [LibraryViewModel] 工厂（手工 DI，风格同 `playerViewModelFactory`）。
 */
fun libraryViewModelFactory(repository: LibraryRepository): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return LibraryViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

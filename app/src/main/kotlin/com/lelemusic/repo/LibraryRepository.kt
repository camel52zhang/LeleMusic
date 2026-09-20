package com.lelemusic.repo

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.lelemusic.model.LibraryFile
import com.lelemusic.model.Playlist
import com.lelemusic.model.Platform
import com.lelemusic.model.Song
import com.lelemusic.model.StorableSong
import com.lelemusic.model.toStorableSong
import com.lelemusic.util.NaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自建歌单仓库：内存态 `StateFlow<List<Playlist>>` + `filesDir/playlists.json` 落盘。
 *
 * **设计约束**：
 * - 全工程单例（由 `AppGraph.libraryRepository` 提供），UI 层只做增删改查、
 *   不直接碰文件——歌单列表页 / 歌单详情页 / 榜单快捷加歌共用同一份状态；
 * - 写入采用「临时文件 + rename 原子替换」，避免写一半崩掉损坏整个库；
 * - 读取容忍缺文件 / 坏 JSON：损坏时**备份原文件**（`playlists.json.corrupt`）
 *   并回落到空库，绝不让一次坏盘把 App 卡在启动崩溃。
 *
 * @param file          JSON 文件路径（`File(application.filesDir, "playlists.json")`）
 * @param gson          全局共享 Gson
 * @param platformById  `platform.id → Platform` 反查表（还原 DTO 用；取自 AppGraph）
 */
class LibraryRepository(
    private val file: File,
    private val gson: Gson,
    val platformById: Map<String, Platform>
) {

    private val _playlists = MutableStateFlow(normalize(loadFromDisk()))

    init {
        // 一次性迁移：ag 版的 LocalChartStore（local_charts.json）并入本仓库，
        // 迁移后删除旧文件。无旧文件时零开销。
        migrateLocalCharts()
    }

    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val tag = "LibraryRepository"

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 归一化读盘结果：Gson 对 Kotlin data class 走 Unsafe 分配，缺 `songs` 字段时
     * 运行时可能是 null（编译器不信），这里统一兜底成空表；`origin` 缺失归一化为手动新建
     * （旧版本 playlists.json 没有 origin 字段）。
     */
    @Suppress("USELESS_ELVIS")
    private fun normalize(list: List<Playlist>): List<Playlist> =
        list.map { playlist ->
            playlist.copy(
                songs = playlist.songs ?: emptyList(),
                origin = playlist.origin ?: Playlist.ORIGIN_MANUAL
            )
        }

    /** 歌单详情内某歌单（id 不存在返回 null，UI 层兜底） */
    fun playlist(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    private fun loadFromDisk(): List<Playlist> {
        if (!file.exists()) return emptyList()
        return try {
            val container = gson.fromJson(
                file.readText(),
                LibraryFile::class.java
            ) ?: LibraryFile()
            normalize(container.playlists)
        } catch (bad: JsonSyntaxException) {
            Log.w(tag, "playlists.json corrupt, backup & reset", bad)
            try {
                file.renameTo(File(file.parentFile, "playlists.json.corrupt"))
            } catch (ignored: Exception) {
                Log.e(tag, "backup corrupt file failed", ignored)
            }
            emptyList()
        } catch (other: Exception) {
            Log.e(tag, "read playlists.json failed", other)
            emptyList()
        }
    }

    // ------------------------------------------------------------------
    // 写（全部 suspend：IO 线程落盘）
    // ------------------------------------------------------------------

    /** 新建歌单；name 空白返回 null。自动去重同名（同名视为追加到已存在的那条）。 */
    suspend fun create(name: String): Playlist? = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext null
        val now = System.currentTimeMillis()
        val existing = _playlists.value.firstOrNull { it.name == trimmed }
        if (existing != null) return@withContext existing
        val playlist = Playlist(id = "pl_${now}_${_playlists.value.size}", name = trimmed, createdAtMs = now)
        mutate { it + playlist }
        playlist
    }

    /**
     * 创建「本地歌单」（导入 / 挂载命名后调用，2026-09-12 需求）。
     *
     * 与 [create] 的差别：
     * - **允许同名并存**（两次导入可以都叫「我的歌」），歌单以 id 为唯一键；
     * - 创建时**立刻带上全部歌曲**与来源标记 [origin]（[Playlist.ORIGIN_IMPORT] /
     *   [Playlist.ORIGIN_MOUNT]）；这类歌单会同时作为首页「本地音乐」Tab 的下一级榜单。
     */
    suspend fun createLocalChart(
        name: String,
        songs: List<Song>,
        origin: String
    ): Playlist? = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext null
        val now = System.currentTimeMillis()
        val playlist = Playlist(
            id = "pl_${now}_${_playlists.value.size}",
            name = trimmed,
            createdAtMs = now,
            songs = songs.map { it.toStorableSong() },
            origin = origin
        )
        mutate { it + playlist }
        playlist
    }

    /**
     * 一次性迁移：把 ag 版 [com.lelemusic.data.local.LocalChartStore] 的
     * `local_charts.json` 并入本仓库（origin 统一记为「导入」），迁移后删除旧文件。
     */
    private fun migrateLocalCharts() {
        val legacy = File(file.parentFile, "local_charts.json")
        if (!legacy.exists()) return
        try {
            val legacyCharts: List<LegacyLocalChart>? =
                gson.fromJson(legacy.readText(), Array<LegacyLocalChart>::class.java)?.toList()
            val migrated = legacyCharts.orEmpty()
                .filter { it.name != null }
                .map { chart ->
                    Playlist(
                        id = chart.id ?: "pl_local_${System.currentTimeMillis()}_${_playlists.value.size}",
                        name = chart.name.orEmpty(),
                        createdAtMs = chart.createdAtMs,
                        songs = chart.songs ?: emptyList(),
                        origin = Playlist.ORIGIN_IMPORT
                    )
                }
            if (migrated.isNotEmpty()) {
                mutate { it + migrated }
            }
            if (!legacy.delete()) {
                Log.w(tag, "delete migrated local_charts.json failed")
            }
            Log.i(tag, "migrated ${migrated.size} local charts from local_charts.json")
        } catch (error: Exception) {
            Log.w(tag, "migrate local_charts.json failed, keep legacy file", error)
        }
    }

    /** ag 版 LocalChartStore 的旧 JSON 形态（仅迁移解析用） */
    private data class LegacyLocalChart(
        val id: String? = null,
        val name: String? = null,
        val createdAtMs: Long = 0L,
        val songs: List<StorableSong>? = null
    )

    suspend fun rename(id: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext false
        val existed = _playlists.value.any { it.id == id }
        if (!existed) return@withContext false
        mutate { list ->
            list.map { if (it.id == id) it.copy(name = trimmed) else it }
        }
        true
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val before = _playlists.value
        if (before.none { it.id == id }) return@withContext false
        mutate { list -> list.filterNot { it.id == id } }
        true
    }

    /**
     * 把一首歌加进歌单（按 uid 去重，重复返回 false）。
     *
     * uid = `platformId:platformSongId`：platformId 未注册时视为不可加（正常不会发生，
     * 因为 Song 本身来自已注册平台）。
     */
    suspend fun addSong(playlistId: String, song: Song): Boolean = withContext(Dispatchers.IO) {
        val storable = song.toStorableSong()
        addStorable(playlistId, storable)
    }

    suspend fun addStorable(playlistId: String, storable: StorableSong): Boolean =
        withContext(Dispatchers.IO) {
            val uid = "${storable.platformId}:${storable.platformSongId}"
            val found = _playlists.value.firstOrNull { it.id == playlistId } ?: return@withContext false
            if (found.containsUid(uid)) return@withContext false
            mutate { list ->
                list.map {
                    if (it.id == playlistId) it.copy(songs = it.songs + storable) else it
                }
            }
            true
        }

    /** 从歌单移除一首歌；uid = `platformId:platformSongId`。歌不存在视为已成功。 */
    suspend fun removeSong(playlistId: String, uid: String): Boolean = withContext(Dispatchers.IO) {
        val found = _playlists.value.firstOrNull { it.id == playlistId } ?: return@withContext false
        if (found.songs.none { "${it.platformId}:${it.platformSongId}" == uid }) {
            return@withContext false
        }
        mutate { list ->
            list.map {
                if (it.id == playlistId) it.copy(songs = it.songs.filterNot { s -> "${s.platformId}:${s.platformSongId}" == uid }) else it
            }
        }
        true
    }

    /**
     * 把歌单内歌曲按标题自然序重排（`"2" < "10"`，中文按拼音）。
     *
     * 给存量歌单一个「一键排序」入口：早期版本挂载/导入时没排序，歌单里是乱序，
     * 之后再挂载同一文件夹会因 uid 去重全部跳过、顺序永远不会自己变好——
     * 这个接口把已存储的顺序原地重排并持久化，供详情页「按名称排序」按钮调用。
     */
    suspend fun sortByTitle(playlistId: String): Boolean = withContext(Dispatchers.IO) {
        val found = _playlists.value.firstOrNull { it.id == playlistId } ?: return@withContext false
        val sorted = found.songs.sortedWith(
            Comparator { a, b -> NaturalOrder.compare(a.title, b.title) }
        )
        if (sorted == found.songs) return@withContext true // 已经有序，无需落盘
        mutate { list ->
            list.map { if (it.id == playlistId) it.copy(songs = sorted) else it }
        }
        true
    }

    /**
     * 把歌单按「本批歌曲的传入顺序」原地重排（批量刷新歌单镜像）。
     *
     * 语义：用户在挂载/导入同一个文件夹并选择已有歌单时，若这批歌**全部已存在**
     * （`addSong` 去重会跳过），歌单顺序永远不会更新——这里把本批涉及到的歌
     * 按本次传入顺序排到前面，**歌单里不属于本批的歌保持原有相对顺序跟在后面**。
     * 调用方保证传入顺序已按自然序（[com.lelemusic.util.NaturalOrder]）排好。
     */
    suspend fun reorderBatch(playlistId: String, orderedSongs: List<Song>): Boolean =
        withContext(Dispatchers.IO) {
            val found = _playlists.value.firstOrNull { it.id == playlistId }
                ?: return@withContext false
            if (orderedSongs.isEmpty()) return@withContext true
            // 只取「确实已在歌单里」的本批歌（理论上 added==0 时全部命中）
            val batch = orderedSongs.mapNotNull { song ->
                val s = song.toStorableSong()
                if (found.containsUid(found.songUid(s))) s else null
            }
            if (batch.isEmpty()) return@withContext true
            val batchUids = batch.map { found.songUid(it) }.toSet()
            val rest = found.songs.filterNot { found.songUid(it) in batchUids }
            val next = batch + rest
            if (next == found.songs) return@withContext true // 顺序没变化，不落盘
            mutate { list ->
                list.map { if (it.id == playlistId) it.copy(songs = next) else it }
            }
            true
        }

    // ------------------------------------------------------------------
    // 底层
    // ------------------------------------------------------------------

    /** 内存更新 + 原子落盘；落盘失败不影响内存态（下次写盘会再试） */
    private fun mutate(transform: (List<Playlist>) -> List<Playlist>) {
        val next = transform(_playlists.value)
        _playlists.value = next
        persist(next)
    }

    private fun persist(list: List<Playlist>) {
        try {
            val payload = gson.toJson(LibraryFile(version = 1, playlists = list))
            val tmp = File(file.parentFile, "playlists.json.tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                // Windows 上 renameTo 目标已存在时可能失败：先删旧再改名兜底
                if (file.exists() && !file.delete()) {
                    Log.e(tag, "delete old playlists.json failed")
                }
                if (!tmp.renameTo(file)) {
                    Log.e(tag, "rename tmp -> playlists.json failed")
                }
            }
        } catch (error: Exception) {
            Log.e(tag, "persist playlists.json failed", error)
        }
    }
}

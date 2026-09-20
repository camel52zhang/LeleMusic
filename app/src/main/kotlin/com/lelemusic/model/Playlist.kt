package com.lelemusic.model

/**
 * 自建歌单的持久化歌曲 DTO。
 *
 * [Song.platform] 是内存态接口对象（不可序列化），落盘时必须换成稳定字符串
 * [platformId]（`"netease"` / `"kugou"` / `"local"`），读盘时经
 * `AppGraph.platformById` 反查还原成 [Platform]。
 *
 * [Song.playable] 是解析期的瞬时结论，不落盘；恢复后的歌统一 `UNKNOWN`，
 * 播放时由取链/拉流重新判定（本地文件不存在或权限失效会在播放时报错兜底）。
 */
data class StorableSong(
    val platformId: String,
    val platformSongId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val extras: Map<String, String> = emptyMap()
)

/** 一条自建歌单。songs 保持用户手动排列的顺序。 */
data class Playlist(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val songs: List<StorableSong> = emptyList(),

    /**
     * 歌单来源（2026-09-12 起支持三类）：
     * - [ORIGIN_MANUAL]（null / 缺字段，兼容旧数据）= 用户手动新建；
     * - [ORIGIN_IMPORT] = 「导入本地音乐」命名产生，带「导入」徽标；
     * - [ORIGIN_MOUNT]  = 「挂载本地文件夹」命名产生，带「挂载」徽标。
     *
     * import / mount 来源的歌单会同时作为首页「本地音乐」Tab 的下一级榜单展示
     * （见 `LocalRankSource`），并且在我的歌单列表里可正常重命名 / 删除。
     */
    val origin: String? = null
) {

    /** 歌单内去重主键：与 [Song.uid] 同构（`platformId:platformSongId`） */
    fun songUid(song: StorableSong): String = "${song.platformId}:${song.platformSongId}"

    /** 是否已含某 uid 的歌曲（去重用，uid = `platformId:platformSongId`） */
    fun containsUid(uid: String): Boolean =
        songs.any { "${it.platformId}:${it.platformSongId}" == uid }

    /** 是否为本地歌单（导入 / 挂载产生；会出现在「本地音乐」Tab 下一级） */
    val isLocalChart: Boolean
        get() = origin == ORIGIN_IMPORT || origin == ORIGIN_MOUNT

    companion object {
        const val ORIGIN_MANUAL = "manual"
        const val ORIGIN_IMPORT = "import"
        const val ORIGIN_MOUNT = "mount"
    }
}

/** 歌单持久化文件容器（JSON 顶层对象，便于将来加字段不破坏旧数据） */
data class LibraryFile(
    val version: Int = 1,
    val playlists: List<Playlist> = emptyList()
)

// ---------------------------------------------------------------------------
// 内存态 ↔ 持久化态转换
// ---------------------------------------------------------------------------

/** 持久化 DTO → 领域模型；platformId 未注册（平台被移除）时返回 null，调用方跳过 */
fun StorableSong.toSong(platformById: Map<String, Platform>): Song? {
    val platform = platformById[platformId] ?: return null
    return Song(
        uid = "${platform.id}:${platformSongId}",
        platform = platform,
        platformSongId = platformSongId,
        extras = extras,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        rank = 0,
        playable = PlayableStatus.UNKNOWN
    )
}

/** 领域模型 → 持久化 DTO（只保留稳定字段） */
fun Song.toStorableSong(): StorableSong = StorableSong(
    platformId = platform.id,
    platformSongId = platformSongId,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    coverUrl = coverUrl,
    extras = extras
)

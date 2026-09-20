package com.lelemusic.model

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lelemusic.core.common.LELE_SCHEME

/**
 * 统一歌曲领域模型。
 *
 * 各平台字段差异极大（网易用数字 `id`、酷狗用 `hash`），
 * 差异部分一律塞进 [extras]，由各平台 Resolver 自行解读（架构文档 §3.3）。
 *
 * @property uid            `"${platform.id}:${platformSongId}"`，进程内唯一主键
 * @property platformSongId 平台侧主键：网易=`id`、酷狗=`hash`
 * @property extras         平台私有解析上下文
 * @property durationMs     时长（毫秒）；未知为 0（UI 显示 `--:--`）
 * @property coverUrl       专辑图；可能为 null，UI 必须能处理
 * @property rank           榜单排名，从 1 开始
 * @property playable       可播性预判
 */
data class Song(
    val uid: String,
    val platform: Platform,
    val platformSongId: String,
    val extras: Map<String, String> = emptyMap(),
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val rank: Int,
    val playable: PlayableStatus = PlayableStatus.UNKNOWN
) {

    /** [extras] 取值语法糖：缺失返回空串，绝不返回 null */
    fun extra(key: String): String = extras[key].orEmpty()
}

/**
 * Song → MediaItem。
 *
 * - 云端平台：uri 是占位地址 `lelemusic://<uid>`，**不含真实直链**：
 *   真实 URL 由 T03 的 `ResolveDataSpecResolver` 在每次读流时实时解析
 *   （网易直链 20 分钟过期，绝不能预取后长期持有）；
 * - 本地平台（[LocalMedia.PLATFORM_ID]）：直接放真实 `content://` / `file://` uri，
 *   `ResolveDataSpecResolver` 对非占位 scheme 原样放行，不再取链。
 *
 * 注：T03 的 `MediaItemFactory` 应直接委托本扩展函数，避免两份实现。
 */
fun Song.toMediaItem(): MediaItem {
    val uri = if (platform.id == LocalMedia.PLATFORM_ID) {
        Uri.parse(extra(LocalMedia.EXTRA_FILE_URI))
    } else {
        Uri.parse("$LELE_SCHEME://$uid")
    }
    return MediaItem.Builder()
        .setMediaId(uid)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(coverUrl?.let { Uri.parse(it) })
                .build()
        )
        .build()
}

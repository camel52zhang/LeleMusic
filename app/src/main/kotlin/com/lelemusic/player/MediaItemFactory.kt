package com.lelemusic.player

import androidx.media3.common.MediaItem
import com.lelemusic.model.Song
import com.lelemusic.model.toMediaItem

/**
 * `Song` → `MediaItem` 的唯一转换入口（T04 / T05 只准用这里）。
 *
 * 实现**完全委托**给 `model/Song.kt` 里已有的 `Song.toMediaItem()`，
 * 本类不做第二份实现——占位 URI 与元数据组装的口径必须全局只有一处
 * （架构文档 §7.10 / T03 要点 6）。
 *
 * 产出的 `MediaItem`：
 * - `mediaId = Song.uid`（`${platform}:${platformSongId}`），全链路用 uid 对齐；
 * - `uri     = lelemusic://<uid>` **占位地址**，真实直链在 `ResolveDataSpecResolver` 里懒解析；
 * - `mediaMetadata` 已带 title / artist / albumTitle / artworkUri，
 *   通知栏与锁屏控件直接从这里取文案，不需要再读 `TrackStore`。
 */
object MediaItemFactory {

    /** 单曲 → MediaItem */
    fun from(song: Song): MediaItem = song.toMediaItem()

    /** 歌曲列表 → MediaItem 列表（保持入参顺序，播放队列顺序 = 榜单顺序） */
    fun from(songs: List<Song>): List<MediaItem> = songs.map { song -> song.toMediaItem() }
}

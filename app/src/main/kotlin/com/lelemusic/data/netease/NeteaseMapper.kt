package com.lelemusic.data.netease

import com.lelemusic.core.common.normalizeToHttps
import com.lelemusic.data.remote.dto.NeteaseTrack
import com.lelemusic.model.PlayableStatus
import com.lelemusic.model.Song

/** Song.extras：fee 字段（0 免费 / 8 VIP / 1 付费 / 4 付费专辑） */
private const val EX_NE_FEE = "ne.fee"

/** Song.extras：专辑图原始地址 */
private const val EX_NE_PIC = "ne.pic"

/**
 * 网易云 DTO → 领域模型（架构文档 T02 要点：`fee` → `PlayableStatus` 预判）。
 *
 * `fee` 编码（api-feasibility A2.1）：
 * | fee | 含义 | 能否拿直链 |
 * |---|---|---|
 * | 0 | 免费 | ✅ |
 * | 8 | VIP / 会员高音质 | ✅ 实测 4/4 通过 |
 * | 1 | 付费单曲 / 数字专辑 | ❌ `code:-110` |
 * | 4 | 付费专辑 | ❓ 推测同 1 |
 */
object NeteaseMapper {

    /** 判定为「拿不到直链」的 fee 值 */
    private val BLOCKED_FEES = setOf(1, 4)

    fun toSongs(tracks: List<NeteaseTrack>, startRank: Int = 1): List<Song> {
        val result = ArrayList<Song>(tracks.size)
        tracks.forEachIndexed { index, track ->
            toSong(track, startRank + index)?.let { result.add(it) }
        }
        return result
    }

    /** 缺 id 的条目直接丢弃（没有 id 无法取链） */
    fun toSong(track: NeteaseTrack, rank: Int): Song? {
        val id = track.id ?: return null
        val fee = track.fee ?: 0

        val title = track.name.orEmpty()
        val artist = track.artists
            ?.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
            ?.joinToString("、")
            .orEmpty()
        val albumName = track.album?.name.orEmpty()
        val picUrl = track.album?.picUrl?.takeIf { it.isNotBlank() }

        return Song(
            uid = "${NeteasePlatform.id}:$id",
            platform = NeteasePlatform,
            platformSongId = id.toString(),
            extras = mapOf(
                EX_NE_FEE to fee.toString(),
                EX_NE_PIC to picUrl.orEmpty()
            ),
            title = title,
            artist = artist,
            album = albumName,
            // 网易 duration 已是**毫秒**，与酷狗榜单的秒不同，勿再乘 1000
            durationMs = track.duration ?: 0L,
            coverUrl = picUrl?.let { normalizeToHttps(it) },
            rank = rank,
            playable = predictPlayable(fee)
        )
    }

    /**
     * 付费墙预判：让用户**在点之前**就知道这首歌播不了，而不是点了才失败
     * （PRD 要求，api-feasibility B2.4 建议）。
     */
    fun predictPlayable(fee: Int): PlayableStatus =
        if (fee in BLOCKED_FEES) PlayableStatus.UNAVAILABLE else PlayableStatus.UNKNOWN
}

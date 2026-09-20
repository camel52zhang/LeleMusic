package com.lelemusic.data.kugou

import com.lelemusic.core.common.normalizePlayUrl
import com.lelemusic.data.remote.dto.KugouRankSong
import com.lelemusic.model.PlayableStatus
import com.lelemusic.model.Song

/** Song.extras：320kbps hash */
private const val EX_KG_HASH320 = "kg.hash320"

/** Song.extras：无损 hash */
private const val EX_KG_HASHSQ = "kg.hashsq"

/** Song.extras：榜单返回的时长（秒），可能为空 */
private const val EX_KG_DURATION = "kg.duration"

/** 封面 `album_sizable_cover` 的 `{size}` 占位符替换值（与取链层 album_img 同款，不替换会 404） */
private const val COVER_SIZE = 150

/**
 * 酷狗 DTO → 领域模型。
 *
 * ⚠️ 酷狗的 `320hash` / `sqhash` 走 `@SerializedName` 映射（架构文档 §7.4-2），
 * **禁止**用反引号字段名——那样 Gson 反射行为不可靠。
 *
 * 已知限制（OQ-3 / api-feasibility B3.3，2026-09-12 实测更新）：
 * - `getSongInfo.php` 实测**只出 128kbps**，传 320hash 无效；
 * - 榜单接口**不返回专辑名**（album 字段缺失，UI 显示「未知专辑」）；
 * - 榜单接口**带专辑封面** `album_sizable_cover`（此前误判为无封面），
 *   `trans_param.union_cover` 可作备用（Gson 只映射声明过的字段，备用源暂不解析）。
 */
object KugouMapper {

    /**
     * 榜单封面归一：`{size}` 占位符替换（不替换 404）+ http → https（Android 9+ 禁明文）。
     * 与 [KugouUrlResolver.buildCoverUrl] 同款规则，独立实现避免 UI 层反向依赖取链类。
     */
    fun buildChartCoverUrl(albumSizableCover: String?): String? {
        val raw = albumSizableCover?.takeIf { it.isNotBlank() } ?: return null
        return normalizePlayUrl(raw.replace("{size}", COVER_SIZE.toString()))
    }

    fun toSongs(items: List<KugouRankSong>, startRank: Int = 1): List<Song> {
        val result = ArrayList<Song>(items.size)
        items.forEachIndexed { index, item ->
            toSong(item, startRank + index)?.let { result.add(it) }
        }
        return result
    }

    /** 缺 hash 的条目直接丢弃（没有 hash 无法取链） */
    fun toSong(item: KugouRankSong, rank: Int): Song? {
        val hash = item.hash?.takeIf { it.isNotBlank() } ?: return null

        val title = item.songname?.takeIf { it.isNotBlank() }
            ?: parseTitleFromFileName(item.filename)
        val artist = item.singername?.takeIf { it.isNotBlank() }
            ?: parseArtistFromFileName(item.filename)
        val durationSec = item.duration ?: 0

        return Song(
            uid = "${KugouPlatform.id}:$hash",
            platform = KugouPlatform,
            platformSongId = hash,
            extras = mapOf(
                EX_KG_HASH320 to item.hash320.orEmpty(),
                EX_KG_HASHSQ to item.sqhash.orEmpty(),
                EX_KG_DURATION to durationSec.toString()
            ),
            title = title,
            artist = artist,
            // 酷狗榜单不返回专辑名（接口限制），UI orUnknown() 兜底
            album = "",
            durationMs = durationSec.toLong() * 1_000L,
            // 2026-09-12 实测：榜单带 album_sizable_cover，直接映射（此前误判为无封面）
            coverUrl = buildChartCoverUrl(item.albumSizableCover),
            rank = rank,
            playable = PlayableStatus.UNKNOWN
        )
    }

    /**
     * `filename` 形如「周杰伦 - 千山万水」。
     *
     * 拆不出来时返回空串（UI 用 `orUnknown()` 兜底），绝不返回 "null" 文本。
     */
    fun parseTitleFromFileName(fileName: String?): String {
        val raw = fileName?.takeIf { it.isNotBlank() } ?: return ""
        val index = raw.indexOf(" - ")
        return if (index >= 0) raw.substring(index + 3).trim() else raw.trim()
    }

    fun parseArtistFromFileName(fileName: String?): String {
        val raw = fileName?.takeIf { it.isNotBlank() } ?: return ""
        val index = raw.indexOf(" - ")
        return if (index >= 0) raw.substring(0, index).trim() else ""
    }
}

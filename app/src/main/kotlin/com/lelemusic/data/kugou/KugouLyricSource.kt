package com.lelemusic.data.kugou

import android.util.Log
import com.lelemusic.core.common.errorCodeOf
import com.lelemusic.data.remote.KugouApi
import com.lelemusic.data.source.LyricSource
import com.lelemusic.model.LyricParsers
import com.lelemusic.model.LyricResult
import com.lelemusic.model.Platform
import com.lelemusic.model.Song
import kotlinx.coroutines.CancellationException
import java.util.Base64

/** 酷狗歌词：搜索候选（第一步） */
private const val LYRIC_SEARCH_URL = "https://lyrics.kugou.com/search"

/** 酷狗歌词：下载内容（第二步，content 为 base64 的 LRC） */
private const val LYRIC_DOWNLOAD_URL = "https://lyrics.kugou.com/download"

/** 酷狗关键字搜歌（M2c 歌词补漏用，2026-09 实测匿名可用） */
private const val SONG_SEARCH_URL = "https://songsearch.kugou.com/song_search_v2"

/**
 * 酷狗歌词源（两步取词，2026-09 实测可用）：
 *
 * 1. `search` 用曲名 + hash 拿到候选列表；
 * 2. 取**首个含 accesskey 的候选**调 `download`，把 base64 的 LRC 解出来。
 *
 * M2c 兜底：主链路（songId=hash）无词 / 失败时，用 `title + artist` 在酷狗曲库搜出
 * 「有词版本」的 hash，再走同一取词链路。
 */
class KugouLyricSource(
    private val api: KugouApi
) : LyricSource {

    override val platform: Platform = KugouPlatform

    override suspend fun lyric(song: Song): LyricResult {
        val hash = song.platformSongId
        if (hash.isBlank()) {
            return LyricResult.Error("E_PARSE", "kugou hash blank")
        }
        val keyword = song.title.ifBlank { hash }
        return fetchLyricByHash(keyword = keyword, hash = hash, durationMs = song.durationMs)
    }

    override suspend fun lyricBySearch(title: String, artist: String, durationMs: Long): LyricResult {
        val keyword = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (keyword.isBlank()) return LyricResult.NoLyric()
        return try {
            val resp = api.searchSongV2(url = SONG_SEARCH_URL, keyword = keyword, pagesize = 5)
            val items = resp.data?.lists.orEmpty()
            // 候选挑选：歌名精确一致优先，否则取第一条（接口一般把最匹配的放前面）
            val best = items.firstOrNull { it.songName?.trim() == title.trim() }
                ?: items.firstOrNull()
            val hash = best?.primaryHash ?: run {
                Log.i(TAG, "kugou lyric search no song keyword=$keyword")
                return LyricResult.NoLyric()
            }
            Log.i(TAG, "kugou lyric search hit keyword=$keyword -> hash=$hash")
            fetchLyricByHash(keyword = title, hash = hash, durationMs = durationMs)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Log.w(TAG, "kugou lyric search failed keyword=$keyword: ${throwable.message}", throwable)
            LyricResult.Error(errorCodeOf(throwable), throwable.message.orEmpty())
        }
    }

    /** 主链路：hash → LRC（Success / NoLyric / Error 三态收敛） */
    private suspend fun fetchLyricByHash(keyword: String, hash: String, durationMs: Long): LyricResult {
        return try {
            val search = api.lyricSearch(
                url = LYRIC_SEARCH_URL,
                keyword = keyword,
                duration = durationMs,
                hash = hash
            )
            val candidates = search.candidates.orEmpty()
                .filter { it.id != null && !it.accesskey.isNullOrBlank() }
            val best = candidates.firstOrNull()
            if (best == null) {
                Log.i(TAG, "kugou lyric no candidate hash=$hash")
                return LyricResult.NoLyric()
            }
            val download = api.lyricDownload(
                url = LYRIC_DOWNLOAD_URL,
                id = best.id ?: return LyricResult.NoLyric(),
                accesskey = best.accesskey.orEmpty()
            )
            val content = download.content
            if (content.isNullOrBlank()) {
                Log.i(TAG, "kugou lyric empty content hash=$hash")
                return LyricResult.NoLyric()
            }
            val text = decodeLrc(content)
            if (text.isBlank()) return LyricResult.NoLyric()
            val lyric = LyricParsers.parseLrc(text)
            if (lyric.isEmpty) return LyricResult.NoLyric()
            Log.i(TAG, "kugou lyric ok hash=$hash lines=${lyric.lines.size}")
            LyricResult.Success(lyric)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Log.w(TAG, "kugou lyric failed hash=$hash: ${throwable.message}", throwable)
            LyricResult.Error(errorCodeOf(throwable), throwable.message.orEmpty())
        }
    }

    /** `content` 理论上是 base64；解码失败（个别候选直接给明文）时原样返回 */
    private fun decodeLrc(content: String): String = try {
        String(Base64.getDecoder().decode(content), Charsets.UTF_8)
    } catch (invalid: IllegalArgumentException) {
        content
    }

    private companion object {
        const val TAG = "KugouLyricSource"
    }
}

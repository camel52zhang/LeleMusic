package com.lelemusic.data.netease

import android.util.Log
import com.lelemusic.core.common.errorCodeOf
import com.lelemusic.data.remote.NeteaseApi
import com.lelemusic.data.source.LyricSource
import com.lelemusic.model.LyricParsers
import com.lelemusic.model.LyricResult
import com.lelemusic.model.Platform
import com.lelemusic.model.Song
import kotlinx.coroutines.CancellationException

/** 网易云歌词接口（免加密免 Cookie，api-feasibility A2.x 2026-09 实测可用） */
private const val LYRIC_URL = "https://music.163.com/api/song/lyric"

/** 网易云关键字搜歌（M2c 歌词补漏，2026-09 实测匿名可用） */
private const val SEARCH_URL = "https://music.163.com/api/search/get/web"

/**
 * 网易云歌词源：`/api/song/lyric?id=<neteaseId>&lv=-1&kv=-1&tv=-1`。
 *
 * 网易云歌词接口对**付费墙歌曲同样返回完整 LRC**（真机日志里甲乙丙丁是付费试听，
 * 歌词照常可取），只有纯音乐（`nolyric`）或未收录（`uncollected`）才无词。
 *
 * M2c 兜底：主链路无词 / 失败时按 `title + artist` 搜歌，命中候选 id 后走同一取词链路
 * （榜单给的 songId 偶尔指向无词版本，同曲目的其它版本往往有词）。
 */
class NeteaseLyricSource(
    private val api: NeteaseApi
) : LyricSource {

    override val platform: Platform = NeteasePlatform

    override suspend fun lyric(song: Song): LyricResult {
        val id = song.platformSongId.toLongOrNull()
        if (id == null) {
            Log.w(TAG, "netease lyric: non-numeric id=${song.platformSongId}")
            return LyricResult.Error("E_PARSE", "netease song id non-numeric")
        }
        return fetchLyricById(id)
    }

    override suspend fun lyricBySearch(title: String, artist: String, durationMs: Long): LyricResult {
        val keyword = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        if (keyword.isBlank()) return LyricResult.NoLyric()
        return try {
            val resp = api.searchSong(url = SEARCH_URL, keyword = keyword, limit = 5)
            val candidates = resp.result?.songs.orEmpty().filter { it.id != null }
            val best = candidates.firstOrNull { it.name?.trim() == title.trim() }
                ?: candidates.firstOrNull()
            val id = best?.id ?: run {
                Log.i(TAG, "netease lyric search no song keyword=$keyword")
                return LyricResult.NoLyric()
            }
            Log.i(TAG, "netease lyric search hit keyword=$keyword -> id=$id")
            fetchLyricById(id)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Log.w(TAG, "netease lyric search failed keyword=$keyword: ${throwable.message}", throwable)
            LyricResult.Error(errorCodeOf(throwable), throwable.message.orEmpty())
        }
    }

    /** 主链路：id → LRC（Success / NoLyric / Error 三态收敛） */
    private suspend fun fetchLyricById(id: Long): LyricResult {
        return try {
            val resp = api.lyric(url = LYRIC_URL, id = id)
            if (resp.nolyric == true || resp.uncollected == true) {
                Log.i(TAG, "netease lyric nolyric/uncollected id=$id")
                return LyricResult.NoLyric()
            }
            val text = resp.lrc?.lyric?.trim().orEmpty()
            if (text.isBlank()) {
                Log.i(TAG, "netease lyric empty text id=$id")
                return LyricResult.NoLyric()
            }
            val lyric = LyricParsers.parseLrc(text)
            if (lyric.isEmpty) return LyricResult.NoLyric()
            Log.i(TAG, "netease lyric ok id=$id lines=${lyric.lines.size}")
            LyricResult.Success(lyric)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Log.w(TAG, "netease lyric failed id=$id: ${throwable.message}", throwable)
            LyricResult.Error(errorCodeOf(throwable), throwable.message.orEmpty())
        }
    }

    private companion object {
        const val TAG = "NeteaseLyricSource"
    }
}

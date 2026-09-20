package com.lelemusic.data.local

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lelemusic.data.source.LyricSource
import com.lelemusic.model.LocalMedia
import com.lelemusic.model.LyricParsers
import com.lelemusic.model.LyricResult
import com.lelemusic.model.Platform
import com.lelemusic.model.Song
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException

/**
 * 本地旁路字幕歌词源。
 *
 * 本地歌曲的 [Song.extras] 里若带有 `lyricUri`（同目录同名 .lrc/.srt，由导入器配对写入），
 * 播放页歌词面板就能显示它——本地音乐与云平台歌词走**同一条** [LyricSource] 链路。
 *
 * **字符集策略**：先按严格 UTF-8 解码，遇 MalformedInputException 立即降级 GB18030。
 * 中文 .lrc / .srt 文件历史上绝大多数是 GBK/GB2312/GB18030 编码（早期播放器写入习惯），
 * UTF-8 严格解析遇首字节 0xCE 0xC4 这种 GBK 首字节 → REPLACE 出 U+FFFD 出现"中文乱码/方块"，
 * 这里用 [CodingErrorAction.REPORT] 主动抛错，捕获后切 GB18030（GBK 超集，向下兼容且支持 CJK 全字符）。
 * 真正 UTF-8 文件（含 BOM 或仅 ASCII）严格解码不会抛错，直接走 UTF-8 分支，性能无损耗。
 *
 * 读文件走 [Context.getContentResolver]（SAF uri），主线程不可读，由调用方协程上下文保证。
 */
class LocalSidecarLyricSource(
    private val context: Context
) : LyricSource {

    override val platform: Platform = LocalPlatform

    override suspend fun lyric(song: Song): LyricResult {
        val uriText = song.extra(LocalMedia.EXTRA_LYRIC_URI)
        if (uriText.isBlank()) return LyricResult.NoLyric()
        val uri = Uri.parse(uriText)
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return LyricResult.NoLyric()
            val text = decodeLyricBytes(bytes)
            if (text.isBlank()) return LyricResult.NoLyric()
            val lyric = LyricParsers.parseFile(displayNameOf(uri), text)
            if (lyric.isEmpty) return LyricResult.NoLyric()
            Log.i(TAG, "local sidecar lyric ok uri=$uriText lines=${lyric.lines.size}")
            LyricResult.Success(lyric)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Log.w(TAG, "local sidecar lyric failed uri=$uriText: ${throwable.message}", throwable)
            LyricResult.Error("E_IO", throwable.message.orEmpty())
        }
    }

    /**
     * 严格 UTF-8 → GB18030 兜底。遇 MalformedInputException 立即降级（不再试 Big5/UTF-16，
     * GB18030 覆盖 GB2312/GBK/GB18030 全字符集与 Unicode 转换互通，是中文 .lrc 的事实标准）。
     */
    private fun decodeLyricBytes(bytes: ByteArray): String {
        val strictUtf8 = StandardCharsets.UTF_8.newDecoder().apply {
            onMalformedInput(CodingErrorAction.REPORT)
            onUnmappableCharacter(CodingErrorAction.REPORT)
        }
        return try {
            strictUtf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (malformed: MalformedInputException) {
            Log.i(TAG, "lyric not utf-8 (${bytes.size}B), fallback GB18030")
            Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString()
        }
    }

    private fun displayNameOf(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    } catch (throwable: Throwable) {
        null
    }

    private companion object {
        const val TAG = "LocalSidecarLyricSource"
    }
}
package com.lelemusic.model

/**
 * 单行歌词。
 *
 * @param timeMs 该行开始时间（毫秒）；纯文本歌词（无时间轴）统一为 `-1`
 * @param text   该行文本；多行字幕用 `\n` 连接
 */
data class LyricLine(
    val timeMs: Long = -1L,
    val text: String
)

/**
 * 一首歌的歌词。
 *
 * @param lines  全部歌词行
 * @param synced true = 带时间戳（播放页随进度滚动高亮）；false = 纯文本（无时间轴）
 */
class Lyric(
    val lines: List<LyricLine>,
    val synced: Boolean
) {
    val isEmpty: Boolean get() = lines.none { it.text.isNotBlank() }

    /**
     * 播放到 [ms] 时当前应高亮的行下标。
     *
     * - 空歌词 / 未同步歌词（[synced] = false）返回 -1；
     * - 否则返回「开始时间 <= ms 的最后一行」。
     */
    fun indexAt(ms: Long): Int {
        if (lines.isEmpty() || !synced) return -1
        var index = 0
        while (index < lines.lastIndex && lines[index + 1].timeMs <= ms) index++
        return index
    }
}

/**
 * 平台歌词加载结果（data 层产出，UI 层再翻译成自己的展示状态）。
 */
sealed interface LyricResult {

    /** 成功拿到歌词 */
    data class Success(val lyric: Lyric) : LyricResult

    /** 接口正常但没有词（纯音乐 / 未收录 / 无词） */
    data class NoLyric(val reason: String = "") : LyricResult

    /** 网络 / 解析失败（错误码供日志定位，文案由 UI 统一给） */
    data class Error(val errorCode: String, val message: String = "") : LyricResult
}

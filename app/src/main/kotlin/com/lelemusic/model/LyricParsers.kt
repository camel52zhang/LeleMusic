package com.lelemusic.model

/**
 * LRC / SRT / 纯文本歌词解析器（纯 Kotlin，无 Android 依赖，可在 JVM 单测）。
 *
 * 解析原则：**只提取「能滚动高亮的同步行」**，元信息行（`[ti:]`/`[ar:]`/SRT 序号行等）
 * 一律丢弃；纯文本（无任何时间戳）退化为整段静态歌词，[Lyric.synced] = false。
 */
object LyricParsers {

    // -----------------------------------------------------------------------
    // LRC
    // -----------------------------------------------------------------------

    /** `[mm:ss.xx]` / `[mm:ss:xxx]` 三种小数分隔都兼容；一行可挂多个时间戳 */
    private val LRC_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

    /**
     * 解析 LRC 文本。
     *
     * - `[mm:ss.xx]` 的小数是**厘秒**（2 位），换算 ms = ×10；
     *   若出现 3 位则按毫秒原值处理（部分客户端写法）。
     * - 同一行多个时间戳 → 拆成多行（同一文本重复输出）。
     */
    fun parseLrc(raw: String): Lyric {
        val out = ArrayList<LyricLine>(32)
        var foundTimestamp = false
        for (lineRaw in raw.lineSequence()) {
            val tags = LRC_TAG.findAll(lineRaw).toList()
            if (tags.isEmpty()) continue
            foundTimestamp = true
            val text = lineRaw.substring(tags.last().range.last + 1).trim()
            if (text.isBlank()) continue
            for (tag in tags) {
                val min = tag.groupValues[1].toLongOrNull() ?: 0L
                val sec = tag.groupValues[2].toLongOrNull() ?: 0L
                val frac = tag.groupValues[3]
                val ms = min * 60_000L + sec * 1_000L + fracMs(frac)
                out.add(LyricLine(timeMs = ms, text = text))
            }
        }
        if (!foundTimestamp || out.isEmpty()) return fromPlain(raw)
        out.sortBy { it.timeMs }
        return Lyric(lines = out, synced = true)
    }

    // -----------------------------------------------------------------------
    // SRT
    // -----------------------------------------------------------------------

    private val SRT_TIME = Regex(
        "(\\d{1,3}):(\\d{1,2}):(\\d{1,2})[,.]\\d{1,3}\\s*-->\\s*" +
            "(\\d{1,3}):(\\d{1,2}):(\\d{1,2})[,.]\\d{1,3}"
    )

    /**
     * 解析 SRT 字幕文本。
     *
     * 每个块：可选序号行 → `hh:mm:ss,mmm --> ...` 时间行 → 字幕文本行。
     * 取块的**起始时间**作为 [LyricLine.timeMs]，文本行用 `\n` 连接（多行字幕）。
     */
    fun parseSrt(raw: String): Lyric {
        val out = ArrayList<LyricLine>(32)
        for (block in raw.split(Regex("\\R\\s*\\R"))) {
            if (block.isBlank()) continue
            val lines = block.trim().lines()
            val timeIndex = lines.indexOfFirst { SRT_TIME.containsMatchIn(it) }
            if (timeIndex < 0) continue
            val match = SRT_TIME.find(lines[timeIndex]) ?: continue
            val startMs = match.groupValues[1].toLongOrNull()?.let { h ->
                val m = match.groupValues[2].toLongOrNull() ?: 0L
                val s = match.groupValues[3].toLongOrNull() ?: 0L
                h * 3_600_000L + m * 60_000L + s * 1_000L
            } ?: continue
            val text = lines
                .drop(timeIndex + 1)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            if (text.isBlank()) continue
            out.add(LyricLine(timeMs = startMs, text = text))
        }
        if (out.isEmpty()) return fromPlain(raw)
        out.sortBy { it.timeMs }
        return Lyric(lines = out, synced = true)
    }

    // -----------------------------------------------------------------------
    // 统一入口 + 纯文本兜底
    // -----------------------------------------------------------------------

    /**
     * 按文件名后缀决定解析器；无法识别时智能兜底：
     * 内容含 `-->` 视为 SRT，否则按 LRC 处理（LRC 解析器对纯文本自然退回静态歌词）。
     *
     * @return 永不返回 null：解析结果最差是 [Lyric.isEmpty] 为 true 的静态歌词
     */
    fun parseFile(fileName: String?, content: String): Lyric {
        val lower = fileName?.lowercase().orEmpty()
        return when {
            lower.endsWith(".srt") -> parseSrt(content)
            content.contains("-->") -> parseSrt(content)
            else -> parseLrc(content)
        }
    }

    /** 无时间轴文本 → 静态歌词：逐行保留非空行，[Lyric.synced] = false */
    fun fromPlain(raw: String): Lyric {
        val lines = raw.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .map { LyricLine(timeMs = -1L, text = it) }
            .toList()
        return Lyric(lines = lines, synced = false)
    }

    private fun fracMs(frac: String): Long = when (frac.length) {
        3 -> frac.toLongOrNull() ?: 0L
        2 -> (frac.toLongOrNull() ?: 0L) * 10L
        1 -> (frac.toLongOrNull() ?: 0L) * 100L
        else -> 0L
    }
}

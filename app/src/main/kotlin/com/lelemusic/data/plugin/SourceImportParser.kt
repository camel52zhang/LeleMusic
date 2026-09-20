package com.lelemusic.data.plugin

/**
 * 音源清单文本解析器。
 *
 * 用户在真实使用中贴进来的东西有三种形态（都出现在 `api.txt` 里）：
 * 1. 单个 URL：`https://.../xxx.js`
 * 2. 名称 + 分隔符 + URL：`SixYin v1.2.1\thttps://...`（LX-Music 桌面自定义源的导入格式）
 * 3. 整段多行清单：上面两种混排，中间可能有空行
 *
 * 解析策略刻意做「宽进」：只要一行里能抠出一个 http(s) URL 就认，
 * URL 前面的剩余文本（去掉分隔符）当名称——用户手贴的格式本来就五花八门，
 * 严格解析只会把可用行判成无效。
 */
object SourceImportParser {

    private val URL_REGEX = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)

    data class Entry(val name: String?, val url: String)

    data class Result(val entries: List<Entry>, val invalidLines: Int)

    fun parse(text: String): Result {
        if (text.isBlank()) return Result(emptyList(), 0)

        val entries = mutableListOf<Entry>()
        val seenUrls = mutableSetOf<String>()
        var invalid = 0

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // 以 # 开头的行当注释（清单文件里常见）
            if (line.startsWith("#")) continue

            val match = URL_REGEX.find(line)
            if (match == null) {
                invalid++
                continue
            }
            val url = match.value.trim().trimEnd(',', ';', '、')
            if (!url.startsWith("http", ignoreCase = true)) {
                invalid++
                continue
            }

            // 名称 = URL 之前的文本；去掉 Tab/多余空白
            val before = line.substring(0, match.range.first)
                .trim()
                .trim('、', ',', '-', '|')
                .trim()
            val name = before.ifEmpty { null }

            if (seenUrls.add(url)) {
                entries += Entry(name = name, url = url)
            }
        }

        return Result(entries = entries, invalidLines = invalid)
    }

    /** 从 URL 推导一个兜底显示名：`.../sixyin/latest.js` → `sixyin/latest.js` */
    fun nameFromUrl(url: String): String {
        val tail = url.substringAfterLast('/')
        return tail.ifBlank { url }.take(48)
    }
}

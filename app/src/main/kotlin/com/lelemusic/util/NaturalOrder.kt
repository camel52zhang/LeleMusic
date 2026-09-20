package com.lelemusic.util

import java.text.Collator
import java.util.Locale

/**
 * 文件名 / 歌名「自然序」比较器。
 *
 * 把字符串拆成「连续数字段 / 连续非数字段」交替的 token 序列再比较：
 *  - 数字段按 Long 数值比（`"2" < "10"`，而不是字面 `"10" < "2"`）；
 *  - 非数字段按 [nameCollator]（中文按拼音，数字前缀场景稳定）；
 *  - 数字段 vs 非数字段：数字在前，给 `"01 Intro"` 这类格式一个稳定前缀。
 *
 * 典型用途：挂载本地文件夹 / 导入歌曲后按文件名排歌单，
 * 让 `英语口语8000句 1 / 2 / 10 / 12` 按直觉顺序出现，而不是 provider 的乱序。
 */
object NaturalOrder {

    /** 中文按拼音、英文按字母的本地化比较器 */
    private val collator: Collator = Collator.getInstance(Locale.CHINA)

    val comparator: Comparator<String> = Comparator { a, b -> compare(a, b) }

    fun compare(s1: String, s2: String): Int {
        val t1 = tokenize(s1)
        val t2 = tokenize(s2)
        var i = 0
        while (i < t1.size && i < t2.size) {
            val a = t1[i]
            val b = t2[i]
            val cmp: Int = when {
                a is Long && b is Long -> a.compareTo(b)
                a is Long -> -1
                b is Long -> 1
                else -> collator.compare(a as String, b as String)
            }
            if (cmp != 0) return cmp
            i++
        }
        return t1.size.compareTo(t2.size)
    }

    /** 切 [s] 成"数字段 → Long、非数字段 → String"的 token 列表；`"01"` 与 `"1"` 数值相等 */
    private fun tokenize(s: String): List<Any> {
        if (s.isEmpty()) return emptyList()
        val tokens = ArrayList<Any>()
        var i = 0
        val n = s.length
        while (i < n) {
            if (s[i].isDigit()) {
                var j = i
                while (j < n && s[j].isDigit()) j++
                tokens.add(s.substring(i, j).toLong())
                i = j
            } else {
                var j = i
                while (j < n && !s[j].isDigit()) j++
                tokens.add(s.substring(i, j))
                i = j
            }
        }
        return tokens
    }
}

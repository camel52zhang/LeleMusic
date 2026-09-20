package com.lelemusic.core.common

/**
 * 时长格式化：`3:20`（分钟不补零）。
 *
 * 非正数返回 `--:--`，避免 UI 出现 `0:00` 或 `null`（PRD 要求禁止显示 null）。
 */
fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/**
 * 播放器进度格式化：`03:20`（分钟补零，避免进度条文本跳动）。
 */
fun formatClock(ms: Long): String {
    if (ms < 0L) return "00:00"
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

/** 空字符串 / 空白 / null → 返回兜底文案 */
fun String?.orUnknown(fallback: String = "未知"): String =
    if (this.isNullOrBlank()) fallback else this

/** null → 0 */
fun Long?.orZero(): Long = this ?: 0L

/** null → 0 */
fun Int?.orZero(): Int = this ?: 0

/**
 * URL 规范化：把任意形态的地址改写成 `https://` 开头（架构文档 §7.10）。
 *
 * 覆盖三种输入形态：
 * - `https://...`      → 原样返回
 * - `http://...`       → 替换为 `https://`（网易云直链、专辑图必须用这条，Android 9+ 禁明文）
 * - `host/path?query`  → 补 `https://` 前缀（部分平台直链返回无 scheme 的裸地址）
 */
fun normalizeToHttps(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
    return when {
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring("http://".length)
        else -> "https://$trimmed"
    }
}

/**
 * 播放直链规范化（取链器专用）：对 **明文白名单 CDN** 保留原始 `http://` scheme，其余强制 https。
 *
 * **为什么不能直接 `normalizeToHttps` 全转 https**：
 * `res/xml/network_security_config.xml` 显式对 `music.126.net` / `kugou.com` / `gtimg.cn`
 * 开放 `cleartextTrafficPermitted=true`，正是因为这三个 CDN 的直链节点在部分网络下 **https 连不通、
 * 只服务 http**（实测 `m801.music.126.net`：http=200/206，https=connection refused）。
 * 若一律强转 https，ExoPlayer 的 `DefaultHttpDataSource` 会因连不上 CDN 报 `ERROR_CODE_IO_*` →
 * UI 显示「网络异常」；而自检台用 `MediaMetadataRetriever`（系统媒体栈对 https 容忍度不同）会假阳性通过。
 * 因此播放直链必须保留 http，与 network_security_config 的白名单对齐。
 */
private val CLEARTEXT_ALLOWED_HOSTS = setOf("music.126.net", "kugou.com", "gtimg.cn")

fun normalizePlayUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
    val host = runCatching { android.net.Uri.parse(trimmed).host }.getOrNull().orEmpty()
    val cleartext = CLEARTEXT_ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    return if (cleartext) trimmed else normalizeToHttps(trimmed)
}

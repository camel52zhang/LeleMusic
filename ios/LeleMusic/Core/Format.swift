import Foundation

// MARK: - URL 规范化（对齐 Android Format.kt）

/// `http://` → `https://`；裸 host/path 补 `https://`
func normalizeToHttps(_ raw: String) -> String {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty { return trimmed }
    let lower = trimmed.lowercased()
    if lower.hasPrefix("https://") { return trimmed }
    if lower.hasPrefix("http://") {
        return "https://" + trimmed.dropFirst("http://".count)
    }
    return "https://" + trimmed
}

/// 从任意 URL 字符串提取 host（不依赖 URL 完整合法性）
func hostOf(_ raw: String) -> String {
    var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if let idx = s.range(of: "://") { s = String(s[idx.upperBound...]) }
    if let slash = s.firstIndex(of: "/") { s = String(s[..<slash]) }
    if let at = s.firstIndex(of: "@") { s = String(s[s.index(after: at)...]) }
    if let colon = s.firstIndex(of: ":") { s = String(s[..<colon]) }
    return s.lowercased()
}

/// 明文白名单 CDN（与 Android network_security_config 白名单对齐）
private let cleartextAllowedHosts: Set<String> = ["music.126.net", "kugou.com", "gtimg.cn"]

/// 播放直链规范化：白名单 CDN 保留 http，其余强制 https
func normalizePlayUrl(_ raw: String) -> String {
    let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty { return trimmed }
    let host = hostOf(trimmed)
    let cleartext = cleartextAllowedHosts.contains { host == $0 || host.hasSuffix(".\($0)") }
    return cleartext ? trimmed : normalizeToHttps(trimmed)
}

// MARK: - 时长格式化

/// `3:20`（分钟不补零）；非正数返回 `--:--`
func formatDuration(_ ms: Int) -> String {
    guard ms > 0 else { return "--:--" }
    let total = ms / 1000
    return "\(total / 60):\(String(format: "%02d", total % 60))"
}

/// `03:20`（补零，进度条用）
func formatClock(_ seconds: Double) -> String {
    guard seconds.isFinite, seconds >= 0 else { return "00:00" }
    let total = Int(seconds)
    return String(format: "%02d:%02d", total / 60, total % 60)
}

// MARK: - 文件名拆分（对齐 KugouMapper / matchKeywordsOf）

/// `"周杰伦 - 千山万水"` → 歌名
func parseTitleFromFileName(_ fileName: String?) -> String {
    guard let raw = fileName, !raw.isEmpty else { return "" }
    if let idx = raw.range(of: " - ") {
        return String(raw[idx.upperBound...]).trimmingCharacters(in: .whitespaces)
    }
    return raw.trimmingCharacters(in: .whitespaces)
}

/// `"周杰伦 - 千山万水"` → 歌手
func parseArtistFromFileName(_ fileName: String?) -> String {
    guard let raw = fileName, !raw.isEmpty else { return "" }
    if let idx = raw.range(of: " - ") {
        return String(raw[..<idx.lowerBound]).trimmingCharacters(in: .whitespaces)
    }
    return ""
}

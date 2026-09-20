import Foundation

// MARK: - 音源接口地址注册表（对齐 Android ResolverEndpoints）

enum ResolverEndpoints {
    private static let lock = NSLock()
    private static var defaults: [String: String] = [:]
    private static var overrides: [String: String] = [:]

    /// resolver 登记内置默认地址（重复注册无害，后者覆盖）
    static func register(_ strategyId: String, _ defaultUrl: String) {
        lock.lock()
        defer { lock.unlock() }
        guard !defaultUrl.isEmpty else { return }
        defaults[strategyId] = defaultUrl
    }

    /// 回灌用户覆盖（UserDefaults 持久化）
    static func load() {
        lock.lock()
        defer { lock.unlock() }
        let dict = UserDefaults.standard.dictionary(forKey: "endpoint_overrides") as? [String: String] ?? [:]
        overrides = dict
    }

    /// 当前应使用的地址：用户覆盖 > 默认
    static func current(_ strategyId: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return overrides[strategyId] ?? defaults[strategyId]
    }

    static func defaultOf(_ strategyId: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return defaults[strategyId]
    }

    static func isOverridden(_ strategyId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return overrides.keys.contains(strategyId)
    }

    static func setOverride(_ strategyId: String, _ url: String) {
        lock.lock()
        overrides[strategyId] = url.isEmpty ? nil : url
        lock.unlock()
        persist()
    }

    static func clearOverride(_ strategyId: String) {
        lock.lock()
        overrides.removeValue(forKey: strategyId)
        lock.unlock()
        persist()
    }

    private static func persist() {
        lock.lock()
        let dict = overrides
        lock.unlock()
        UserDefaults.standard.set(dict, forKey: "endpoint_overrides")
    }
}

// MARK: - 歌词解析器（纯 Swift 移植 Android LyricParsers）

struct LyricLine: Identifiable, Hashable {
    /// 毫秒；纯文本歌词统一为 -1
    var timeMs: Int
    var text: String
    var id: Int { timeMs ^ text.hashValue }
}

struct Lyric {
    var lines: [LyricLine]
    var synced: Bool

    var isEmpty: Bool { !lines.contains { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty } }

    /// 播放到 ms 时应高亮的行下标；空/未同步返回 -1
    func indexAt(_ ms: Int) -> Int {
        guard !lines.isEmpty, synced else { return -1 }
        var index = 0
        while index < lines.count - 1 && lines[index + 1].timeMs <= ms { index += 1 }
        return index
    }
}

enum LyricParsers {
    /// `[mm:ss.xx]` / `[mm:ss:xxx]` 兼容；一行可挂多个时间戳
    private static let lrcTag = try! NSRegularExpression(pattern: #"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]"#)

    static func parseLrc(_ raw: String) -> Lyric {
        var out: [LyricLine] = []
        var foundTimestamp = false
        for lineRaw in raw.components(separatedBy: .newlines) {
            let ns = lineRaw as NSString
            let matches = lrcTag.matches(in: lineRaw, range: NSRange(location: 0, length: ns.length))
            if matches.isEmpty { continue }
            foundTimestamp = true
            guard let last = matches.last else { continue }
            let text = ns.substring(from: last.range.upperBound).trimmingCharacters(in: .whitespaces)
            if text.isEmpty { continue }
            for m in matches {
                let minute = Int(ns.substring(with: m.range(at: 1))) ?? 0
                let second = Int(ns.substring(with: m.range(at: 2))) ?? 0
                var frac = ""
                if m.range(at: 3).location != NSNotFound {
                    frac = ns.substring(with: m.range(at: 3))
                }
                let ms = minute * 60_000 + second * 1_000 + fracMs(frac)
                out.append(LyricLine(timeMs: ms, text: text))
            }
        }
        if !foundTimestamp || out.isEmpty { return fromPlain(raw) }
        out.sort { $0.timeMs < $1.timeMs }
        return Lyric(lines: out, synced: true)
    }

    /// 无时间轴文本 → 静态歌词
    static func fromPlain(_ raw: String) -> Lyric {
        let lines = raw
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(.init(charactersIn: "\r"))) }
            .filter { !$0.isEmpty }
            .map { LyricLine(timeMs: -1, text: $0) }
        return Lyric(lines: lines, synced: false)
    }

    /// 内容含 `-->` 视为 SRT（简化：MVP 只保留 LRC 主链路，SRT 走纯文本）
    static func parseFile(fileName: String?, content: String) -> Lyric {
        parseLrc(content)
    }

    private static func fracMs(_ frac: String) -> Int {
        guard let v = Int(frac) else { return 0 }
        switch frac.count {
        case 3: return v
        case 2: return v * 10
        case 1: return v * 100
        default: return 0
        }
    }
}

// MARK: - 歌词加载结果三态（对齐 LyricResult）

enum LyricResult {
    case success(Lyric)
    case noLyric
    case error(String, String)
}

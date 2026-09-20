import Foundation

// MARK: - 歌词加载器（平台直查 + 联网匹配兜底，对齐 Android matchOnlineLyric）

@MainActor
final class LyricLoader: ObservableObject {
    enum State {
        case idle
        case loading
        case ready(Lyric)
        /// canMatchOnline = true 时占位区可点击触发联网匹配
        case failed(String, canMatchOnline: Bool)
    }

    @Published var state: State = .idle

    private let netease = NeteaseService()
    private let kugou = KugouService()

    private var loadTask: Task<Void, Never>?
    private var currentUid: String = ""

    func load(song: Song?) {
        guard let song else {
            state = .idle
            return
        }
        // 本地歌曲没有 sidecar 歌词，直接进入可联网匹配态
        if song.platform == .local {
            state = .failed("", canMatchOnline: true)
            return
        }
        currentUid = song.uid
        state = .loading
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            guard let self else { return }
            let result = await self.fetch(song: song)
            // 过期结果丢弃（切歌竞态）
            guard self.currentUid == song.uid, !Task.isCancelled else { return }
            switch result {
            case .success(let lyric):
                self.state = .ready(lyric)
            case .noLyric:
                self.state = .failed("", canMatchOnline: true)
            case .error(let code, let message):
                self.state = .failed("\(code) \(message)", canMatchOnline: true)
            }
        }
    }

    /// 联网匹配：按歌名+歌手遍历网易云 → 酷狗（本地导入的「歌名@歌手」文件名先拆分）
    func matchOnline(song: Song?) {
        guard let song else { return }
        currentUid = song.uid
        state = .loading
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            guard let self else { return }
            let result = await self.matchKeywords(song: song)
            guard self.currentUid == song.uid, !Task.isCancelled else { return }
            switch result {
            case .success(let lyric):
                self.state = .ready(lyric)
            default:
                // 联网翻遍了云端词库也没找到 → 创意失败提示（对齐 Android 文案）
                self.state = .failed("match_failed", canMatchOnline: true)
            }
        }
    }

    private func fetch(song: Song) async -> LyricResult {
        switch song.platform {
        case .netease:
            return try? await netease.lyric(song: song) ?? .noLyric
        case .kugou:
            return try? await kugou.lyric(song: song) ?? .noLyric
        case .local:
            return .noLyric
        }
    }

    /// 关键词增强：artist 为空时按最后一个 `@` / ` - ` 拆文件名
    private func matchKeywords(song: Song) async -> LyricResult {
        var title = song.title
        var artist = song.artist
        if artist.isEmpty {
            let name = song.title
            if let idx = name.lastIndex(of: "@") {
                title = String(name[..<idx]).trimmingCharacters(in: .whitespaces)
                artist = String(name[name.index(after: idx)...]).trimmingCharacters(in: .whitespaces)
            }
        }
        // 网易云 → 酷狗
        if let r = try? await netease.lyricBySearch(title: title, artist: artist, durationMs: song.durationMs),
           case .success = r {
            return r
        }
        if let r = try? await kugou.lyricBySearch(title: title, artist: artist, durationMs: song.durationMs),
           case .success = r {
            return r
        }
        return .noLyric
    }
}

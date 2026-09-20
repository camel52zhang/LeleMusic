import Foundation

// MARK: - 酷狗音乐数据服务（榜单 / 搜索 / 取链 / 歌词，端点对齐 Android）

struct KugouService {
    // 端点（与 Android 端 ResolverEndpoints 默认值一致）
    static let rankUrl = "https://m.kugou.com/rank/info/"
    static let playInfoUrl = "https://m.kugou.com/app/i/getSongInfo.php"
    static let lyricSearchUrl = "https://lyrics.kugou.com/search"
    static let lyricDownloadUrl = "https://lyrics.kugou.com/download"
    static let songSearchUrl = "https://songsearch.kugou.com/song_search_v2"

    static let referer = "https://m.kugou.com/"

    // 榜单定义（对齐 KugouModule）
    static let charts: [(id: String, name: String)] = [
        ("8888", "TOP500"),
        ("6666", "飙升榜"),
    ]

    static let playInfoStrategyId = "kugou.playinfo"

    init() {
        ResolverEndpoints.register(Self.playInfoStrategyId, Self.playInfoUrl)
    }

    // MARK: 榜单（固定每页 30 条，翻页取满 limit）

    func fetchChart(chartId: String, limit: Int = 50) async throws -> [Song] {
        guard let rankId = Int(chartId) else {
            throw AppException(code: AppError.parse, detail: "invalid kugou rankid=\(chartId)")
        }
        let pageSize = 30
        let pagesNeeded = min((limit + pageSize - 1) / pageSize, 5)
        var songs: [Song] = []
        for page in 1...pagesNeeded {
            if songs.count >= limit { break }
            var comps = URLComponents(string: Self.rankUrl)!
            comps.queryItems = [
                URLQueryItem(name: "rankid", value: String(rankId)),
                URLQueryItem(name: "page", value: String(page)),
                URLQueryItem(name: "json", value: "true"),
            ]
            let resp: KugouRankResp = try await Http.getJSON(KugouRankResp.self, url: comps.string!)
            if let status = resp.status, status != 1 {
                throw AppException(
                    code: AppError.network,
                    detail: "kugou rank status=\(status) errcode=\(resp.errcode ?? -1) rankid=\(rankId) page=\(page)"
                )
            }
            let list = resp.songs?.list ?? []
            if list.isEmpty { break }
            let startRank = (page - 1) * pageSize + 1
            for (offset, item) in list.enumerated() {
                if let song = Self.toSong(item, rank: startRank + offset) {
                    songs.append(song)
                }
            }
            if list.count < pageSize { break }
        }
        guard !songs.isEmpty else {
            throw AppException(code: AppError.emptyData, detail: "kugou rankid=\(rankId) returned 0 songs")
        }
        return Array(songs.prefix(limit))
    }

    /// 缺 hash 的条目直接丢弃（没有 hash 无法取链）
    static func toSong(_ item: KugouRankSong, rank: Int) -> Song? {
        guard let hash = item.hash, !hash.isEmpty else { return nil }
        let title = nonBlank(item.songname) ?? parseTitleFromFileName(item.filename)
        let artist = nonBlank(item.singername) ?? parseArtistFromFileName(item.filename)
        let durationSec = item.duration ?? 0
        return Song(
            uid: "\(PlatformId.kugou.rawValue):\(hash)",
            platform: .kugou,
            platformSongId: hash,
            extras: [
                "kg.hash320": item.hash320 ?? "",
                "kg.hashsq": item.sqhash ?? "",
                "kg.duration": String(durationSec),
            ],
            title: title,
            artist: artist,
            album: "", // 酷狗榜单不返回专辑名（接口限制）
            durationMs: durationSec * 1000,
            coverUrl: Self.buildChartCoverUrl(item.albumSizableCover),
            rank: rank
        )
    }

    /// `{size}` 占位替换（不替换 404）+ http → https
    static func buildChartCoverUrl(_ albumSizableCover: String?) -> String? {
        guard let raw = albumSizableCover, !raw.isEmpty else { return nil }
        return normalizePlayUrl(raw.replacingOccurrences(of: "{size}", with: "150"))
    }

    // MARK: 搜索（song_search_v2，匿名可用）

    func search(keyword: String, limit: Int = 30) async throws -> [Song] {
        var comps = URLComponents(string: Self.songSearchUrl)!
        comps.queryItems = [
            URLQueryItem(name: "keyword", value: keyword),
            URLQueryItem(name: "page", value: "1"),
            URLQueryItem(name: "pagesize", value: String(limit)),
            URLQueryItem(name: "platform", value: "WebFilter"),
        ]
        let resp: KugouSearchResp = try await Http.getJSON(
            KugouSearchResp.self,
            url: comps.string!,
            headers: ["Referer": Self.referer]
        )
        let items = resp.data?.lists ?? []
        return items.enumerated().compactMap { offset, item -> Song? in
            guard let hash = item.primaryHash, !hash.isEmpty else { return nil }
            return Song(
                uid: "\(PlatformId.kugou.rawValue):\(hash)",
                platform: .kugou,
                platformSongId: hash,
                extras: [
                    "kg.hash320": item.hqFileHash ?? "",
                    "kg.hashsq": item.sqFileHash ?? "",
                    "kg.duration": String(item.durationSec ?? 0),
                ],
                title: item.songName ?? "",
                artist: item.singerName ?? "",
                durationMs: (item.durationSec ?? 0) * 1000,
                coverUrl: buildChartCoverUrl(item.albumSizableCover),
                rank: offset + 1
            )
        }
    }

    // MARK: 取链（getSongInfo.php?cmd=playInfo，全曲 128kbps）

    func resolvePlayUrl(song: Song) async throws -> ResolvedTrack {
        let hash = song.platformSongId
        guard !hash.isEmpty else {
            throw AppException(code: AppError.parse, detail: "kugou hash is blank, uid=\(song.uid)")
        }
        var comps = URLComponents(string: endpoint(Self.playInfoStrategyId, Self.playInfoUrl))!
        comps.queryItems = [
            URLQueryItem(name: "cmd", value: "playInfo"),
            URLQueryItem(name: "hash", value: hash),
        ]
        let resp: KugouPlayInfoResp = try await Http.getJSON(
            KugouPlayInfoResp.self,
            url: comps.string!,
            headers: ["Referer": Self.referer]
        )

        if let status = resp.status, status != 1 {
            let reason = nonBlank(resp.error) ?? "no audio"
            throw AppException(
                code: AppError.playSourceUnavailable,
                detail: "kugou status=\(status) errcode=\(resp.errcode ?? -1) reason=\(reason) hash=\(hash)"
            )
        }

        let rawUrl = nonBlank(resp.url)
            ?? resp.backupUrl?.firstString
        guard let raw = rawUrl else {
            throw AppException(
                code: AppError.playSourceUnavailable,
                detail: "kugou url and backup_url both empty, status=\(resp.status.map(String.init) ?? "nil") hash=\(hash)"
            )
        }

        let url = normalizePlayUrl(raw)
        let isFull = Self.decideIsFull(url: url, timeLengthSec: resp.timeLength, declareMs: song.durationMs)
        return ResolvedTrack(
            url: url,
            isFull: isFull,
            headers: [:],
            strategyId: Self.playInfoStrategyId,
            quality: "\(resp.bitRate ?? 0)k",
            coverUrl: Self.buildChartCoverUrl(resp.albumImg)
        )
    }

    /// 全曲判定（对齐 KugouUrlResolver.decideIsFull）
    static func decideIsFull(url: String, timeLengthSec: Int?, declareMs: Int) -> Bool {
        let declareSec = declareMs / 1000
        if let tl = timeLengthSec, tl > 0, declareSec > 0 {
            return Double(tl) >= Double(declareSec) * 0.90
        }
        return url.lowercased().contains("/yp/full/")
    }

    // MARK: 歌词（search → download 两步，content 为 base64）

    func lyric(song: Song) async throws -> LyricResult {
        let hash = song.platformSongId
        guard !hash.isEmpty else { return .error(AppError.parse, "kugou hash blank") }
        let keyword = song.title.isEmpty ? hash : song.title
        return try await fetchLyricByHash(keyword: keyword, hash: hash, durationMs: song.durationMs)
    }

    func lyricBySearch(title: String, artist: String, durationMs: Int) async throws -> LyricResult {
        let keyword = [title, artist].filter { !$0.isEmpty }.joined(separator: " ")
        guard !keyword.isEmpty else { return .noLyric }
        let songs = try await search(keyword: keyword, limit: 5)
        // 歌名精确一致优先，否则取第一条
        let best = songs.first { $0.title.trimmingCharacters(in: .whitespaces) == title.trimmingCharacters(in: .whitespaces) }
            ?? songs.first
        guard let hit = best else { return .noLyric }
        return try await fetchLyricByHash(keyword: title, hash: hit.platformSongId, durationMs: durationMs)
    }

    private func fetchLyricByHash(keyword: String, hash: String, durationMs: Int) async throws -> LyricResult {
        var comps = URLComponents(string: Self.lyricSearchUrl)!
        comps.queryItems = [
            URLQueryItem(name: "ver", value: "1"),
            URLQueryItem(name: "man", value: "yes"),
            URLQueryItem(name: "client", value: "pc"),
            URLQueryItem(name: "keyword", value: keyword),
            URLQueryItem(name: "duration", value: String(durationMs)),
            URLQueryItem(name: "hash", value: hash),
        ]
        let search: KugouLyricSearchResp = try await Http.getJSON(
            KugouLyricSearchResp.self, url: comps.string!, headers: ["Referer": Self.referer]
        )
        guard let candidate = (search.candidates ?? []).first(where: { $0.id != nil && nonBlank($0.accesskey) != nil }) else {
            return .noLyric
        }
        var dcomps = URLComponents(string: Self.lyricDownloadUrl)!
        dcomps.queryItems = [
            URLQueryItem(name: "ver", value: "1"),
            URLQueryItem(name: "client", value: "pc"),
            URLQueryItem(name: "id", value: String(candidate.id!)),
            URLQueryItem(name: "accesskey", value: candidate.accesskey!),
            URLQueryItem(name: "fmt", value: "lrc"),
            URLQueryItem(name: "charset", value: "utf8"),
        ]
        let download: KugouLyricDownloadResp = try await Http.getJSON(
            KugouLyricDownloadResp.self, url: dcomps.string!, headers: ["Referer": Self.referer]
        )
        guard let content = nonBlank(download.content) else { return .noLyric }
        let text = decodeBase64Lrc(content)
        guard !text.isEmpty else { return .noLyric }
        let lyric = LyricParsers.parseLrc(text)
        if lyric.isEmpty { return .noLyric }
        return .success(lyric)
    }

    /// 用户覆盖地址优先（仅取链接口支持覆盖，对齐 Android ResolverEndpoints 语义）
    private func endpoint(_ strategyId: String, _ defaultUrl: String) -> String {
        ResolverEndpoints.current(strategyId) ?? defaultUrl
    }
}

/// 空白 → nil
func nonBlank(_ s: String?) -> String? {
    guard let t = s?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return nil }
    return t
}

import Foundation

// MARK: - 网易云音乐数据服务（榜单 / 搜索 / eapi 取链 / 320 兜底 / GD 兜底 / 歌词）

struct NeteaseService {
    static let playlistDetailUrl = "https://music.163.com/api/playlist/detail"
    static let songUrlUrl = "https://music.163.com/api/song/enhance/player/url"
    static let lyricUrl = "https://music.163.com/api/song/lyric"
    static let searchWebUrl = "https://music.163.com/api/search/get/web"
    static let cloudSearchUrl = "https://music.163.com/api/cloudsearch/pc"
    static let eapiUrl = "https://interface3.music.163.com/eapi/song/enhance/player/url/v1"
    static let eapiDigestPath = "/api/song/enhance/player/url/v1"
    static let gdUrl = "https://music-api.gdstudio.xyz/api.php"

    static let referer = "https://music.163.com/"
    static let desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154"
    static let anonymousCookie = "os=pc; appver=; osver=; deviceId=pyncm!"

    static let eapiStrategyId = "netease.eapi"
    static let urlStrategyId = "netease.320"
    static let gdStrategyId = "netease.gd"

    /// eapi 公开 AES 密钥（网易全客户端通用，非机密）
    private static let aesKey = "e82ckenh8dichen8"

    // 榜单定义（对齐 NeteaseModule）
    static let charts: [(id: String, name: String)] = [
        ("3778678", "热歌榜"),
        ("3779629", "新歌榜"),
    ]

    init() {
        ResolverEndpoints.register(Self.eapiStrategyId, Self.eapiUrl)
        ResolverEndpoints.register(Self.urlStrategyId, Self.songUrlUrl)
        ResolverEndpoints.register(Self.gdStrategyId, Self.gdUrl)
    }

    // MARK: 榜单（playlist/detail，免加密免 Cookie）

    func fetchChart(chartId: String, limit: Int = 50) async throws -> [Song] {
        guard let playlistId = Int64(chartId) else {
            throw AppException(code: AppError.parse, detail: "invalid netease playlist id=\(chartId)")
        }
        var comps = URLComponents(string: Self.playlistDetailUrl)!
        comps.queryItems = [URLQueryItem(name: "id", value: String(playlistId))]
        let resp: NeteasePlaylistResp = try await Http.getJSON(
            NeteasePlaylistResp.self, url: comps.string!, headers: ["Referer": Self.referer]
        )
        if let code = resp.code, code != 200 {
            throw AppException(code: AppError.network, detail: "netease playlist code=\(code) id=\(playlistId)")
        }
        let tracks = resp.result?.tracks ?? []
        guard !tracks.isEmpty else {
            throw AppException(code: AppError.emptyData, detail: "netease playlist id=\(playlistId) has 0 tracks")
        }
        return tracks.prefix(limit).enumerated().compactMap { offset, track in
            Self.toSong(track, rank: offset + 1)
        }
    }

    /// 缺 id 的条目直接丢弃；fee 1/4 预判灰显（付费墙）
    static func toSong(_ track: NeteaseTrack, rank: Int) -> Song? {
        guard let id = track.id else { return nil }
        let fee = track.fee ?? 0
        let picUrl = nonBlank(track.album?.picUrl)
        return Song(
            uid: "\(PlatformId.netease.rawValue):\(id)",
            platform: .netease,
            platformSongId: String(id),
            extras: [
                "ne.fee": String(fee),
                "ne.pic": picUrl ?? "",
            ],
            title: track.name ?? "",
            artist: (track.artists ?? []).compactMap { nonBlank($0.name) }.joined(separator: "、"),
            album: track.album?.name ?? "",
            durationMs: track.duration ?? 0,
            coverUrl: picUrl.map { normalizeToHttps($0) },
            rank: rank,
            playable: (fee == 1 || fee == 4) ? .unavailable : .unknown
        )
    }

    // MARK: 搜索（cloudsearch/pc POST，结果质量高于旧 search/get）

    func search(keyword: String, limit: Int = 30) async throws -> [Song] {
        let resp: NeteaseSearchResp = try await Http.postFormJSON(
            NeteaseSearchResp.self,
            url: Self.cloudSearchUrl,
            form: ["s": keyword, "type": "1", "limit": String(limit), "offset": "0"],
            headers: ["Referer": Self.referer]
        )
        let songs = (resp.result?.songs ?? []).enumerated().compactMap { offset, item -> Song? in
            guard let id = item.id else { return nil }
            let picUrl = nonBlank(item.album?.picUrl)
            return Song(
                uid: "\(PlatformId.netease.rawValue):\(id)",
                platform: .netease,
                platformSongId: String(id),
                extras: ["ne.pic": picUrl ?? ""],
                title: item.name ?? "",
                artist: (item.artists ?? []).compactMap { nonBlank($0.name) }.joined(separator: "、"),
                album: item.album?.name ?? "",
                durationMs: item.duration ?? 0,
                coverUrl: picUrl.map { normalizeToHttps($0) },
                rank: offset + 1
            )
        }
        guard !songs.isEmpty else {
            throw AppException(code: AppError.emptyData, detail: "netease search empty: \(keyword)")
        }
        return songs
    }

    // MARK: 取链策略 1：eapi 加密通道（匿名 exhigh 320k）

    func resolveEapi(song: Song) async throws -> ResolvedTrack {
        guard let songId = Int64(song.platformSongId) else {
            throw AppException(code: AppError.parse, detail: "eapi song id invalid uid=\(song.uid)")
        }

        let payload = Self.buildEapiPayload(songId: songId, level: "exhigh")
        let digest = md5Hex("nobody\(Self.eapiDigestPath)use\(payload)md5forencrypt")
        let text = "\(Self.eapiDigestPath)-36cd479b6b5-\(payload)-36cd479b6b5-\(digest)"
        guard let hex = aesEcbPkcs7Hex(text, key: Self.aesKey) else {
            throw AppException(code: AppError.unknown, detail: "eapi aes encrypt failed")
        }

        let endpoint = ResolverEndpoints.current(Self.eapiStrategyId) ?? Self.eapiUrl
        let resp: NeteaseEapiResp = try await Http.postFormJSON(
            NeteaseEapiResp.self,
            url: endpoint,
            form: ["params": hex],
            headers: [
                "User-Agent": Self.desktopUA,
                "Referer": Self.referer,
                "Cookie": Self.anonymousCookie,
            ]
        )
        guard resp.code == 200, let raw = nonBlank(resp.data?.first?.url) else {
            throw AppException(
                code: AppError.playSourceUnavailable,
                detail: "eapi url empty id=\(songId) code=\(resp.code.map(String.init) ?? "nil")"
            )
        }
        return ResolvedTrack(
            url: normalizePlayUrl(raw),
            isFull: true,
            headers: ["Referer": Self.referer],
            strategyId: Self.eapiStrategyId,
            quality: "320k"
        )
    }

    /// 固定键序 payload JSON（digest 与加密必须用同一字符串，手工拼接）
    static func buildEapiPayload(songId: Int64, level: String) -> String {
        let requestId = Int.random(in: 10_000_000...99_999_999)
        let header = "{\"os\":\"pc\",\"appver\":\"\",\"osver\":\"\",\"deviceId\":\"pyncm!\",\"requestId\":\"\(requestId)\"}"
        let escapedHeader = header.replacingOccurrences(of: "\"", with: "\\\"")
        return "{\"ids\":[\(songId)],\"level\":\"\(level)\",\"encodeType\":\"flac\",\"header\":\"\(escapedHeader)\"}"
    }

    // MARK: 取链策略 2：旧直连接口（320 → 128 降级）

    func resolveSongUrl(song: Song) async throws -> ResolvedTrack {
        if let track = try await requestSongUrl(song: song, bitrate: 320_000) {
            return track
        }
        // 320 拿不到（典型 code:-110 版权/付费限制）→ 降 128 重试
        guard let track = try await requestSongUrl(song: song, bitrate: 128_000) else {
            throw AppException(
                code: AppError.playSourceUnavailable,
                detail: "netease 320&128 both failed id=\(song.platformSongId)"
            )
        }
        return track
    }

    private func requestSongUrl(song: Song, bitrate: Int) async throws -> ResolvedTrack? {
        guard !song.platformSongId.isEmpty else {
            throw AppException(code: AppError.parse, detail: "netease song id is blank, uid=\(song.uid)")
        }
        let endpoint = ResolverEndpoints.current(Self.urlStrategyId) ?? Self.songUrlUrl
        var comps = URLComponents(string: endpoint)!
        comps.queryItems = [
            URLQueryItem(name: "ids", value: "[\(song.platformSongId)]"),
            URLQueryItem(name: "br", value: String(bitrate)),
        ]
        let resp: NeteaseUrlResp = try await Http.getJSON(
            NeteaseUrlResp.self, url: comps.string!, headers: ["Referer": Self.referer]
        )
        guard let raw = nonBlank(resp.data?.first?.url) else { return nil }
        return ResolvedTrack(
            url: normalizePlayUrl(raw),
            isFull: true,
            headers: ["Referer": Self.referer],
            strategyId: Self.urlStrategyId,
            quality: bitrate >= 320_000 ? "320k" : "128k"
        )
    }

    // MARK: 取链策略 4：GD 音乐台公共 API（最终兜底，随时可能失效）

    func resolveGd(song: Song) async throws -> ResolvedTrack {
        guard !song.platformSongId.isEmpty else {
            throw AppException(code: AppError.parse, detail: "gd song id blank uid=\(song.uid)")
        }
        let endpoint = ResolverEndpoints.current(Self.gdStrategyId) ?? Self.gdUrl
        let url = "\(endpoint)?types=url&source=netease&id=\(song.platformSongId)&br=320"
        let body = try await Http.string(url: url, fast: true)
        struct GdResp: Decodable { var url: String? }
        guard let data = body.data(using: .utf8),
              let resp = try? Http.decoder.decode(GdResp.self, from: data),
              let raw = nonBlank(resp.url) else {
            throw AppException(
                code: AppError.playSourceUnavailable,
                detail: "gd url empty id=\(song.platformSongId) body=\(String(body.prefix(120)))"
            )
        }
        return ResolvedTrack(
            url: normalizePlayUrl(raw),
            isFull: true,
            headers: ["Referer": Self.referer],
            strategyId: Self.gdStrategyId,
            quality: "320k"
        )
    }

    // MARK: 歌词（/api/song/lyric 免加密免 Cookie，付费墙歌曲同样返回完整 LRC）

    func lyric(song: Song) async throws -> LyricResult {
        guard let id = Int64(song.platformSongId) else {
            return .error(AppError.parse, "netease song id non-numeric")
        }
        return try await fetchLyricById(id)
    }

    func lyricBySearch(title: String, artist: String, durationMs: Int) async throws -> LyricResult {
        let keyword = [title, artist].filter { !$0.isEmpty }.joined(separator: " ")
        guard !keyword.isEmpty else { return .noLyric }
        let resp: NeteaseSearchResp = try await Http.getJSON(
            NeteaseSearchResp.self,
            url: searchGetUrl(keyword: keyword, limit: 5),
            headers: ["Referer": Self.referer]
        )
        let candidates = (resp.result?.songs ?? []).filter { $0.id != nil }
        let best = candidates.first { $0.name?.trimmingCharacters(in: .whitespaces) == title.trimmingCharacters(in: .whitespaces) }
            ?? candidates.first
        guard let id = best?.id else { return .noLyric }
        return try await fetchLyricById(id)
    }

    private func searchGetUrl(keyword: String, limit: Int) -> String {
        var comps = URLComponents(string: Self.searchWebUrl)!
        comps.queryItems = [
            URLQueryItem(name: "s", value: keyword),
            URLQueryItem(name: "type", value: "1"),
            URLQueryItem(name: "offset", value: "0"),
            URLQueryItem(name: "limit", value: String(limit)),
        ]
        return comps.string!
    }

    private func fetchLyricById(_ id: Int64) async throws -> LyricResult {
        var comps = URLComponents(string: Self.lyricUrl)!
        comps.queryItems = [
            URLQueryItem(name: "id", value: String(id)),
            URLQueryItem(name: "lv", value: "-1"),
            URLQueryItem(name: "kv", value: "-1"),
            URLQueryItem(name: "tv", value: "-1"),
        ]
        let resp: NeteaseLyricResp = try await Http.getJSON(
            NeteaseLyricResp.self, url: comps.string!, headers: ["Referer": Self.referer]
        )
        if resp.nolyric == true || resp.uncollected == true { return .noLyric }
        guard let text = nonBlank(resp.lrc?.lyric) else { return .noLyric }
        let lyric = LyricParsers.parseLrc(text)
        if lyric.isEmpty { return .noLyric }
        return .success(lyric)
    }
}

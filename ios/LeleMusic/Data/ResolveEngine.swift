import Foundation

// MARK: - 取链降级责任链（对齐 Android PlayUrlResolveUseCase）

/// LX-Music REST 代理（GET {base}/url/{source}/{songmid}/320k + X-Request-Key，无需 JS 引擎）
struct LxProxyResolver {
    let platform: PlatformId
    let baseUrl: String
    let apiKey: String
    let tag: String
    let source: String

    var strategyId: String { platform == .netease ? "lx.wy.\(tag)" : "lx.kg.\(tag)" }

    /// 内置公共代理（api.txt render_api.js，X-Request-Key: share-v3）
    static let onrenderBase = "https://lxmusicapi.onrender.com"
    static let onrenderKey = "share-v3"

    init(platform: PlatformId, baseUrl: String, apiKey: String, tag: String) {
        self.platform = platform
        self.baseUrl = baseUrl
        self.apiKey = apiKey
        self.tag = tag
        self.source = platform == .netease ? "wy" : "kg"
        ResolverEndpoints.register(strategyId, baseUrl)
    }

    static func builtin(platform: PlatformId) -> LxProxyResolver {
        LxProxyResolver(platform: platform, baseUrl: onrenderBase, apiKey: onrenderKey, tag: "onrender")
    }

    func resolve(song: Song) async throws -> ResolvedTrack {
        let songmid = song.platformSongId
        guard !songmid.isEmpty else {
            throw AppException(code: AppError.parse, detail: "lx proxy songmid blank uid=\(song.uid)")
        }
        let base = (ResolverEndpoints.current(strategyId) ?? baseUrl).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let url = "\(base)/url/\(source)/\(songmid)/320k"
        let body = try await Http.string(
            url: url,
            headers: ["X-Request-Key": apiKey],
            fast: true
        )
        guard let realUrl = Self.extractUrl(body) else {
            throw AppException(
                code: AppError.playSourceUnavailable,
                detail: "lx proxy no url body=\(String(body.prefix(200))) songmid=\(songmid) source=\(source)"
            )
        }
        // 126.net 直链必须带 Referer，否则 range 请求异常
        let host = hostOf(realUrl)
        let headers: [String: String] = (host == "music.126.net" || host.hasSuffix(".music.126.net"))
            ? ["Referer": NeteaseService.referer]
            : [:]
        return ResolvedTrack(
            url: normalizePlayUrl(realUrl),
            isFull: true,
            headers: headers,
            strategyId: strategyId,
            quality: "320k"
        )
    }

    /// 兼容三种形态：标准 JSON / 引号包裹裸串 / 纯文本裸 URL
    static func extractUrl(_ body: String) -> String? {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "\""))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return nil }

        let lower = trimmed.lowercased()
        let looksBareUrl = (lower.hasPrefix("http://") || lower.hasPrefix("https://"))
            && !trimmed.contains(where: { " \n\r\t".contains($0) })
        if looksBareUrl { return trimmed }

        struct LxResp: Decodable { var code: Int?; var url: String? }
        guard let data = trimmed.data(using: .utf8),
              let resp = try? Http.decoder.decode(LxResp.self, from: data),
              resp.code == 0,
              let url = nonBlank(resp.url) else { return nil }
        return url
    }
}

// MARK: - 责任链编排

final class ResolveEngine {
    static let shared = ResolveEngine()

    private let kugou = KugouService()
    private let netease = NeteaseService()

    /// uid → 上一次解析的失败明细（诊断用）
    private(set) var lastFailures: [String: [ResolveFailure]] = [:]

    /// 按优先级解析直链：中间策略失败全部吞掉，自动降级；全部失败才抛
    func resolve(song: Song) async throws -> ResolvedTrack {
        // 本地歌曲直接给文件路径
        if song.platform == .local {
            return ResolvedTrack(
                url: song.platformSongId,
                isFull: true,
                headers: [:],
                strategyId: "local.file",
                quality: nil
            )
        }

        let chain: [(String, Int, (Song) async throws -> ResolvedTrack)]
        switch song.platform {
        case .netease:
            chain = [
                (NeteaseService.eapiStrategyId, 9, { try await self.netease.resolveEapi(song: $0) }),
                (NeteaseService.urlStrategyId, 10, { try await self.netease.resolveSongUrl(song: $0) }),
                (LxProxyResolver.builtin(platform: .netease).strategyId, 90, { try await LxProxyResolver.builtin(platform: .netease).resolve(song: $0) }),
                (NeteaseService.gdStrategyId, 91, { try await self.netease.resolveGd(song: $0) }),
            ]
        case .kugou:
            let lx = LxProxyResolver.builtin(platform: .kugou)
            chain = [
                (KugouService.playInfoStrategyId, 10, { try await self.kugou.resolvePlayUrl(song: $0) }),
                (lx.strategyId, 90, { try await lx.resolve(song: $0) }),
            ]
        case .local:
            chain = []
        }

        guard !chain.isEmpty else {
            throw AppException(
                code: AppError.playSourceUnavailable,
                detail: "no enabled resolver for platform=\(song.platform.rawValue), uid=\(song.uid)"
            )
        }

        var failures: [ResolveFailure] = []
        for (strategyId, _, attempt) in chain {
            do {
                let track = try await attempt(song)
                lastFailures[song.uid] = failures
                return track
            } catch {
                let code = (error as? AppException)?.code ?? AppError.unknown
                let message = (error as? AppException)?.detail ?? error.localizedDescription
                failures.append(ResolveFailure(strategyId: strategyId, errorCode: code, message: message))
            }
        }

        lastFailures[song.uid] = failures
        let detail = failures
            .map { "\($0.strategyId):\($0.errorCode) (\($0.message))" }
            .joined(separator: " | ")
        throw AppException(
            code: AppError.playSourceUnavailable,
            detail: "all strategies failed for uid=\(song.uid) [\(detail)]"
        )
    }
}

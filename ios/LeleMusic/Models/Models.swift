import Foundation

// MARK: - 平台身份（对齐 Android Platform.kt，id 不可更改，否则历史 uid 失效）

enum PlatformId: String, CaseIterable {
    case netease
    case kugou
    case local

    var displayName: String {
        switch self {
        case .netease: return "网易云音乐"
        case .kugou: return "酷狗音乐"
        case .local: return "本地音乐"
        }
    }

    /// 品牌主题色（hex 对齐 Android seedColor）
    var seedHex: UInt {
        switch self {
        case .netease: return 0xC20C0C
        case .kugou: return 0x0092FF
        case .local: return 0x5E7A88
        }
    }

    /// 平台官方单曲落地页
    func songPageUrl(_ platformSongId: String) -> String {
        switch self {
        case .netease: return "https://music.163.com/#/song?id=\(platformSongId)"
        case .kugou: return "https://www.kugou.com/song/#hash=\(platformSongId)"
        case .local: return ""
        }
    }
}

// MARK: - 可播性三态

enum PlayableStatus: String, Codable {
    case unknown
    case available
    case trialOnly
    case unavailable
}

// MARK: - 统一歌曲领域模型（对齐 Android Song.kt）

struct Song: Identifiable, Hashable {
    /// `"${platformId}:${platformSongId}"`，唯一主键
    var uid: String
    var platform: PlatformId
    var platformSongId: String
    var extras: [String: String]
    var title: String
    var artist: String
    var album: String
    /// 毫秒；未知为 0
    var durationMs: Int
    var coverUrl: String?
    var rank: Int
    var playable: PlayableStatus

    var id: String { uid }

    func extra(_ key: String) -> String { extras[key] ?? "" }

    init(
        uid: String,
        platform: PlatformId,
        platformSongId: String,
        extras: [String: String] = [:],
        title: String,
        artist: String,
        album: String = "",
        durationMs: Int = 0,
        coverUrl: String? = nil,
        rank: Int = 0,
        playable: PlayableStatus = .unknown
    ) {
        self.uid = uid
        self.platform = platform
        self.platformSongId = platformSongId
        self.extras = extras
        self.title = title
        self.artist = artist
        self.album = album
        self.durationMs = durationMs
        self.coverUrl = coverUrl
        self.rank = rank
        self.playable = playable
    }
}

// MARK: - 歌单持久化 DTO（与 Android playlists.json 同 schema，可互导）

struct StorableSong: Codable, Hashable {
    var platformId: String
    var platformSongId: String
    var title: String
    var artist: String
    var album: String
    var durationMs: Int
    var coverUrl: String?
    var extras: [String: String] = [:]
}

struct Playlist: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var createdAtMs: Double
    var songs: [StorableSong] = []
    var origin: String? = nil

    static let originManual = "manual"
    static let originImport = "import"

    func songUid(_ song: StorableSong) -> String { "\(song.platformId):\(song.platformSongId)" }

    func containsUid(_ uid: String) -> Bool {
        songs.contains { "\($0.platformId):\($0.platformSongId)" == uid }
    }
}

struct LibraryFile: Codable {
    var version: Int = 1
    var playlists: [Playlist] = []
}

// MARK: - 取链结果（对齐 Android ResolvedTrack）

struct ResolvedTrack {
    var url: String
    var isFull: Bool
    var headers: [String: String]
    var strategyId: String
    var quality: String?
    var coverUrl: String?
}

struct ResolveFailure {
    var strategyId: String
    var errorCode: String
    var message: String
}

/// data 层统一异常（对齐 Android AppException）
struct AppException: Error {
    var code: String
    var detail: String

    var localizedDescription: String { "\(code): \(detail)" }
}

enum AppError {
    static let network = "E_NET"
    static let timeout = "E_TIMEOUT"
    static let parse = "E_PARSE"
    static let playSourceUnavailable = "E_NO_SOURCE"
    static let emptyData = "E_EMPTY"
    static let unknown = "E_UNKNOWN"
}

/// 本地音乐平台常量（对齐 Android LocalMedia）
enum LocalMedia {
    static let platformId = PlatformId.local.rawValue
}

import Foundation

// MARK: - 全平台响应 DTO（字段全部可选，缺字段安全跳过）

// ---------------------------------------------------------------------------
// 网易云
// ---------------------------------------------------------------------------

struct NeteasePlaylistResp: Decodable {
    var code: Int?
    var result: NeteasePlaylistResult?
}

struct NeteasePlaylistResult: Decodable {
    var tracks: [NeteaseTrack]?
}

struct NeteaseTrack: Decodable {
    var id: Int64?
    var name: String?
    /// 0 免费 / 8 VIP / 1 付费 / 4 付费专辑
    var fee: Int?
    /// 毫秒（与酷狗的秒不同，勿再乘 1000）
    var duration: Int?
    var artists: [NeteaseArtist]?
    var album: NeteaseAlbum?
}

struct NeteaseArtist: Decodable {
    var name: String?
}

struct NeteaseAlbum: Decodable {
    var name: String?
    var picUrl: String?
}

struct NeteaseUrlResp: Decodable {
    var data: [NeteaseUrlData]?
}

struct NeteaseUrlData: Decodable {
    var url: String?
    var code: Int?
    var fee: Int?
    var level: String?
}

struct NeteaseLyricResp: Decodable {
    var nolyric: Bool?
    var uncollected: Bool?
    var lrc: NeteaseLrcBox?
}

struct NeteaseLrcBox: Decodable {
    var lyric: String?
}

/// 搜索（search/get 与 cloudsearch/pc 共用，缺字段安全忽略）
struct NeteaseSearchResp: Decodable {
    var code: Int?
    var result: NeteaseSearchResult?
}

struct NeteaseSearchResult: Decodable {
    var songs: [NeteaseSearchSong]?
}

struct NeteaseSearchSong: Decodable {
    var id: Int64?
    var name: String?
    var duration: Int?
    var artists: [NeteaseArtist]?
    var album: NeteaseAlbum?
}

/// eapi 直链响应
struct NeteaseEapiResp: Decodable {
    var code: Int?
    var data: [NeteaseUrlData]?
}

// ---------------------------------------------------------------------------
// 酷狗
// ---------------------------------------------------------------------------

struct KugouRankResp: Decodable {
    var info: KugouRankInfo?
    var songs: KugouSongPage?
    var status: Int?
    var errcode: Int?
    var error: String?
}

struct KugouRankInfo: Decodable {
    var rankIdPublishDate: String?

    enum CodingKeys: String, CodingKey {
        case rankIdPublishDate = "rank_id_publish_date"
    }
}

struct KugouSongPage: Decodable {
    var total: Int?
    var page: Int?
    var pagesize: Int?
    var list: [KugouRankSong]?
}

struct KugouRankSong: Decodable {
    var hash: String?
    var hash320: String?
    var sqhash: String?
    var filename: String?
    var songname: String?
    var singername: String?
    var albumSizableCover: String?
    /// 秒
    var duration: Int?

    enum CodingKeys: String, CodingKey {
        case hash
        case hash320 = "320hash"
        case sqhash
        case filename
        case songname
        case singername
        case albumSizableCover = "album_sizable_cover"
        case duration
    }
}

struct KugouPlayInfoResp: Decodable {
    /// 1 = 成功
    var status: Int?
    var errcode: Int?
    var error: String?
    var url: String?
    /// 数组/对象/字符串多形态，见 JSONValue
    var backupUrl: JSONValue?
    /// 真实时长（秒）
    var timeLength: Int?
    var bitRate: Int?
    var albumImg: String?
    var privilege: Int?
    var payType: Int?

    enum CodingKeys: String, CodingKey {
        case status
        case errcode
        case error
        case url
        case backupUrl = "backup_url"
        case timeLength
        case bitRate
        case albumImg = "album_img"
        case privilege
        case payType = "pay_type"
    }
}

struct KugouLyricSearchResp: Decodable {
    var status: Int?
    var candidates: [KugouLyricCandidate]?
    var err_code: Int?
}

struct KugouLyricCandidate: Decodable {
    var id: Int64?
    var accesskey: String?
}

struct KugouLyricDownloadResp: Decodable {
    var status: Int?
    var err_code: Int?
    var content: String?
    var fmt: String?
    var charset: String?
}

struct KugouSearchResp: Decodable {
    var status: Int?
    var data: KugouSearchData?
}

struct KugouSearchData: Decodable {
    var lists: [KugouSearchItem]?
}

struct KugouSearchItem: Decodable {
    var fileHash: String?
    var hqFileHash: String?
    var sqFileHash: String?
    var songName: String?
    var singerName: String?
    /// 秒
    var durationSec: Int?
    var albumSizableCover: String?

    enum CodingKeys: String, CodingKey {
        case fileHash = "FileHash"
        case hqFileHash = "HQFileHash"
        case sqFileHash = "SQFileHash"
        case songName = "SongName"
        case singerName = "SingerName"
        case durationSec = "Duration"
        case albumSizableCover = "AlbumSizableCover"
    }

    /// 候选主 hash：优先 FileHash，退而求其次 HQ/SQ
    var primaryHash: String? { fileHash ?? hqFileHash ?? sqFileHash }
}

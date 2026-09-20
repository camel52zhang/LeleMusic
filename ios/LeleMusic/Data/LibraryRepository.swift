import Foundation

// MARK: - 自建歌单仓库（Documents/playlists.json，与 Android 同 schema）

@MainActor
final class LibraryRepository: ObservableObject {
    static let shared = LibraryRepository()

    @Published private(set) var playlists: [Playlist] = []

    private var fileURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("playlists.json")
    }

    /// 本地音乐拷贝目录
    var musicDir: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Music", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private init() {
        load()
    }

    // MARK: 持久化

    func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let file = try? JSONDecoder().decode(LibraryFile.self, from: data) else { return }
        playlists = file.playlists
    }

    private func save() {
        let file = LibraryFile(playlists: playlists)
        guard let data = try? JSONEncoder().encode(file) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    // MARK: 歌单 CRUD

    @discardableResult
    func createPlaylist(name: String) -> Playlist {
        var playlist = Playlist(
            id: UUID().uuidString,
            name: name,
            createdAtMs: Double(Date().timeIntervalSince1970 * 1000),
            origin: Playlist.originManual
        )
        playlist.origin = Playlist.originManual
        playlists.insert(playlist, at: 0)
        save()
        return playlist
    }

    func deletePlaylist(id: String) {
        playlists.removeAll { $0.id == id }
        save()
    }

    func renamePlaylist(id: String, name: String) {
        guard let idx = playlists.firstIndex(where: { $0.id == id }) else { return }
        playlists[idx].name = name
        save()
    }

    // MARK: 歌曲

    /// 按 uid 去重追加；返回实际新增数量
    @discardableResult
    func addSong(playlistId: String, song: Song) -> Int {
        guard let idx = playlists.firstIndex(where: { $0.id == playlistId }) else { return 0 }
        let storable = song.toStorable()
        if playlists[idx].containsUid("\(storable.platformId):\(storable.platformSongId)") {
            return 0
        }
        playlists[idx].songs.append(storable)
        save()
        return 1
    }

    @discardableResult
    func addSongs(playlistId: String, songs: [Song]) -> Int {
        songs.reduce(0) { $0 + addSong(playlistId: playlistId, song: $1) }
    }

    func removeSong(playlistId: String, uid: String) {
        guard let idx = playlists.firstIndex(where: { $0.id == playlistId }) else { return }
        playlists[idx].songs.removeAll { "\($0.platformId):\($0.platformSongId)" == uid }
        save()
    }

    func playlist(id: String?) -> Playlist? {
        guard let id else { return nil }
        return playlists.first { $0.id == id }
    }

    // MARK: 本地导入（SAF → 拷贝进 Documents/Music，保证持久可访问）

    /// 拷贝安全作用域文件到 Music 目录，返回可播 Song（platformId=local，platformSongId=文件名）
    func importAudioFile(from url: URL) throws -> Song {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }

        let baseName = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension.lowercased()
        // 重名追加序号，避免覆盖
        var fileName = "\(baseName).\(ext)"
        var counter = 1
        let dest = musicDir.appendingPathComponent(fileName)
        while FileManager.default.fileExists(atPath: dest.path) {
            fileName = "\(baseName)-\(counter).\(ext)"
            counter += 1
        }
        let finalDest = musicDir.appendingPathComponent(fileName)
        try FileManager.default.copyItem(at: url, to: finalDest)

        let title = parseTitleFromFileName(baseName).isEmpty ? baseName : parseTitleFromFileName(baseName)
        let artist = parseArtistFromFileName(baseName)
        return Song(
            uid: "\(LocalMedia.platformId):\(fileName)",
            platform: .local,
            platformSongId: fileName,
            extras: [:],
            title: title,
            artist: artist,
            album: "",
            durationMs: 0,
            coverUrl: nil,
            rank: 0
        )
    }
}

// MARK: - Song ↔ StorableSong

extension Song {
    func toStorable() -> StorableSong {
        StorableSong(
            platformId: platform.rawValue,
            platformSongId: platformSongId,
            title: title,
            artist: artist,
            album: album,
            durationMs: durationMs,
            coverUrl: coverUrl,
            extras: extras
        )
    }

    static func from(storable: StorableSong) -> Song? {
        guard let platform = PlatformId(rawValue: storable.platformId) else { return nil }
        return Song(
            uid: "\(platform.rawValue):\(storable.platformSongId)",
            platform: platform,
            platformSongId: storable.platformSongId,
            extras: storable.extras,
            title: storable.title,
            artist: storable.artist,
            album: storable.album,
            durationMs: storable.durationMs,
            coverUrl: storable.coverUrl,
            rank: 0
        )
    }
}

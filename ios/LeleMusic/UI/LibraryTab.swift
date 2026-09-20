import SwiftUI
import UniformTypeIdentifiers

// MARK: - 我的歌单 Tab

struct LibraryTab: View {
    @EnvironmentObject var library: LibraryRepository

    @State private var showCreate = false
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                ForEach(library.playlists) { playlist in
                    NavigationLink {
                        PlaylistDetailView(playlistId: playlist.id)
                    } label: {
                        HStack {
                            Image(systemName: playlist.origin == Playlist.originImport ? "square.and.arrow.down" : "music.note.list")
                                .foregroundStyle(.tint)
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(playlist.name).font(.body.weight(.medium))
                                    if playlist.origin == Playlist.originImport {
                                        Text("导入")
                                            .font(.caption2)
                                            .padding(.horizontal, 5)
                                            .padding(.vertical, 1)
                                            .background(Color.accentColor.opacity(0.15), in: Capsule())
                                    }
                                }
                                Text("\(playlist.songs.count) 首")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .onDelete { indexSet in
                    for i in indexSet { library.deletePlaylist(id: library.playlists[i].id) }
                }
            }
            .overlay {
                if library.playlists.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "music.note.list").font(.largeTitle).foregroundStyle(.tertiary)
                        Text("还没有歌单\n点右上角「＋」新建一个吧")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                }
            }
            .navigationTitle("我的歌单")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        newName = ""
                        showCreate = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("新建歌单")
                }
            }
            .alert("新建歌单", isPresented: $showCreate) {
                TextField("歌单名称", text: $newName)
                Button("创建") {
                    let name = newName.trimmingCharacters(in: .whitespaces)
                    if !name.isEmpty { library.createPlaylist(name: name) }
                }
                Button("取消", role: .cancel) {}
            }
        }
    }
}

// MARK: - 歌单详情（播放全部 / 加歌 / 侧滑删除）

struct PlaylistDetailView: View {
    @EnvironmentObject var library: LibraryRepository
    @EnvironmentObject var player: PlayerController

    let playlistId: String

    @State private var showImporter = false
    @State private var toast: String?
    @State private var showRename = false
    @State private var renameText = ""

    private var playlist: Playlist? { library.playlist(id: playlistId) }

    var body: some View {
        List {
            if let playlist {
                ForEach(Array(playlist.songs.enumerated()), id: \.offset) { idx, storable in
                    if let song = Song.from(storable: storable) {
                        Button {
                            let songs = playlist.songs.compactMap { Song.from(storable: $0) }
                            if let playIndex = songs.firstIndex(where: { $0.uid == song.uid }) {
                                player.play(queue: songs, at: playIndex)
                            }
                        } label: {
                            SongRow(song: song, rank: idx + 1)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .onDelete { indexSet in
                    for i in indexSet {
                        let storable = playlist.songs[i]
                        library.removeSong(playlistId: playlistId, uid: "\(storable.platformId):\(storable.platformSongId)")
                    }
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(playlist?.name ?? "歌单")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    if let playlist, !playlist.songs.isEmpty {
                        let songs = playlist.songs.compactMap { Song.from(storable: $0) }
                        player.play(queue: songs, at: 0)
                    } else {
                        toast = "歌单还是空的，先加点歌吧"
                    }
                } label: {
                    Image(systemName: "play.circle")
                }
                .accessibilityLabel("播放全部")
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    showImporter = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("导入歌曲")
                Menu {
                    Button("重命名") {
                        renameText = playlist?.name ?? ""
                        showRename = true
                    }
                    Button("删除歌单", role: .destructive) {
                        library.deletePlaylist(id: playlistId)
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: [.audio],
            allowsMultipleSelection: true
        ) { result in
            guard case .success(let urls) = result else { return }
            var imported: [Song] = []
            var failed = 0
            for url in urls {
                do {
                    imported.append(try library.importAudioFile(from: url))
                } catch {
                    failed += 1
                }
            }
            let added = library.addSongs(playlistId: playlistId, songs: imported)
            if added > 0, failed == 0 {
                toast = "已添加 \(added) 首歌曲"
            } else if added == 0, failed == 0 {
                toast = "所选歌曲都已在歌单中"
            } else if failed > 0 {
                toast = "已添加 \(added) 首，\(failed) 首导入失败"
            }
        }
        .alert("重命名歌单", isPresented: $showRename) {
            TextField("歌单名称", text: $renameText)
            Button("保存") {
                let name = renameText.trimmingCharacters(in: .whitespaces)
                if !name.isEmpty { library.renamePlaylist(id: playlistId, name: name) }
            }
            Button("取消", role: .cancel) {}
        }
        .toast(message: $toast)
    }
}

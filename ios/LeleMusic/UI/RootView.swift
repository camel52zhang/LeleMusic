import SwiftUI

// MARK: - 根视图：三 Tab + 迷你播放条 + 全屏播放页

struct RootView: View {
    @EnvironmentObject var player: PlayerController
    @State private var showPlayer = false

    var body: some View {
        ZStack(alignment: .bottom) {
            TabView {
                ChartTab()
                    .tabItem { Label("榜单", systemImage: "chart.bar") }
                SearchTab()
                    .tabItem { Label("搜索", systemImage: "magnifyingglass") }
                LibraryTab()
                    .tabItem { Label("我的歌单", systemImage: "music.note.list") }
            }
            // 迷你播放条压在 TabBar 上方
            if player.currentSong != nil {
                MiniPlayerBar(showPlayer: $showPlayer)
                    .padding(.horizontal, 8)
                    .padding(.bottom, 49) // TabBar 高度
            }
        }
        .sheet(isPresented: $showPlayer) {
            PlayerSheet()
        }
    }
}

// MARK: - 迷你播放条

struct MiniPlayerBar: View {
    @EnvironmentObject var player: PlayerController
    @Binding var showPlayer: Bool

    var body: some View {
        HStack(spacing: 12) {
            CoverImage(url: player.currentSong?.coverUrl ?? player.resolvedCover, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(player.currentSong?.title ?? "")
                    .font(.footnote.weight(.medium))
                    .lineLimit(1)
                Text(player.currentSong?.artist ?? "")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Button {
                player.togglePlay()
            } label: {
                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                    .font(.title3)
                    .frame(width: 44, height: 44)
            }
            Button {
                player.next()
            } label: {
                Image(systemName: "forward.fill")
                    .font(.title3)
                    .frame(width: 44, height: 44)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
        .onTapGesture { showPlayer = true }
        .accessibilityLabel("迷你播放条")
    }
}

// MARK: - 封面

struct CoverImage: View {
    let url: String?
    var size: CGFloat

    var body: some View {
        Group {
            if let url, let u = URL(string: url) {
                AsyncImage(url: u) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        placeholder
                    }
                }
            } else {
                placeholder
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private var placeholder: some View {
        ZStack {
            Rectangle().fill(Color(.systemGray5))
            Image(systemName: "music.note")
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - 歌曲行（榜单/搜索/歌单共用）

struct SongRow: View {
    let song: Song
    var rank: Int? = nil

    var body: some View {
        HStack(spacing: 12) {
            if let rank {
                Text("\(rank)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(rank <= 3 ? Color.red : Color.secondary)
                    .frame(width: 24)
            }
            CoverImage(url: song.coverUrl, size: 44)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(song.title.isEmpty ? "未知曲名" : song.title)
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                    if song.playable == .unavailable {
                        Image(systemName: "lock.fill")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                }
                Text([song.artist, song.album].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Text(formatDuration(song.durationMs))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 4)
    }
}

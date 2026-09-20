import SwiftUI

// MARK: - 全屏播放页（封面 / 控制 / 歌词点击跳播 / 联网匹配）

struct PlayerSheet: View {
    @EnvironmentObject var player: PlayerController
    @Environment(\.dismiss) private var dismiss

    @StateObject private var lyricLoader = LyricLoader()
    /// 拖动进度条时暂停自动刷新
    @State private var isScrubbing = false
    @State private var scrubValue: Double = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // 封面区
                CoverImage(url: player.currentSong?.coverUrl ?? player.resolvedCover, size: min(UIScreen.main.bounds.width - 48, 260))
                    .shadow(color: .black.opacity(0.15), radius: 12, y: 6)
                    .padding(.top, 8)

                VStack(spacing: 4) {
                    Text(player.currentSong?.title ?? "")
                        .font(.title3.weight(.semibold))
                        .lineLimit(1)
                    Text(player.currentSong?.artist.isEmpty == false ? player.currentSong!.artist : "未知歌手")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .padding(.top, 16)

                // 歌词区（点击跳播 + 联网匹配）
                LyricsPanel(lyricLoader: lyricLoader)
                    .frame(maxHeight: 300)
                    .padding(.top, 12)

                Spacer(minLength: 8)

                // 进度
                VStack(spacing: 4) {
                    Slider(
                        value: Binding(
                            get: { isScrubbing ? scrubValue : min(player.currentTime, max(player.duration, 0.1)) },
                            set: { scrubValue = $0 }
                        ),
                        in: 0...max(player.duration, 0.1)
                    ) { editing in
                        isScrubbing = editing
                        if !editing { player.seek(to: scrubValue) }
                    }
                    HStack {
                        Text(formatClock(player.currentTime)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        Spacer()
                        Text(formatClock(player.duration)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 24)

                // 控制行
                HStack(spacing: 32) {
                    Button { player.previous() } label: {
                        Image(systemName: "backward.fill").font(.title2)
                    }
                    .frame(width: 56, height: 56)
                    Button { player.togglePlay() } label: {
                        Group {
                            if player.isBuffering {
                                ProgressView()
                            } else {
                                Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                                    .font(.system(size: 64))
                            }
                        }
                    }
                    .frame(width: 72, height: 72)
                    Button { player.next() } label: {
                        Image(systemName: "forward.fill").font(.title2)
                    }
                    .frame(width: 56, height: 56)
                }
                .padding(.vertical, 10)

                // 模式行
                HStack {
                    Spacer()
                    Button { player.cycleMode() } label: {
                        Label(player.mode.label, systemImage: player.mode.iconName)
                            .font(.footnote)
                            .labelStyle(.titleAndIcon)
                    }
                    Spacer()
                }
                .padding(.bottom, 12)
            }
            .padding(.horizontal, 24)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.down")
                    }
                    .accessibilityLabel("收起播放页")
                }
            }
            .onChange(of: player.currentSong?.uid) { _ in
                lyricLoader.load(song: player.currentSong)
            }
            .onAppear {
                lyricLoader.load(song: player.currentSong)
            }
            .alert(
                "播放失败",
                isPresented: Binding(
                    get: { player.lastError != nil },
                    set: { if !$0 { player.lastError = nil } }
                )
            ) {
                Button("知道了", role: .cancel) {}
            } message: {
                Text(player.lastError ?? "")
            }
        }
    }
}

// MARK: - 歌词面板（滚动高亮 / 点击跳播 / 占位区联网匹配）

struct LyricsPanel: View {
    @ObservedObject var lyricLoader: LyricLoader
    @EnvironmentObject var player: PlayerController

    var body: some View {
        switch lyricLoader.state {
        case .idle, .loading:
            VStack(spacing: 8) {
                ProgressView()
                Text("歌词加载中…")
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

        case .ready(let lyric):
            LyricLines(lyric: lyric)

        case .failed(let detail, let canMatchOnline):
            VStack(spacing: 10) {
                if detail == "match_failed" {
                    Text("联网翻遍了云端词库，也没能找到这首的歌词")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Text("校正歌名后再试一次")
                        .font(.footnote)
                        .foregroundStyle(.tertiary)
                } else {
                    Text("暂无歌词")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                if canMatchOnline {
                    Button {
                        lyricLoader.matchOnline(song: player.currentSong)
                    } label: {
                        Label("点这里，联网帮你找歌词", systemImage: "globe")
                            .font(.footnote.weight(.medium))
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

struct LyricLines: View {
    @EnvironmentObject var player: PlayerController
    let lyric: Lyric

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    // 顶部占位防首行贴边
                    Color.clear.frame(height: 8).id("top")
                    ForEach(Array(lyric.lines.enumerated()), id: \.offset) { idx, line in
                        let isCurrent = lyric.indexAt(Int(player.currentTime * 1000)) == idx
                        Text(line.text)
                            .font(isCurrent ? .callout.weight(.semibold) : .callout)
                            .foregroundStyle(isCurrent ? Color.primary : Color.secondary.opacity(0.7))
                            .lineLimit(3)
                            // 点击跳播：synced 行且时间合法
                            .onTapGesture {
                                if lyric.synced, line.timeMs >= 0 {
                                    player.seek(to: Double(line.timeMs) / 1000)
                                    if !player.isPlaying { player.togglePlay() }
                                }
                            }
                            .id(idx)
                    }
                    Color.clear.frame(height: 8).id("bottom")
                }
                .padding(.horizontal, 8)
            }
            .onChange(of: lyric.indexAt(Int(player.currentTime * 1000))) { current in
                guard current >= 0 else { return }
                withAnimation(.easeInOut(duration: 0.3)) {
                    proxy.scrollTo(current, anchor: .center)
                }
            }
        }
    }
}

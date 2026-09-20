import SwiftUI

// MARK: - 榜单 Tab（平台切换 + 榜单菜单）

struct ChartTab: View {
    @EnvironmentObject var player: PlayerController
    @EnvironmentObject var library: LibraryRepository

    @State private var platformIndex = 0
    @State private var chartIndex = 0
    @State private var songs: [Song] = []
    @State private var loading = false
    @State private var errorText: String?

    private var platforms: [PlatformId] { [.netease, .kugou] }
    private var currentCharts: [(id: String, name: String)] {
        platformIndex == 0 ? NeteaseService.charts : KugouService.charts
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("平台", selection: $platformIndex) {
                    ForEach(0..<platforms.count, id: \.self) { Text(platforms[$0].displayName).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .onChange(of: platformIndex) { _ in
                    chartIndex = 0
                    Task { await fetch() }
                }

                if loading {
                    Spacer()
                    ProgressView("加载中…")
                    Spacer()
                } else if let errorText {
                    Spacer()
                    VStack(spacing: 12) {
                        Image(systemName: "wifi.exclamationmark").font(.largeTitle).foregroundStyle(.secondary)
                        Text(errorText).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        Button("重试") { Task { await fetch() } }.buttonStyle(.bordered)
                    }
                    .padding()
                    Spacer()
                } else {
                    List {
                        ForEach(Array(songs.enumerated()), id: \.element.uid) { idx, song in
                            Button {
                                player.play(queue: songs, at: idx)
                            } label: {
                                SongRow(song: song, rank: song.rank > 0 ? song.rank : idx + 1)
                            }
                            .buttonStyle(.plain)
                            .addSongContextMenu(song: song)
                        }
                    }
                    .listStyle(.plain)
                    .refreshable { await fetch() }
                }
            }
            .navigationTitle(currentCharts.indices.contains(chartIndex) ? currentCharts[chartIndex].name : "榜单")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        ForEach(0..<currentCharts.count, id: \.self) { i in
                            Button(currentCharts[i].name) {
                                chartIndex = i
                                Task { await fetch() }
                            }
                        }
                    } label: {
                        Image(systemName: "line.3.horizontal.decrease.circle")
                    }
                    .accessibilityLabel("切换榜单")
                }
            }
            .task { if songs.isEmpty { await fetch() } }
        }
    }

    private func fetch() async {
        loading = true
        errorText = nil
        defer { loading = false }
        let chart = currentCharts.indices.contains(chartIndex) ? currentCharts[chartIndex] : nil
        guard let chart else { return }
        do {
            songs = platformIndex == 0
                ? try await NeteaseService().fetchChart(chartId: chart.id)
                : try await KugouService().fetchChart(chartId: chart.id)
        } catch {
            songs = []
            let ex = error as? AppException
            errorText = "\(ex?.code ?? "E_UNKNOWN"): \(ex?.detail ?? error.localizedDescription)"
        }
    }
}

// MARK: - 「添加到歌单」上下文菜单（榜单/搜索共用）

struct AddSongContextMenu: ViewModifier {
    @EnvironmentObject var library: LibraryRepository
    @State private var toast: String?

    let song: Song

    func body(content: Content) -> some View {
        content
            .contextMenu {
                Menu {
                    if library.playlists.isEmpty {
                        Text("还没有歌单，先去「我的歌单」新建")
                    }
                    ForEach(library.playlists) { playlist in
                        Button(playlist.name) {
                            let added = library.addSong(playlistId: playlist.id, song: song)
                            toast = added > 0 ? "已加入「\(playlist.name)」" : "这首歌已在歌单里"
                        }
                    }
                } label: {
                    Label("添加到歌单", systemImage: "plus")
                }
            }
            .toast(message: $toast)
    }
}

extension View {
    func addSongContextMenu(song: Song) -> some View {
        modifier(AddSongContextMenu(song: song))
    }
}

// MARK: - 轻量 Toast

struct ToastModifier: ViewModifier {
    @Binding var message: String?

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let message {
                Text(message)
                    .font(.footnote)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.thinMaterial, in: Capsule())
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .padding(.bottom, 80)
                    .task {
                        try? await Task.sleep(nanoseconds: 1_800_000_000)
                        withAnimation { self.message = nil }
                    }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: message)
    }
}

extension View {
    func toast(message: Binding<String?>) -> some View {
        modifier(ToastModifier(message: message))
    }
}

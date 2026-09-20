import SwiftUI

// MARK: - 搜索 Tab

struct SearchTab: View {
    @EnvironmentObject var player: PlayerController

    @State private var keyword = ""
    @State private var platformIndex = 0
    @State private var songs: [Song] = []
    @State private var searching = false
    @State private var errorText: String?
    @State private var hasSearched = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("歌名 / 歌手", text: $keyword)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onSubmit { Task { await search() } }
                    if !keyword.isEmpty {
                        Button {
                            keyword = ""
                            songs = []
                            hasSearched = false
                        } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary)
                        }
                    }
                }
                .padding(10)
                .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 10))
                .padding(.horizontal, 16)

                Picker("平台", selection: $platformIndex) {
                    Text("网易云").tag(0)
                    Text("酷狗").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)

                if searching {
                    Spacer()
                    ProgressView()
                    Spacer()
                } else if let errorText {
                    Spacer()
                    Text(errorText).font(.footnote).foregroundStyle(.secondary)
                    Spacer()
                } else if !hasSearched {
                    Spacer()
                    Text("输入关键词搜索全网音乐")
                        .font(.footnote)
                        .foregroundStyle(.tertiary)
                    Spacer()
                } else {
                    List {
                        ForEach(Array(songs.enumerated()), id: \.element.uid) { idx, song in
                            Button {
                                player.play(queue: songs, at: idx)
                            } label: {
                                SongRow(song: song)
                            }
                            .buttonStyle(.plain)
                            .addSongContextMenu(song: song)
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("搜索")
        }
    }

    private func search() async {
        let kw = keyword.trimmingCharacters(in: .whitespaces)
        guard !kw.isEmpty else { return }
        searching = true
        errorText = nil
        hasSearched = true
        defer { searching = false }
        do {
            songs = platformIndex == 0
                ? try await NeteaseService().search(keyword: kw)
                : try await KugouService().search(keyword: kw)
            if songs.isEmpty { errorText = "没有找到相关歌曲" }
        } catch {
            songs = []
            let ex = error as? AppException
            errorText = ex?.code == AppError.emptyData
                ? "没有找到相关歌曲"
                : "搜索失败：\(ex?.detail ?? error.localizedDescription)"
        }
    }
}

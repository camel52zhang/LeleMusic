import Foundation
import AVFoundation
import MediaPlayer
import UIKit

// MARK: - 播放器（AVPlayer + 队列 + 实时取链，对齐 Android PlaybackController 语义）

enum PlayMode: String, CaseIterable {
    case list      // 列表循环
    case repeatOne // 单曲循环
    case shuffle   // 随机

    var iconName: String {
        switch self {
        case .list: return "repeat"
        case .repeatOne: return "repeat.1"
        case .shuffle: return "shuffle"
        }
    }

    var label: String {
        switch self {
        case .list: return "列表循环"
        case .repeatOne: return "单曲循环"
        case .shuffle: return "随机播放"
        }
    }
}

@MainActor
final class PlayerController: NSObject, ObservableObject {
    static let shared = PlayerController()

    private let player = AVPlayer()
    private let engine = ResolveEngine.shared

    @Published var queue: [Song] = []
    @Published var index: Int = -1
    @Published var isPlaying = false
    @Published var isBuffering = false
    /// 秒
    @Published var currentTime: Double = 0
    @Published var duration: Double = 0
    /// 当前策略解析出来的封面（Song 无封面时回填）
    @Published var resolvedCover: String?
    /// 解析/播放失败的完整信息（诊断用）
    @Published var lastError: String?
    @Published var mode: PlayMode = .list

    var currentSong: Song? {
        (0..<queue.count).contains(index) ? queue[index] : nil
    }

    private var playToken = UUID()
    private var timeObserver: Any?

    override private init() {
        super.init()
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        addTimeObserver()
        observeEnd()
        UIApplication.shared.beginReceivingRemoteControlEvents()
        setupRemoteCommands()
    }

    // MARK: 播放入口

    func play(queue songs: [Song], at index: Int) {
        guard !songs.isEmpty, songs.indices.contains(index) else { return }
        self.queue = songs
        self.index = index
        playCurrent()
    }

    func playCurrent() {
        guard let song = currentSong else { return }
        playToken = UUID()
        let token = playToken

        isBuffering = true
        lastError = nil
        currentTime = 0
        duration = Double(song.durationMs) / 1000
        resolvedCover = nil
        updateNowPlaying()

        Task { [weak self] in
            guard let self else { return }
            do {
                let track = try await engine.resolve(song: song)
                // 过期回包丢弃（切歌竞态）
                guard self.playToken == token else { return }
                self.resolvedCover = track.coverUrl
                self.startPlayback(track: track)
            } catch {
                guard self.playToken == token else { return }
                self.isBuffering = false
                self.isPlaying = false
                let ex = error as? AppException
                self.lastError = "\(ex?.code ?? AppError.unknown): \(ex?.detail ?? error.localizedDescription)"
            }
        }
    }

    private func startPlayback(track: ResolvedTrack) {
        let isLocal = track.strategyId == "local.file"
        let asset: AVURLAsset
        if isLocal {
            let url = LibraryRepository.shared.musicDir.appendingPathComponent(track.url)
            asset = AVURLAsset(url: url)
        } else {
            var options: [String: Any] = [:]
            if !track.headers.isEmpty {
                // AVURLAsset 的请求头注入（Referer 等）
                options["AVURLAssetHTTPHeaderFieldsKey"] = track.headers
            }
            asset = AVURLAsset(url: URL(string: track.url)!, options: options)
        }
        let item = AVPlayerItem(asset: asset)
        player.replaceCurrentItem(with: item)
        player.play()
        isBuffering = false
        isPlaying = true
        // 异步读真实时长
        Task { [weak self] in
            let seconds = try? await asset.load(.duration).seconds
            guard let self, let s = seconds, s.isFinite, s > 0 else { return }
            self.duration = s
            self.updateNowPlaying()
        }
        updateNowPlaying()
    }

    // MARK: 控制

    func togglePlay() {
        if isPlaying {
            player.pause()
            isPlaying = false
        } else {
            player.play()
            isPlaying = true
        }
        updateNowPlaying()
    }

    func seek(to seconds: Double) {
        player.seek(
            to: CMTime(seconds: seconds, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
        currentTime = seconds
    }

    func next(auto: Bool = false) {
        guard !queue.isEmpty else { return }
        if auto, mode == .repeatOne {
            seek(to: 0)
            player.play()
            isPlaying = true
            return
        }
        if mode == .shuffle {
            index = queue.count > 1 ? (0..<queue.count).randomElement() ?? 0 : 0
            playCurrent()
            return
        }
        if index + 1 < queue.count {
            index += 1
            playCurrent()
        } else if !auto {
            // 手动点下一首：列表尾回卷
            index = 0
            playCurrent()
        } else {
            // 自动播完：列表循环回卷
            index = 0
            playCurrent()
        }
    }

    func previous() {
        guard !queue.isEmpty else { return }
        index = index - 1 >= 0 ? index - 1 : queue.count - 1
        playCurrent()
    }

    func cycleMode() {
        let all = PlayMode.allCases
        let idx = all.firstIndex(of: mode) ?? 0
        mode = all[(idx + 1) % all.count]
    }

    // MARK: 进度与播完

    private func addTimeObserver() {
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.currentTime = time.seconds.isFinite ? time.seconds : 0
                let playing = self.player.timeControlStatus == .playing
                self.isPlaying = playing
                self.isBuffering = self.player.timeControlStatus == .waitingToPlayAtSpecifiedRate
            }
        }
    }

    private func observeEnd() {
        NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.next(auto: true)
            }
        }
    }

    // MARK: 锁屏控制（MPNowPlayingInfoCenter + 远程命令）

    private func setupRemoteCommands() {
        MPRemoteCommandCenter.shared().playCommand.addTarget { _ in
            Task { @MainActor in self.togglePlay() }
            return .success
        }
        MPRemoteCommandCenter.shared().pauseCommand.addTarget { _ in
            Task { @MainActor in self.togglePlay() }
            return .success
        }
        MPRemoteCommandCenter.shared().nextTrackCommand.addTarget { _ in
            Task { @MainActor in self.next() }
            return .success
        }
        MPRemoteCommandCenter.shared().previousTrackCommand.addTarget { _ in
            Task { @MainActor in self.previous() }
            return .success
        }
    }

    private func updateNowPlaying() {
        var info: [String: Any] = [:]
        if let song = currentSong {
            info[MPMediaItemPropertyTitle] = song.title
            info[MPMediaItemPropertyArtist] = song.artist.isEmpty ? "未知歌手" : song.artist
            info[MPMediaItemPropertyAlbumTitle] = song.album
            info[MPMediaItemPropertyPlaybackDuration] = duration
            info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentTime
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
            // 封面异步加载
            guard let coverUrl = song.coverUrl ?? resolvedCover, let url = URL(string: coverUrl) else { return }
            URLSession.shared.dataTask(with: url) { data, _, _ in
                guard let data, let image = UIImage(data: data) else { return }
                Task { @MainActor in
                    var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                    info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                    MPNowPlayingInfoCenter.default().nowPlayingInfo = info
                }
            }.resume()
        }
    }
}

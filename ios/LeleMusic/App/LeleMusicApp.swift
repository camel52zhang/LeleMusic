import SwiftUI

@main
struct LeleMusicApp: App {
    @StateObject private var player = PlayerController.shared
    @StateObject private var library = LibraryRepository.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(player)
                .environmentObject(library)
                .tint(.accentColor)
        }
    }
}

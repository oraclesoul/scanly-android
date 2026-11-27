import SwiftUI

@main
struct ScanlyApp: App {
    @StateObject private var repo: DocRepository
    @StateObject private var settings: AppSettings
    @StateObject private var library: PDFLibrary

    init() {
        // test hook: start from a clean slate
        if ProcessInfo.processInfo.arguments.contains("-resetData") {
            Storage.wipeAll()
        }
        _repo = StateObject(wrappedValue: DocRepository())
        _settings = StateObject(wrappedValue: AppSettings())
        _library = StateObject(wrappedValue: PDFLibrary())
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(repo)
                .environmentObject(settings)
                .environmentObject(library)
                .task {
                    if ProcessInfo.processInfo.arguments.contains("-seedDemo"), repo.docs.isEmpty {
                        await DemoSeed.seed(into: repo)
                    }
                }
        }
    }
}

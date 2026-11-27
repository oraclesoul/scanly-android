import Foundation

/// Where exported PDFs are saved. Nothing chosen means the default Scanly folder in the Files app.
@MainActor
final class AppSettings: ObservableObject {
    @Published private(set) var folderBookmark: Data?
    @Published private(set) var folderName: String?

    private let bookmarkKey = "saveFolderBookmark"
    private let nameKey = "saveFolderName"

    init() {
        let defaults = UserDefaults.standard
        folderBookmark = defaults.data(forKey: bookmarkKey)
        folderName = defaults.string(forKey: nameKey)
        // a bookmark that no longer resolves falls back to the default folder
        if let data = folderBookmark {
            var stale = false
            if (try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale)) == nil {
                reset()
            }
        }
    }

    var locationLabel: String {
        if folderBookmark != nil, let name = folderName { return name }
        return Storage.defaultLocationLabel
    }

    var isCustom: Bool { folderBookmark != nil }

    /// `url` comes from the folder picker and is still security scoped.
    func setFolder(url: URL) -> Bool {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) else {
            return false
        }
        folderBookmark = data
        folderName = url.lastPathComponent
        UserDefaults.standard.set(data, forKey: bookmarkKey)
        UserDefaults.standard.set(url.lastPathComponent, forKey: nameKey)
        return true
    }

    func reset() {
        folderBookmark = nil
        folderName = nil
        UserDefaults.standard.removeObject(forKey: bookmarkKey)
        UserDefaults.standard.removeObject(forKey: nameKey)
    }
}

enum FolderAccess {
    struct Handle {
        let url: URL
        let close: () -> Void
    }

    enum Failure: Error { case unavailable }

    /// Opens the folder behind a bookmark (or the default folder) for reading and writing.
    static func open(bookmark: Data?) throws -> Handle {
        guard let bookmark else {
            return Handle(url: Storage.defaultPDFFolder, close: {})
        }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else {
            throw Failure.unavailable
        }
        let scoped = url.startAccessingSecurityScopedResource()
        return Handle(url: url, close: { if scoped { url.stopAccessingSecurityScopedResource() } })
    }

    static func uniqueURL(in folder: URL, fileName: String) -> URL {
        let fm = FileManager.default
        var candidate = folder.appendingPathComponent(fileName)
        guard fm.fileExists(atPath: candidate.path) else { return candidate }
        let base = (fileName as NSString).deletingPathExtension
        let ext = (fileName as NSString).pathExtension
        var n = 1
        repeat {
            candidate = folder.appendingPathComponent("\(base) (\(n)).\(ext)")
            n += 1
        } while fm.fileExists(atPath: candidate.path)
        return candidate
    }
}

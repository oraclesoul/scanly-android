import Foundation

enum PageFilter: String, Codable, CaseIterable, Identifiable {
    case original, cropped, enhanced, xerox

    var id: String { rawValue }

    var label: String {
        switch self {
        case .original: return "Original"
        case .cropped: return "Cropped"
        case .enhanced: return "Enhanced"
        case .xerox: return "Xerox"
        }
    }
}

/// `corners` holds 4 normalized (0...1) points as x,y pairs, top-left origin, ordered TL, TR, BR, BL.
struct ScanPage: Identifiable, Codable, Equatable {
    var id: String
    var corners: [Double]
    var filter: PageFilter
    var version: Int
}

struct ScanDoc: Identifiable, Codable, Equatable {
    var id: String
    var name: String
    var createdAt: Date
    var updatedAt: Date
    var pages: [ScanPage]
}

struct PDFRecord: Identifiable, Codable, Equatable {
    var id: String
    /// File name including the .pdf extension.
    var name: String
    /// Bookmark of the folder the PDF was saved in; nil means the default Scanly folder.
    var folderBookmark: Data?
    var location: String
    var createdAt: Date
    var pageCount: Int
    var sizeBytes: Int64

    var title: String { name.hasSuffix(".pdf") ? String(name.dropLast(4)) : name }
}

enum PDFPageSize: String, CaseIterable, Identifiable {
    case a4 = "A4"
    case fit = "Fit to image"
    var id: String { rawValue }
}

enum PDFQuality: String, CaseIterable, Identifiable {
    case high = "High"
    case balanced = "Balanced"
    case small = "Small"
    var id: String { rawValue }

    var maxSide: CGFloat {
        switch self {
        case .high: return 2480
        case .balanced: return 1754
        case .small: return 1240
        }
    }

    var jpegQuality: CGFloat {
        switch self {
        case .high: return 0.90
        case .balanced: return 0.85
        case .small: return 0.70
        }
    }
}

enum Route: Hashable {
    case doc(String)
    case pdf(String)
    case settings
    case page(String, Int)
    case crop(String, String)
}

enum Storage {
    static func ensure(_ url: URL) -> URL {
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static var support: URL {
        ensure(FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0])
    }

    static var docsRoot: URL { ensure(support.appendingPathComponent("docs", isDirectory: true)) }
    static var pdfThumbs: URL { ensure(support.appendingPathComponent("pdfthumbs", isDirectory: true)) }
    static var pdfIndex: URL { support.appendingPathComponent("pdfs.json") }

    /// Visible in the Files app under "On My iPhone > Scanly" because the app shares its Documents folder.
    static var defaultPDFFolder: URL {
        ensure(FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Scanly", isDirectory: true))
    }

    static let defaultLocationLabel = "On My iPhone › Scanly"

    static func wipeAll() {
        let fm = FileManager.default
        try? fm.removeItem(at: support.appendingPathComponent("docs"))
        try? fm.removeItem(at: support.appendingPathComponent("pdfthumbs"))
        try? fm.removeItem(at: pdfIndex)
        if let files = try? fm.contentsOfDirectory(at: defaultPDFFolder, includingPropertiesForKeys: nil) {
            files.forEach { try? fm.removeItem(at: $0) }
        }
        if let id = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: id)
        }
    }
}

func sanitizeFileName(_ raw: String) -> String {
    let bad = CharacterSet(charactersIn: "\\/:*?\"<>|").union(.controlCharacters)
    var cleaned = raw.components(separatedBy: bad).joined(separator: "_").trimmingCharacters(in: .whitespaces)
    while cleaned.hasSuffix(".") { cleaned.removeLast() }
    return cleaned.isEmpty ? "Scan" : cleaned
}

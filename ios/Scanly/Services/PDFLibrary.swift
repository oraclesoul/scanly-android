import UIKit
import PDFKit

/// Remembers the PDFs Scanly has exported so they can be listed, viewed, renamed and deleted.
@MainActor
final class PDFLibrary: ObservableObject {
    @Published private(set) var pdfs: [PDFRecord] = []

    init() {
        pdfs = load()
    }

    func record(_ id: String) -> PDFRecord? { pdfs.first { $0.id == id } }

    func thumbURL(_ id: String) -> URL {
        Storage.pdfThumbs.appendingPathComponent("\(id).jpg")
    }

    func add(_ record: PDFRecord, thumbnailFrom thumb: URL?) {
        if let thumb {
            try? FileManager.default.removeItem(at: thumbURL(record.id))
            try? FileManager.default.copyItem(at: thumb, to: thumbURL(record.id))
        }
        pdfs = sorted(pdfs + [record])
        save()
    }

    /// Runs `body` with the real file URL, keeping folder access open while it runs.
    func withFile<T>(_ record: PDFRecord, _ body: (URL) throws -> T) throws -> T {
        let handle = try FolderAccess.open(bookmark: record.folderBookmark)
        defer { handle.close() }
        return try body(handle.url.appendingPathComponent(record.name))
    }

    /// A temporary copy that stays valid after folder access is closed, for viewing and sharing.
    func temporaryCopy(of record: PDFRecord) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("view-\(record.id)", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let copy = dir.appendingPathComponent(record.name)
        try withFile(record) { try FileManager.default.copyItem(at: $0, to: copy) }
        return copy
    }

    /// Drops entries whose file no longer exists and picks up size changes made elsewhere.
    func refresh() {
        var changed = false
        var kept: [PDFRecord] = []
        for var r in pdfs {
            guard let handle = try? FolderAccess.open(bookmark: r.folderBookmark) else {
                kept.append(r) // folder temporarily unreachable: keep the entry
                continue
            }
            let url = handle.url.appendingPathComponent(r.name)
            let exists = FileManager.default.fileExists(atPath: url.path)
            if exists, let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int64, size != r.sizeBytes {
                r.sizeBytes = size
                changed = true
            }
            handle.close()
            if exists { kept.append(r) } else {
                try? FileManager.default.removeItem(at: thumbURL(r.id))
                changed = true
            }
        }
        if changed {
            pdfs = sorted(kept)
            save()
        }
    }

    func rename(_ record: PDFRecord, to newBaseName: String) -> Bool {
        let newName = sanitizeFileName(newBaseName) + ".pdf"
        if newName == record.name { return true }
        do {
            try withFile(record) { source in
                let target = source.deletingLastPathComponent().appendingPathComponent(newName)
                guard !FileManager.default.fileExists(atPath: target.path) else { throw CocoaError(.fileWriteFileExists) }
                try FileManager.default.moveItem(at: source, to: target)
            }
        } catch {
            return false
        }
        if let i = pdfs.firstIndex(where: { $0.id == record.id }) {
            pdfs[i].name = newName
            save()
        }
        return true
    }

    func delete(_ record: PDFRecord) -> Bool {
        do {
            try withFile(record) { url in
                if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
            }
        } catch {
            return false
        }
        pdfs.removeAll { $0.id == record.id }
        try? FileManager.default.removeItem(at: thumbURL(record.id))
        save()
        return true
    }

    private func sorted(_ list: [PDFRecord]) -> [PDFRecord] { list.sorted { $0.createdAt > $1.createdAt } }

    private func save() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        if let data = try? encoder.encode(pdfs) {
            try? data.write(to: Storage.pdfIndex, options: .atomic)
        }
    }

    private func load() -> [PDFRecord] {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let data = try? Data(contentsOf: Storage.pdfIndex),
              let list = try? decoder.decode([PDFRecord].self, from: data) else { return [] }
        return sorted(list)
    }
}

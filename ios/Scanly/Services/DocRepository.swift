import UIKit

private actor SerialGate {
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func enter() async {
        if busy {
            await withCheckedContinuation { waiters.append($0) }
        } else {
            busy = true
        }
    }

    func leave() {
        if waiters.isEmpty {
            busy = false
        } else {
            waiters.removeFirst().resume()
        }
    }
}

@MainActor
final class DocRepository: ObservableObject {
    @Published private(set) var docs: [ScanDoc] = []

    private let root = Storage.docsRoot
    private let gate = SerialGate()

    init() {
        docs = loadAll()
    }

    func doc(_ id: String) -> ScanDoc? { docs.first { $0.id == id } }

    func pageURL(_ docId: String, _ pageId: String) -> URL {
        root.appendingPathComponent(docId).appendingPathComponent("\(pageId).jpg")
    }

    func thumbURL(_ docId: String, _ page: ScanPage) -> URL {
        root.appendingPathComponent(docId).appendingPathComponent("\(page.id)_t\(page.version).jpg")
    }

    @discardableResult
    func createDoc(named: String? = nil) -> ScanDoc {
        let now = Date()
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH.mm"
        let name = named ?? "Scan " + formatter.string(from: now)
        let doc = ScanDoc(id: UUID().uuidString, name: name, createdAt: now, updatedAt: now, pages: [])
        _ = Storage.ensure(root.appendingPathComponent(doc.id))
        persist(doc)
        docs = sorted(docs + [doc])
        return doc
    }

    /// Imports one image: normalizes it, auto-detects the edges and writes the thumbnail.
    func addPage(docId: String, image: UIImage) async -> Bool {
        await gate.enter()
        defer { Task { await gate.leave() } }
        guard doc(docId) != nil else { return false }

        let pageId = UUID().uuidString
        let pageFile = pageURL(docId, pageId)
        let page: ScanPage? = await Task.detached(priority: .userInitiated) { () -> ScanPage? in
            let norm = ImageProcessor.normalized(image, maxSide: ImageProcessor.storeMax)
            guard ImageProcessor.saveJPEG(norm, to: pageFile, quality: 0.95) else { return nil }
            let corners = ImageProcessor.detectCorners(norm) ?? ImageProcessor.defaultCorners
            return ScanPage(id: pageId, corners: corners, filter: .cropped, version: 1)
        }.value
        guard let page else { return false }

        let thumb = thumbURL(docId, page)
        await Task.detached(priority: .userInitiated) {
            Self.makeThumb(from: pageFile, page: page, to: thumb)
        }.value
        return mutate(docId) { $0.pages.append(page); $0.updatedAt = Date() }
    }

    func editPage(docId: String, pageId: String, _ change: @escaping (inout ScanPage) -> Void) async {
        guard let current = doc(docId)?.pages.first(where: { $0.id == pageId }) else { return }
        var updated = current
        change(&updated)
        updated.version = current.version + 1

        let source = pageURL(docId, pageId)
        let newThumb = thumbURL(docId, updated)
        let snapshot = updated
        await Task.detached(priority: .userInitiated) {
            Self.makeThumb(from: source, page: snapshot, to: newThumb)
        }.value

        let applied = mutate(docId) { d in
            guard let i = d.pages.firstIndex(where: { $0.id == pageId }) else { return }
            d.pages[i] = snapshot
            d.updatedAt = Date()
        }
        try? FileManager.default.removeItem(at: applied ? thumbURL(docId, current) : newThumb)
    }

    func setFilter(docId: String, pageIds: Set<String>, filter: PageFilter) async {
        let ids = doc(docId)?.pages.filter { pageIds.contains($0.id) && $0.filter != filter }.map(\.id) ?? []
        for id in ids { await editPage(docId: docId, pageId: id) { $0.filter = filter } }
    }

    func rotatePage(docId: String, pageId: String, clockwise: Bool) async {
        let file = pageURL(docId, pageId)
        await Task.detached(priority: .userInitiated) {
            guard let img = ImageProcessor.load(file, maxSide: ImageProcessor.storeMax) else { return }
            ImageProcessor.saveJPEG(ImageProcessor.rotate(img, clockwise: clockwise), to: file, quality: 0.95)
        }.value
        await editPage(docId: docId, pageId: pageId) {
            $0.corners = ImageProcessor.rotateCorners($0.corners, clockwise: clockwise)
        }
    }

    func setOrder(docId: String, ids: [String]) {
        mutate(docId) { d in
            let byId = Dictionary(uniqueKeysWithValues: d.pages.map { ($0.id, $0) })
            let reordered = ids.compactMap { byId[$0] }
            if reordered.count == d.pages.count {
                d.pages = reordered
                d.updatedAt = Date()
            }
        }
    }

    func movePage(docId: String, pageId: String, delta: Int) {
        guard let pages = doc(docId)?.pages, let from = pages.firstIndex(where: { $0.id == pageId }) else { return }
        let to = from + delta
        guard pages.indices.contains(to) else { return }
        var ids = pages.map(\.id)
        ids.insert(ids.remove(at: from), at: to)
        setOrder(docId: docId, ids: ids)
    }

    /// Drag and drop: puts `pageId` at the position currently held by `targetId`.
    func movePage(docId: String, pageId: String, onto targetId: String) {
        guard var ids = doc(docId)?.pages.map(\.id),
              let from = ids.firstIndex(of: pageId), let to = ids.firstIndex(of: targetId), from != to else { return }
        ids.insert(ids.remove(at: from), at: to)
        setOrder(docId: docId, ids: ids)
    }

    /// Removes several pages at once; deleting every page removes the document itself.
    func deletePages(docId: String, ids: Set<String>) {
        guard let d = doc(docId) else { return }
        let doomed = d.pages.filter { ids.contains($0.id) }
        if doomed.isEmpty { return }
        if doomed.count == d.pages.count {
            deleteDoc(docId)
            return
        }
        mutate(docId) { $0.pages.removeAll { ids.contains($0.id) }; $0.updatedAt = Date() }
        for p in doomed {
            try? FileManager.default.removeItem(at: pageURL(docId, p.id))
            try? FileManager.default.removeItem(at: thumbURL(docId, p))
        }
    }

    func rename(docId: String, name: String) {
        let clean = name.trimmingCharacters(in: .whitespaces)
        guard !clean.isEmpty else { return }
        mutate(docId) { $0.name = clean; $0.updatedAt = Date() }
    }

    func deleteDoc(_ id: String) {
        docs.removeAll { $0.id == id }
        try? FileManager.default.removeItem(at: root.appendingPathComponent(id))
    }

    // MARK: - Internals

    nonisolated private static func makeThumb(from pageFile: URL, page: ScanPage, to thumb: URL) {
        guard let src = ImageProcessor.load(pageFile, maxSide: 900) else { return }
        let rendered = ImageProcessor.render(src, page: page, maxSide: 480)
        ImageProcessor.saveJPEG(rendered, to: thumb, quality: 0.85)
    }

    @discardableResult
    private func mutate(_ id: String, _ change: (inout ScanDoc) -> Void) -> Bool {
        guard let i = docs.firstIndex(where: { $0.id == id }) else { return false }
        var d = docs[i]
        change(&d)
        persist(d)
        var next = docs
        next[i] = d
        docs = sorted(next)
        return true
    }

    private func sorted(_ list: [ScanDoc]) -> [ScanDoc] { list.sorted { $0.updatedAt > $1.updatedAt } }

    private func persist(_ doc: ScanDoc) {
        let dir = Storage.ensure(root.appendingPathComponent(doc.id))
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        if let data = try? encoder.encode(doc) {
            try? data.write(to: dir.appendingPathComponent("doc.json"), options: .atomic)
        }
    }

    private func loadAll() -> [ScanDoc] {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let dirs = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        let loaded = dirs.compactMap { dir -> ScanDoc? in
            guard let data = try? Data(contentsOf: dir.appendingPathComponent("doc.json")) else { return nil }
            return try? decoder.decode(ScanDoc.self, from: data)
        }
        return sorted(loaded)
    }
}

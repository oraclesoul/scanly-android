import UIKit

enum PDFExporter {
    struct Result {
        let fileName: String
        let sizeBytes: Int64
    }

    /// Writes the pages as a PDF into the folder behind `bookmark` (or the default folder).
    nonisolated static func export(
        pages: [(url: URL, page: ScanPage)],
        name: String,
        size: PDFPageSize,
        quality: PDFQuality,
        bookmark: Data?,
        progress: @escaping (Int, Int) -> Void
    ) throws -> Result {
        let safeName = sanitizeFileName(name)
        let title = safeName
        let renderer = UIGraphicsPDFRenderer(
            bounds: CGRect(x: 0, y: 0, width: 595.28, height: 841.89),
            format: {
                let f = UIGraphicsPDFRendererFormat()
                f.documentInfo = [kCGPDFContextTitle as String: title, kCGPDFContextCreator as String: "Scanly"]
                return f
            }()
        )

        var failed = false
        let data = renderer.pdfData { ctx in
            for (index, entry) in pages.enumerated() {
                progress(index, pages.count)
                guard let original = ImageProcessor.load(entry.url, maxSide: ImageProcessor.storeMax) else {
                    failed = true
                    break
                }
                let rendered = ImageProcessor.render(original, page: entry.page, maxSide: quality.maxSide)
                // recompress so the file size follows the chosen quality
                guard let jpeg = rendered.jpegData(compressionQuality: quality.jpegQuality),
                      let image = UIImage(data: jpeg) else {
                    failed = true
                    break
                }
                let iw = image.size.width * image.scale
                let ih = image.size.height * image.scale
                let pageRect: CGRect
                let drawRect: CGRect
                switch size {
                case .a4:
                    let landscape = iw > ih
                    let pw: CGFloat = landscape ? 841.89 : 595.28
                    let ph: CGFloat = landscape ? 595.28 : 841.89
                    let s = min(pw / iw, ph / ih)
                    pageRect = CGRect(x: 0, y: 0, width: pw, height: ph)
                    drawRect = CGRect(x: (pw - iw * s) / 2, y: (ph - ih * s) / 2, width: iw * s, height: ih * s)
                case .fit:
                    let s = 841.89 / max(iw, ih)
                    pageRect = CGRect(x: 0, y: 0, width: iw * s, height: ih * s)
                    drawRect = pageRect
                }
                ctx.beginPage(withBounds: pageRect, pageInfo: [:])
                image.draw(in: drawRect)
            }
        }
        if failed { throw CocoaError(.fileReadCorruptFile) }
        progress(pages.count, pages.count)

        let handle = try FolderAccess.open(bookmark: bookmark)
        defer { handle.close() }
        let target = FolderAccess.uniqueURL(in: handle.url, fileName: safeName + ".pdf")
        try data.write(to: target, options: .atomic)
        return Result(fileName: target.lastPathComponent, sizeBytes: Int64(data.count))
    }
}

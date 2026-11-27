import UIKit

/// Generates photo-like test pages (a skewed sheet of paper on a dark desk) so the app can be exercised without a camera.
enum DemoSeed {
    @MainActor
    static func seed(into repo: DocRepository) async {
        let doc = repo.createDoc(named: "Demo scan")
        let pages = [
            ("PAGE ONE", ["Invoice 2026-001", "Hello from Scanly!", "The quick brown fox jumps over the lazy dog."]),
            ("PAGE TWO", ["Meeting notes", "Reorder test document", "Second page content goes here."]),
            ("PAGE THREE", ["Receipt", "Total due: 42.00", "Thank you for your business."]),
            ("PAGE FOUR", ["Agreement", "Signed and dated", "Keep a copy for your records."])
        ]
        for (i, p) in pages.enumerated() {
            _ = await repo.addPage(docId: doc.id, image: makePhoto(title: p.0, lines: p.1, tilt: CGFloat(i) * 0.02 - 0.03))
        }
    }

    static func makePhoto(title: String, lines: [String], tilt: CGFloat) -> UIImage {
        let size = CGSize(width: 1500, height: 2000)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            let c = ctx.cgContext
            UIColor(red: 0.18, green: 0.13, blue: 0.10, alpha: 1).setFill()
            c.fill(CGRect(origin: .zero, size: size))

            c.saveGState()
            c.translateBy(x: 750, y: 1000)
            c.rotate(by: tilt)
            c.concatenate(CGAffineTransform(a: 1, b: 0.03, c: -0.05, d: 1, tx: 0, ty: 0))
            let paper = CGRect(x: -560, y: -780, width: 1120, height: 1560)
            let colors = [UIColor(white: 0.97, alpha: 1).cgColor, UIColor(white: 0.72, alpha: 1).cgColor] as CFArray
            c.saveGState()
            c.clip(to: paper)
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                c.drawLinearGradient(gradient, start: CGPoint(x: paper.minX, y: 0), end: CGPoint(x: paper.maxX, y: 0), options: [])
            }
            c.restoreGState()

            let titleAttrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 80), .foregroundColor: UIColor(white: 0.08, alpha: 1)]
            let bodyAttrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 38), .foregroundColor: UIColor(white: 0.15, alpha: 1)]
            (title as NSString).draw(at: CGPoint(x: paper.minX + 80, y: paper.minY + 90), withAttributes: titleAttrs)
            for (i, line) in lines.enumerated() {
                (line as NSString).draw(at: CGPoint(x: paper.minX + 80, y: paper.minY + 260 + CGFloat(i) * 64), withAttributes: bodyAttrs)
            }
            UIColor(white: 0.1, alpha: 1).setStroke()
            let box = UIBezierPath(rect: CGRect(x: paper.minX + 80, y: paper.minY + 1050, width: 960, height: 260))
            box.lineWidth = 5
            box.stroke()
            c.restoreGState()
        }
    }
}

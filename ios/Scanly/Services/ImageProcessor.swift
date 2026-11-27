import UIKit
import CoreImage
import CoreImage.CIFilterBuiltins
import Vision
import Accelerate

enum ImageProcessor {
    static let storeMax: CGFloat = 3200
    static let defaultCorners: [Double] = [0.06, 0.06, 0.94, 0.06, 0.94, 0.94, 0.06, 0.94]
    private static let ciContext = CIContext(options: [.cacheIntermediates: false])

    // MARK: - Loading and saving

    /// Applies the EXIF orientation and downsizes so the longest side is at most `maxSide` pixels.
    static func normalized(_ image: UIImage, maxSide: CGFloat) -> UIImage {
        let px = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        let scale = min(1, maxSide / max(px.width, px.height))
        let target = CGSize(width: max(1, (px.width * scale).rounded()), height: max(1, (px.height * scale).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            UIColor.white.setFill()
            UIRectFill(CGRect(origin: .zero, size: target))
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    static func scaled(_ image: UIImage, maxSide: CGFloat) -> UIImage {
        guard maxSide > 0, max(image.size.width, image.size.height) > maxSide else { return image }
        return normalized(image, maxSide: maxSide)
    }

    @discardableResult
    static func saveJPEG(_ image: UIImage, to url: URL, quality: CGFloat) -> Bool {
        guard let data = image.jpegData(compressionQuality: quality) else { return false }
        do {
            try data.write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    static func load(_ url: URL, maxSide: CGFloat) -> UIImage? {
        guard let data = try? Data(contentsOf: url), let img = UIImage(data: data) else { return nil }
        return scaled(img, maxSide: maxSide)
    }

    static func rotate(_ image: UIImage, clockwise: Bool) -> UIImage {
        let w = image.size.width * image.scale
        let h = image.size.height * image.scale
        let size = CGSize(width: h, height: w)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            let c = ctx.cgContext
            c.translateBy(x: size.width / 2, y: size.height / 2)
            c.rotate(by: clockwise ? .pi / 2 : -.pi / 2)
            image.draw(in: CGRect(x: -w / 2, y: -h / 2, width: w, height: h))
        }
    }

    static func rotateCorners(_ c: [Double], clockwise: Bool) -> [Double] {
        let pts = (0..<4).map { i -> (Double, Double) in
            let x = c[i * 2], y = c[i * 2 + 1]
            return clockwise ? (1 - y, x) : (y, 1 - x)
        }
        return orderPoints(pts)
    }

    /// Orders any four points as TL, TR, BR, BL and returns them flattened.
    static func orderPoints(_ pts: [(Double, Double)]) -> [Double] {
        guard pts.count == 4 else { return defaultCorners }
        let tl = pts.min { $0.0 + $0.1 < $1.0 + $1.1 }!
        let br = pts.max { $0.0 + $0.1 < $1.0 + $1.1 }!
        let tr = pts.min { $0.1 - $0.0 < $1.1 - $1.0 }!
        let bl = pts.max { $0.1 - $0.0 < $1.1 - $1.0 }!
        return [tl.0, tl.1, tr.0, tr.1, br.0, br.1, bl.0, bl.1]
    }

    // MARK: - Edge detection

    /// Finds the document outline. Tries Apple's Vision first and checks that the answer is plausible,
    /// then falls back to a brightness-based detector. Returns nil when nothing convincing is found.
    static func detectCorners(_ image: UIImage) -> [Double]? {
        if let cg = image.cgImage {
            let handler = VNImageRequestHandler(cgImage: cg, options: [:])

            let segmentation = VNDetectDocumentSegmentationRequest()
            if (try? handler.perform([segmentation])) != nil,
               let obs = segmentation.results?.first, obs.confidence > 0.5,
               let corners = fromObservation(obs), isPlausible(corners) {
                return corners
            }

            let rects = VNDetectRectanglesRequest()
            rects.maximumObservations = 1
            rects.minimumConfidence = 0.6
            rects.minimumAspectRatio = 0.3
            rects.minimumSize = 0.35
            rects.quadratureTolerance = 30
            if (try? handler.perform([rects])) != nil, let obs = rects.results?.first,
               let corners = fromObservation(obs), isPlausible(corners) {
                return corners
            }
        }
        return detectByBrightness(image)
    }

    /// Area of the quadrilateral as a fraction of the image (shoelace formula).
    static func quadArea(_ c: [Double]) -> Double {
        var sum = 0.0
        for i in 0..<4 {
            let j = (i + 1) % 4
            sum += c[i * 2] * c[j * 2 + 1] - c[j * 2] * c[i * 2 + 1]
        }
        return abs(sum) / 2
    }

    /// A page normally fills a good part of the frame without being the whole frame.
    private static func isPlausible(_ c: [Double]) -> Bool {
        let a = quadArea(c)
        return a >= 0.35 && a <= 0.98
    }

    /// Looks for the largest bright blob (paper on a darker surface) and takes its extreme corners.
    private static func detectByBrightness(_ image: UIImage) -> [Double]? {
        guard let cg = image.cgImage else { return nil }
        let scale = 320.0 / Double(max(cg.width, cg.height))
        let w = max(8, Int(Double(cg.width) * scale)), h = max(8, Int(Double(cg.height) * scale))
        var gray = [UInt8](repeating: 0, count: w * h)
        gray.withUnsafeMutableBytes { ptr in
            guard let ctx = CGContext(
                data: ptr.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return }
            ctx.interpolationQuality = .medium
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        }

        // Otsu threshold
        var hist = [Int](repeating: 0, count: 256)
        for v in gray { hist[Int(v)] += 1 }
        let total = w * h
        var sumAll = 0.0
        for i in 0..<256 { sumAll += Double(i * hist[i]) }
        var wB = 0, sumB = 0.0, best = 0.0, threshold = 128
        for t in 0..<256 {
            wB += hist[t]
            if wB == 0 { continue }
            let wF = total - wB
            if wF == 0 { break }
            sumB += Double(t * hist[t])
            let mB = sumB / Double(wB), mF = (sumAll - sumB) / Double(wF)
            let between = Double(wB) * Double(wF) * (mB - mF) * (mB - mF)
            if between > best { best = between; threshold = t }
        }

        // largest 4-connected bright component
        var labels = [Int32](repeating: 0, count: total)
        var stack = [Int]()
        var bestLabel: Int32 = 0
        var bestSize = 0
        var next: Int32 = 1
        for start in 0..<total where gray[start] > UInt8(threshold) && labels[start] == 0 {
            var size = 0
            stack.append(start)
            labels[start] = next
            while let p = stack.popLast() {
                size += 1
                let x = p % w, y = p / w
                if x > 0, labels[p - 1] == 0, gray[p - 1] > UInt8(threshold) { labels[p - 1] = next; stack.append(p - 1) }
                if x < w - 1, labels[p + 1] == 0, gray[p + 1] > UInt8(threshold) { labels[p + 1] = next; stack.append(p + 1) }
                if y > 0, labels[p - w] == 0, gray[p - w] > UInt8(threshold) { labels[p - w] = next; stack.append(p - w) }
                if y < h - 1, labels[p + w] == 0, gray[p + w] > UInt8(threshold) { labels[p + w] = next; stack.append(p + w) }
            }
            if size > bestSize { bestSize = size; bestLabel = next }
            next += 1
        }
        guard bestSize > 0, Double(bestSize) / Double(total) >= 0.2 else { return nil }

        // the four extreme points of the blob give the corners of a skewed sheet
        var tl = (x: 0, y: 0), tr = tl, br = tl, bl = tl
        var minSum = Int.max, maxSum = Int.min, minDiff = Int.max, maxDiff = Int.min
        for p in 0..<total where labels[p] == bestLabel {
            let x = p % w, y = p / w
            if x + y < minSum { minSum = x + y; tl = (x, y) }
            if x + y > maxSum { maxSum = x + y; br = (x, y) }
            if y - x < minDiff { minDiff = y - x; tr = (x, y) }
            if y - x > maxDiff { maxDiff = y - x; bl = (x, y) }
        }
        let fw = Double(w), fh = Double(h)
        let corners = [tl, tr, br, bl].flatMap { [Double($0.x) / fw, Double($0.y) / fh] }
        return isPlausible(corners) ? insetCorners(corners, by: 0.01) : nil
    }

    private static func insetCorners(_ c: [Double], by inset: Double) -> [Double] {
        let cx = (c[0] + c[2] + c[4] + c[6]) / 4
        let cy = (c[1] + c[3] + c[5] + c[7]) / 4
        var out = c
        for i in 0..<4 {
            out[i * 2] = min(1, max(0, c[i * 2] + (cx - c[i * 2]) * inset))
            out[i * 2 + 1] = min(1, max(0, c[i * 2 + 1] + (cy - c[i * 2 + 1]) * inset))
        }
        return out
    }

    private static func fromObservation(_ o: VNRectangleObservation) -> [Double]? {
        // Vision uses a bottom-left origin, so flip y
        let pts = [o.topLeft, o.topRight, o.bottomRight, o.bottomLeft].map { (Double($0.x), 1 - Double($0.y)) }
        // pull the corners slightly inward so the surface behind the paper doesn't leak into the crop
        return insetCorners(orderPoints(pts), by: 0.01)
    }

    // MARK: - Rendering

    /// Applies the crop and filter. `maxSide` > 0 limits the longest side of the result.
    static func render(_ original: UIImage, page: ScanPage, maxSide: CGFloat = 0) -> UIImage {
        if page.filter == .original { return scaled(original, maxSide: maxSide) }
        guard let cg = original.cgImage else { return original }

        let base = CIImage(cgImage: cg)
        let w = base.extent.width, h = base.extent.height
        func pt(_ i: Int) -> CGPoint {
            CGPoint(x: page.corners[i * 2] * Double(w), y: (1 - page.corners[i * 2 + 1]) * Double(h))
        }
        let f = CIFilter.perspectiveCorrection()
        f.inputImage = base
        f.topLeft = pt(0)
        f.topRight = pt(1)
        f.bottomRight = pt(2)
        f.bottomLeft = pt(3)
        guard var out = f.outputImage else { return original }

        var ext = out.extent.integral
        let longest = max(ext.width, ext.height)
        if maxSide > 0, longest > maxSide {
            let s = maxSide / longest
            out = out.applyingFilter("CILanczosScaleTransform", parameters: [kCIInputScaleKey: s, kCIInputAspectRatioKey: 1.0])
            ext = out.extent.integral
        }
        guard let warped = ciContext.createCGImage(out, from: ext) else { return original }

        switch page.filter {
        case .xerox: return (applyXerox(warped)).map { UIImage(cgImage: $0) } ?? UIImage(cgImage: warped)
        case .enhanced: return (applyEnhance(warped)).map { UIImage(cgImage: $0) } ?? UIImage(cgImage: warped)
        default: return UIImage(cgImage: warped)
        }
    }

    // MARK: - Filters (CPU, same maths as the Android version)

    private struct Pixels {
        var rgba: [UInt8]
        let width: Int
        let height: Int
    }

    private static func readRGBA(_ cg: CGImage) -> Pixels? {
        let w = cg.width, h = cg.height
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        let ok = buf.withUnsafeMutableBytes { ptr -> Bool in
            guard let ctx = CGContext(
                data: ptr.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            ) else { return false }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        return ok ? Pixels(rgba: buf, width: w, height: h) : nil
    }

    private static func makeImage(_ p: Pixels) -> CGImage? {
        var rgba = p.rgba
        return rgba.withUnsafeMutableBytes { ptr -> CGImage? in
            CGContext(
                data: ptr.baseAddress, width: p.width, height: p.height, bitsPerComponent: 8, bytesPerRow: p.width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            )?.makeImage()
        }
    }

    private static func grayBuffer(_ cg: CGImage, width: Int, height: Int) -> [UInt8] {
        var buf = [UInt8](repeating: 0, count: width * height)
        buf.withUnsafeMutableBytes { ptr in
            guard let ctx = CGContext(
                data: ptr.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return }
            ctx.interpolationQuality = .high
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return buf
    }

    /// Separable max filter, which erases dark text and leaves the paper tone.
    private static func maxFilter(_ src: [UInt8], _ w: Int, _ h: Int, radius r: Int) -> [UInt8] {
        var tmp = src
        var out = src
        for y in 0..<h {
            for x in 0..<w {
                var m: UInt8 = 0
                for k in max(0, x - r)...min(w - 1, x + r) { m = max(m, src[y * w + k]) }
                tmp[y * w + x] = m
            }
        }
        for y in 0..<h {
            for x in 0..<w {
                var m: UInt8 = 0
                for k in max(0, y - r)...min(h - 1, y + r) { m = max(m, tmp[k * w + x]) }
                out[y * w + x] = m
            }
        }
        return out
    }

    private static func boxBlur(_ src: [UInt8], _ w: Int, _ h: Int, radius r: Int) -> [UInt8] {
        var tmp = [Float](repeating: 0, count: w * h)
        var out = src
        for y in 0..<h {
            for x in 0..<w {
                let lo = max(0, x - r), hi = min(w - 1, x + r)
                var s: Float = 0
                for k in lo...hi { s += Float(src[y * w + k]) }
                tmp[y * w + x] = s / Float(hi - lo + 1)
            }
        }
        for y in 0..<h {
            for x in 0..<w {
                let lo = max(0, y - r), hi = min(h - 1, y + r)
                var s: Float = 0
                for k in lo...hi { s += tmp[k * w + x] }
                out[y * w + x] = UInt8(max(0, min(255, s / Float(hi - lo + 1))))
            }
        }
        return out
    }

    /// Estimates the paper and lighting layer at full resolution.
    private static func background(of cg: CGImage) -> [UInt8] {
        let w = cg.width, h = cg.height
        let s = min(1.0, 400.0 / Double(max(w, h)))
        let sw = max(1, Int(Double(w) * s)), sh = max(1, Int(Double(h) * s))
        var small = grayBuffer(cg, width: sw, height: sh)
        small = maxFilter(small, sw, sh, radius: 5)
        small = boxBlur(small, sw, sh, radius: 5)
        small = boxBlur(small, sw, sh, radius: 5)

        guard let provider = CGDataProvider(data: Data(small) as CFData),
              let smallImg = CGImage(
                width: sw, height: sh, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: sw,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
              ) else {
            return [UInt8](repeating: 255, count: w * h)
        }
        return grayBuffer(smallImg, width: w, height: h)
    }

    /// Flattens lighting and boosts contrast to a black and white photocopy look.
    private static func applyXerox(_ cg: CGImage) -> CGImage? {
        guard var px = readRGBA(cg) else { return nil }
        let bg = background(of: cg)
        let lo = 115.0, hi = 215.0
        let alpha = 255.0 / (hi - lo)
        let count = px.width * px.height
        px.rgba.withUnsafeMutableBufferPointer { p in
            for i in 0..<count {
                let r = Int(p[i * 4]), g = Int(p[i * 4 + 1]), b = Int(p[i * 4 + 2])
                let gray = Double((77 * r + 150 * g + 29 * b) >> 8)
                let norm = min(255.0, gray * 255.0 / Double(max(1, Int(bg[i]))))
                let v = UInt8(max(0, min(255, (norm - lo) * alpha)))
                p[i * 4] = v; p[i * 4 + 1] = v; p[i * 4 + 2] = v
            }
        }
        return makeImage(px)
    }

    /// Evens out lighting and whitens the paper while keeping colours.
    private static func applyEnhance(_ cg: CGImage) -> CGImage? {
        guard var px = readRGBA(cg) else { return nil }
        let bg = background(of: cg)
        let count = px.width * px.height
        px.rgba.withUnsafeMutableBufferPointer { p in
            for i in 0..<count {
                let gain = 250.0 / Double(max(1, Int(bg[i])))
                for c in 0..<3 {
                    let v = Double(p[i * 4 + c]) * gain * 1.08 - 8.0
                    p[i * 4 + c] = UInt8(max(0, min(255, v)))
                }
            }
        }
        return makeImage(px)
    }
}

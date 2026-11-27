import SwiftUI

struct CropView: View {
    let docId: String
    let pageId: String

    @EnvironmentObject private var repo: DocRepository
    @Environment(\.dismiss) private var dismiss

    @State private var image: UIImage?
    @State private var points: [CGPoint] = []   // normalized, TL TR BR BL
    @State private var saving = false

    var body: some View {
        VStack(spacing: 0) {
            GeometryReader { geo in
                if let image, points.count == 4 {
                    let box = fitted(image.size, in: geo.size)
                    ZStack {
                        Image(uiImage: image).resizable().frame(width: box.width, height: box.height)
                        overlay(size: box)
                    }
                    .frame(width: box.width, height: box.height)
                    .position(x: geo.size.width / 2, y: geo.size.height / 2)
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .padding(24)

            HStack {
                Button { autoDetect() } label: { Label("Auto detect", systemImage: "wand.and.stars") }
                    .disabled(image == nil)
                Spacer()
                Button { set(ImageProcessor.fullCorners) } label: { Label("Full image", systemImage: "arrow.counterclockwise") }
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            .background(Color(.secondarySystemBackground))
        }
        .navigationTitle("Crop")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") { save() }
                    .disabled(image == nil || points.count != 4 || saving)
                    .accessibilityIdentifier("cropDone")
            }
        }
        .task {
            guard let page = repo.doc(docId)?.pages.first(where: { $0.id == pageId }) else { dismiss(); return }
            let file = repo.pageURL(docId, pageId)
            image = await Task.detached(priority: .userInitiated) { ImageProcessor.load(file, maxSide: 1600) }.value
            set(page.corners)
        }
    }

    private func overlay(size: CGSize) -> some View {
        let px = points.map { CGPoint(x: $0.x * size.width, y: $0.y * size.height) }
        return ZStack {
            Path { p in
                p.addRect(CGRect(origin: .zero, size: size))
                p.move(to: px[0])
                for i in 1..<4 { p.addLine(to: px[i]) }
                p.closeSubpath()
            }
            .fill(Color.black.opacity(0.5), style: FillStyle(eoFill: true))

            Path { p in
                p.move(to: px[0])
                for i in 1..<4 { p.addLine(to: px[i]) }
                p.closeSubpath()
            }
            .stroke(Color.accentColor, lineWidth: 2)

            ForEach(0..<4, id: \.self) { i in
                Circle()
                    .fill(.white)
                    .overlay(Circle().stroke(Color.accentColor, lineWidth: 3))
                    .frame(width: 26, height: 26)
                    .frame(width: 56, height: 56)
                    .contentShape(Circle())
                    .position(px[i])
                    .gesture(
                        DragGesture(minimumDistance: 0, coordinateSpace: .named("crop"))
                            .onChanged { value in
                                points[i] = CGPoint(
                                    x: min(1, max(0, value.location.x / size.width)),
                                    y: min(1, max(0, value.location.y / size.height))
                                )
                            }
                    )
            }
        }
        .coordinateSpace(name: "crop")
    }

    private func fitted(_ imageSize: CGSize, in container: CGSize) -> CGSize {
        guard imageSize.width > 0, imageSize.height > 0, container.width > 0, container.height > 0 else { return .zero }
        let s = min(container.width / imageSize.width, container.height / imageSize.height)
        return CGSize(width: imageSize.width * s, height: imageSize.height * s)
    }

    private func set(_ c: [Double]) {
        points = (0..<4).map { CGPoint(x: c[$0 * 2], y: c[$0 * 2 + 1]) }
    }

    private func autoDetect() {
        guard let image else { return }
        Task {
            let found = await Task.detached(priority: .userInitiated) { ImageProcessor.detectCorners(image) }.value
            set(found ?? ImageProcessor.defaultCorners)
        }
    }

    private func save() {
        saving = true
        let ordered = ImageProcessor.orderPoints(points.map { (Double($0.x), Double($0.y)) })
        Task {
            await repo.editPage(docId: docId, pageId: pageId) {
                $0.corners = ordered
                if $0.filter == .original { $0.filter = .cropped }
            }
            dismiss()
        }
    }
}

extension ImageProcessor {
    static let fullCorners: [Double] = [0, 0, 1, 0, 1, 1, 0, 1]
}

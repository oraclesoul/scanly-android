import PDFKit
import SwiftUI

struct PDFKitView: UIViewRepresentable {
    let url: URL
    let zoom: CGFloat

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.backgroundColor = .secondarySystemBackground
        view.document = PDFDocument(url: url)
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {
        if view.document?.documentURL != url { view.document = PDFDocument(url: url) }
        view.maxScaleFactor = 6
        if zoom > 1 {
            view.autoScales = false
            view.scaleFactor = view.scaleFactorForSizeToFit * zoom
        } else {
            view.autoScales = true
        }
    }
}

struct PDFViewerView: View {
    let pdfId: String
    @EnvironmentObject private var library: PDFLibrary
    @Environment(\.dismiss) private var dismiss

    @State private var fileURL: URL?
    @State private var failed = false
    @State private var zoomIndex = 0
    private let zoomLevels: [CGFloat] = [1, 1.5, 2]

    var body: some View {
        Group {
            if let fileURL {
                PDFKitView(url: fileURL, zoom: zoomLevels[zoomIndex])
                    .ignoresSafeArea(edges: .bottom)
                    .accessibilityIdentifier("pdfViewer")
            } else if failed {
                EmptyStateView(
                    icon: "exclamationmark.triangle",
                    title: "Couldn't open this PDF",
                    message: "It may have been moved or deleted."
                )
            } else {
                ProgressView()
            }
        }
        .navigationTitle(library.record(pdfId)?.title ?? "PDF")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let fileURL {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { zoomIndex = (zoomIndex + 1) % zoomLevels.count } label: {
                        Image(systemName: zoomIndex == zoomLevels.count - 1 ? "minus.magnifyingglass" : "plus.magnifyingglass")
                    }
                    .accessibilityLabel("Zoom")
                    ShareLink(item: fileURL) { Image(systemName: "square.and.arrow.up") }
                }
            }
        }
        .task {
            guard let record = library.record(pdfId) else { failed = true; return }
            if let copy = try? library.temporaryCopy(of: record) { fileURL = copy } else { failed = true }
        }
    }
}

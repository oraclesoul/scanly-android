import SwiftUI

struct RootView: View {
    @EnvironmentObject private var repo: DocRepository
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            HomeView(path: $path)
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .doc(let id): DocumentView(docId: id, path: $path)
                    case .pdf(let id): PDFViewerView(pdfId: id)
                    case .settings: SettingsView()
                    case .page(let docId, let index): PageEditorView(docId: docId, startIndex: index, path: $path)
                    case .crop(let docId, let pageId): CropView(docId: docId, pageId: pageId)
                    }
                }
        }
    }
}

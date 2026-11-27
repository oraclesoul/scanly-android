import SwiftUI

struct PageEditorView: View {
    let docId: String
    let startIndex: Int
    @Binding var path: NavigationPath

    @EnvironmentObject private var repo: DocRepository
    @Environment(\.dismiss) private var dismiss
    @State private var index = 0
    @State private var rotating = false
    @State private var confirmDelete = false
    @State private var started = false

    private var pages: [ScanPage] { repo.doc(docId)?.pages ?? [] }
    private var page: ScanPage? { pages.indices.contains(index) ? pages[index] : nil }

    var body: some View {
        VStack(spacing: 0) {
            if pages.isEmpty {
                Color.clear.onAppear { dismiss() }
            } else {
                TabView(selection: $index) {
                    ForEach(Array(pages.enumerated()), id: \.element.id) { i, p in
                        PagePreview(docId: docId, page: p).tag(i)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))

                toolbar
            }
        }
        .navigationTitle("Page \(min(index + 1, max(pages.count, 1))) of \(pages.count)")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { confirmDelete = true } label: { Image(systemName: "trash") }
                    .accessibilityLabel("Delete page")
            }
        }
        .alert("Delete page?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) {
                if let p = page {
                    index = max(0, min(index, pages.count - 2))
                    repo.deletePages(docId: docId, ids: [p.id])
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This page will be removed from the document.")
        }
        .onAppear {
            if !started {
                index = min(max(0, startIndex), max(0, pages.count - 1))
                started = true
            }
        }
    }

    private var toolbar: some View {
        VStack(spacing: 4) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(PageFilter.allCases) { f in
                        FilterChip(title: f.label, selected: page?.filter == f) {
                            if let p = page { Task { await repo.editPage(docId: docId, pageId: p.id) { $0.filter = f } } }
                        }
                        .accessibilityIdentifier("editFilter.\(f.rawValue)")
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
            }
            HStack {
                tool("chevron.left", "Move earlier", enabled: index > 0) {
                    if let p = page { repo.movePage(docId: docId, pageId: p.id, delta: -1); index -= 1 }
                }
                tool("rotate.left", "Rotate left", enabled: !rotating) { rotate(clockwise: false) }
                tool("crop", "Crop") { if let p = page { path.append(Route.crop(docId, p.id)) } }
                tool("rotate.right", "Rotate right", enabled: !rotating) { rotate(clockwise: true) }
                tool("chevron.right", "Move later", enabled: index < pages.count - 1) {
                    if let p = page { repo.movePage(docId: docId, pageId: p.id, delta: 1); index += 1 }
                }
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 4)
        }
        .background(Color(.secondarySystemBackground))
    }

    private func tool(_ icon: String, _ label: String, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: icon).font(.title3).frame(height: 28)
                Text(label).font(.caption2)
            }
            .frame(maxWidth: .infinity)
            .frame(minHeight: 48)
        }
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    private func rotate(clockwise: Bool) {
        guard let p = page else { return }
        Task {
            rotating = true
            await repo.rotatePage(docId: docId, pageId: p.id, clockwise: clockwise)
            rotating = false
        }
    }
}

struct PagePreview: View {
    let docId: String
    let page: ScanPage
    @EnvironmentObject private var repo: DocRepository
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFit().padding(16)
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: "\(page.id)-\(page.version)") {
            let file = repo.pageURL(docId, page.id)
            let snapshot = page
            image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
                guard let src = ImageProcessor.load(file, maxSide: 1600) else { return nil }
                return ImageProcessor.render(src, page: snapshot)
            }.value
        }
    }
}

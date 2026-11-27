import PhotosUI
import SwiftUI

struct DocumentView: View {
    let docId: String
    @Binding var path: NavigationPath

    @EnvironmentObject private var repo: DocRepository
    @Environment(\.dismiss) private var dismiss

    @State private var selected = Set<String>()
    @State private var busy = false
    @State private var rename: RenameState?
    @State private var confirmDeleteDoc = false
    @State private var confirmDeletePages = false
    @State private var showExport = false
    @State private var showCamera = false
    @State private var pickerItems: [PhotosPickerItem] = []

    private var doc: ScanDoc? { repo.doc(docId) }
    private var selecting: Bool { !selectedPages.isEmpty }

    private var selectedPages: [ScanPage] {
        (doc?.pages ?? []).filter { selected.contains($0.id) }
    }

    var body: some View {
        Group {
            if let doc {
                content(doc)
            } else {
                Color.clear.onAppear { dismiss() }
            }
        }
    }

    @ViewBuilder
    private func content(_ doc: ScanDoc) -> some View {
        VStack(spacing: 0) {
            Text(selecting ? "Tap pages to select or deselect them" : "Tap the circle on a page to select it · Hold and drag a page to reorder")
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)

            ZStack {
                grid(doc)
                if busy {
                    Color.black.opacity(0.25)
                    ProgressView().controlSize(.large)
                }
            }

            if selecting { selectionBar } else { bottomBar }
        }
        .navigationTitle(selecting ? "\(selectedPages.count) selected" : doc.name)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(selecting)
        .toolbar {
            if selecting {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { selected = [] }.accessibilityIdentifier("cancelSelection")
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("Select all") { selected = Set(doc.pages.map(\.id)) }
                    Button { confirmDeletePages = true } label: { Image(systemName: "trash") }
                        .accessibilityLabel("Delete selected pages")
                        .accessibilityIdentifier("deleteSelected")
                }
            } else {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button { selected = Set(doc.pages.map(\.id)) } label: { Label("Select all pages", systemImage: "checkmark.circle") }
                        Button { rename = RenameState(text: doc.name) { repo.rename(docId: docId, name: $0) } } label: {
                            Label("Rename", systemImage: "pencil")
                        }
                        Button(role: .destructive) { confirmDeleteDoc = true } label: { Label("Delete document", systemImage: "trash") }
                    } label: { Image(systemName: "ellipsis.circle") }
                    .accessibilityLabel("More")
                }
            }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraScanView(docId: docId) { _ in showCamera = false }
        }
        .sheet(isPresented: $showExport) { ExportSheet(doc: doc, path: $path) }
        .alert("Rename", isPresented: Binding(get: { rename != nil }, set: { if !$0 { rename = nil } })) {
            TextField("Name", text: Binding(get: { rename?.text ?? "" }, set: { rename?.text = $0 }))
            Button("Save") { if let r = rename { r.onSave(r.text) }; rename = nil }
            Button("Cancel", role: .cancel) { rename = nil }
        }
        .alert("Delete document?", isPresented: $confirmDeleteDoc) {
            Button("Delete", role: .destructive) { repo.deleteDoc(docId) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("“\(doc.name)” and all its pages will be deleted.")
        }
        .alert(selectedPages.count == 1 ? "Delete page?" : "Delete \(selectedPages.count) pages?", isPresented: $confirmDeletePages) {
            Button("Delete", role: .destructive) {
                let ids = Set(selectedPages.map(\.id))
                selected = []
                repo.deletePages(docId: docId, ids: ids)
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(selectedPages.count == doc.pages.count
                 ? "These are all the pages, so the whole document will be deleted."
                 : "The selected pages will be removed from this document.")
        }
        .onChange(of: pickerItems) { items in
            guard !items.isEmpty else { return }
            Task {
                busy = true
                for item in items {
                    if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                        _ = await repo.addPage(docId: docId, image: image)
                    }
                }
                pickerItems = []
                busy = false
            }
        }
    }

    private func grid(_ doc: ScanDoc) -> some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                ForEach(Array(doc.pages.enumerated()), id: \.element.id) { index, page in
                    pageCell(page, index: index)
                        .draggable(page.id) { ThumbView(url: repo.thumbURL(docId, page)).frame(width: 90, height: 120) }
                        .dropDestination(for: String.self) { items, _ in
                            guard let dragged = items.first, dragged != page.id else { return false }
                            repo.movePage(docId: docId, pageId: dragged, onto: page.id)
                            return true
                        }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
    }

    private func pageCell(_ page: ScanPage, index: Int) -> some View {
        let isSelected = selected.contains(page.id)
        return VStack(spacing: 6) {
            ZStack(alignment: .topLeading) {
                ThumbView(url: repo.thumbURL(docId, page))
                    .aspectRatio(0.75, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                Button { toggle(page.id) } label: {
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .font(.title2)
                        .foregroundStyle(isSelected ? Color.accentColor : .white)
                        .padding(4)
                        .background(Circle().fill(isSelected ? Color.white : Color.black.opacity(0.35)).padding(4))
                }
                .accessibilityLabel(isSelected ? "Deselect page \(index + 1)" : "Select page \(index + 1)")
                .accessibilityIdentifier("select.\(index)")
            }
            HStack {
                Text("\(index + 1)").font(.subheadline.weight(.semibold))
                Spacer()
                Text(page.filter.label)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("filterLabel.\(index)")
            }
            .padding(.horizontal, 4)
        }
        .padding(8)
        .background(isSelected ? Color.accentColor.opacity(0.18) : Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(isSelected ? Color.accentColor : .clear, lineWidth: 2))
        .contentShape(RoundedRectangle(cornerRadius: 12))
        .onTapGesture {
            if selecting { toggle(page.id) } else { path.append(Route.page(docId, index)) }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("page.\(index)")
    }

    private func toggle(_ id: String) {
        if selected.contains(id) { selected.remove(id) } else { selected.insert(id) }
    }

    private var selectionBar: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Apply to \(selectedPages.count) selected")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.leading, 16)
                .padding(.top, 10)
            let common = Set(selectedPages.map(\.filter)).count == 1 ? selectedPages.first?.filter : nil
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(PageFilter.allCases) { f in
                        FilterChip(title: f.label, selected: common == f) {
                            let ids = Set(selectedPages.map(\.id))
                            Task { busy = true; await repo.setFilter(docId: docId, pageIds: ids, filter: f); busy = false }
                        }
                        .accessibilityIdentifier("filter.\(f.rawValue)")
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
            }
        }
        .padding(.bottom, 4)
        .background(Color(.secondarySystemBackground))
    }

    private var bottomBar: some View {
        HStack(spacing: 8) {
            Button { showCamera = true } label: { Image(systemName: "doc.viewfinder").font(.title3).frame(width: 44, height: 44) }
                .accessibilityLabel("Scan more pages")
            PhotosPicker(selection: $pickerItems, maxSelectionCount: 50, matching: .images) {
                Image(systemName: "photo.badge.plus").font(.title3).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Import images")
            Spacer()
            Button { showExport = true } label: {
                Label("Create PDF", systemImage: "doc.richtext")
                    .font(.headline)
                    .padding(.horizontal, 20)
                    .frame(height: 48)
                    .foregroundStyle(.white)
                    .background(Color.accentColor, in: Capsule())
            }
            .accessibilityIdentifier("createPDF")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color(.secondarySystemBackground))
    }
}

struct FilterChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .foregroundStyle(selected ? Color.accentColor : Color.primary)
                .background(selected ? Color.accentColor.opacity(0.2) : Color.clear, in: Capsule())
                .overlay(Capsule().stroke(selected ? Color.accentColor : Color.secondary.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

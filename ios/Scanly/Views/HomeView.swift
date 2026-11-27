import PhotosUI
import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var repo: DocRepository
    @EnvironmentObject private var library: PDFLibrary
    @Binding var path: NavigationPath

    private enum Tab: String { case pdfs = "PDFs", scans = "Scans" }

    @State private var tab: Tab = .pdfs
    @State private var showCamera = false
    @State private var pickerItems: [PhotosPickerItem] = []
    @State private var importing = false
    @State private var rename: RenameState?
    @State private var deletePDF: PDFRecord?
    @State private var deleteDoc: ScanDoc?
    @State private var exportDoc: ScanDoc?
    @State private var errorMessage: String?

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(spacing: 0) {
                Picker("Section", selection: $tab) {
                    Text("PDFs").tag(Tab.pdfs)
                    Text("Scans").tag(Tab.scans)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.bottom, 8)

                if tab == .pdfs { pdfList } else { scanList }
            }

            actionButtons
                .padding(20)

            if importing {
                Color.black.opacity(0.3).ignoresSafeArea()
                ProgressView().controlSize(.large).tint(.white)
            }
        }
        .navigationTitle("Scanly")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: Route.settings) { Image(systemName: "gearshape") }
                    .accessibilityLabel("Settings")
                    .accessibilityIdentifier("settingsButton")
            }
        }
        .onAppear { library.refresh() }
        .fullScreenCover(isPresented: $showCamera) {
            CameraScanView(docId: nil) { id in
                showCamera = false
                if let id { path.append(Route.doc(id)) }
            }
        }
        .sheet(item: $exportDoc) { doc in ExportSheet(doc: doc, path: $path) }
        .alert("Rename", isPresented: Binding(get: { rename != nil }, set: { if !$0 { rename = nil } })) {
            TextField("Name", text: Binding(get: { rename?.text ?? "" }, set: { rename?.text = $0 }))
                .accessibilityIdentifier("renameField")
            Button("Save") { if let r = rename { r.onSave(r.text) }; rename = nil }
            Button("Cancel", role: .cancel) { rename = nil }
        }
        .alert("Delete PDF?", isPresented: Binding(get: { deletePDF != nil }, set: { if !$0 { deletePDF = nil } })) {
            Button("Delete", role: .destructive) {
                if let p = deletePDF, !library.delete(p) { errorMessage = "Couldn't delete this PDF." }
                deletePDF = nil
            }
            Button("Cancel", role: .cancel) { deletePDF = nil }
        } message: {
            Text("“\(deletePDF?.name ?? "")” will be permanently deleted from \(deletePDF?.location ?? "").")
        }
        .alert("Delete scan?", isPresented: Binding(get: { deleteDoc != nil }, set: { if !$0 { deleteDoc = nil } })) {
            Button("Delete", role: .destructive) {
                if let d = deleteDoc { repo.deleteDoc(d.id) }
                deleteDoc = nil
            }
            Button("Cancel", role: .cancel) { deleteDoc = nil }
        } message: {
            Text("“\(deleteDoc?.name ?? "")” and all its pages will be deleted. PDFs you already created from it are not affected.")
        }
        .alert("Scanly", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
        .onChange(of: pickerItems) { items in
            guard !items.isEmpty else { return }
            Task { await importPhotos(items) }
        }
    }

    // MARK: - Lists

    private var pdfList: some View {
        Group {
            if library.pdfs.isEmpty {
                EmptyStateView(
                    icon: "doc.richtext",
                    title: "No PDFs yet",
                    message: "Scan a document or import photos, then create a PDF. It will show up here."
                )
            } else {
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(Array(library.pdfs.enumerated()), id: \.element.id) { index, pdf in
                            pdfRow(pdf, index: index)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 160)
                }
            }
        }
    }

    private func pdfRow(_ pdf: PDFRecord, index: Int) -> some View {
        CardRow(
            leading: {
                ZStack {
                    Color(.secondarySystemFill)
                    Image(systemName: "doc.richtext").foregroundStyle(.secondary)
                    ThumbView(url: library.thumbURL(pdf.id), fill: true)
                }
            },
            title: pdf.title,
            lines: [
                "\(pageCountText(pdf.pageCount)) · \(fileSize(pdf.sizeBytes)) · \(shortDate(pdf.createdAt))",
                pdf.location
            ],
            trailing: {
                Menu {
                    Button { path.append(Route.pdf(pdf.id)) } label: { Label("View", systemImage: "eye") }
                    Button {
                        rename = RenameState(text: pdf.title) { newName in
                            if !library.rename(pdf, to: newName) {
                                errorMessage = "Couldn't rename. Another file may already have that name."
                            }
                        }
                    } label: { Label("Rename", systemImage: "pencil") }
                    Button(role: .destructive) { deletePDF = pdf } label: { Label("Delete", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis")
                        .frame(width: 40, height: 40)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("More options for \(pdf.name)")
                .accessibilityIdentifier("pdfMenu.\(index)")
            }
        )
        .onTapGesture { path.append(Route.pdf(pdf.id)) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("pdfRow.\(index)")
    }

    private var scanList: some View {
        Group {
            if repo.docs.isEmpty {
                EmptyStateView(
                    icon: "doc.viewfinder",
                    title: "No scans yet",
                    message: "Tap Scan to capture a document, or import photos from your library."
                )
            } else {
                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(Array(repo.docs.enumerated()), id: \.element.id) { index, doc in
                            scanRow(doc, index: index)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 160)
                }
            }
        }
    }

    private func scanRow(_ doc: ScanDoc, index: Int) -> some View {
        CardRow(
            leading: {
                if let first = doc.pages.first {
                    ThumbView(url: repo.thumbURL(doc.id, first), fill: true)
                } else {
                    Color(.secondarySystemFill)
                }
            },
            title: doc.name,
            lines: ["\(pageCountText(doc.pages.count)) · \(shortDate(doc.updatedAt))"],
            trailing: {
                Menu {
                    Button { exportDoc = doc } label: { Label("Create PDF", systemImage: "doc.richtext") }
                    Button {
                        rename = RenameState(text: doc.name) { repo.rename(docId: doc.id, name: $0) }
                    } label: { Label("Rename", systemImage: "pencil") }
                    Button(role: .destructive) { deleteDoc = doc } label: { Label("Delete", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis")
                        .frame(width: 40, height: 40)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("More options for \(doc.name)")
            }
        )
        .onTapGesture { path.append(Route.doc(doc.id)) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("scanRow.\(index)")
    }

    // MARK: - Actions

    private var actionButtons: some View {
        VStack(alignment: .trailing, spacing: 12) {
            PhotosPicker(selection: $pickerItems, maxSelectionCount: 50, matching: .images) {
                Image(systemName: "photo.badge.plus")
                    .font(.title3)
                    .frame(width: 56, height: 56)
                    .background(Color.accentColor.opacity(0.18), in: Circle())
                    .shadow(radius: 2, y: 1)
            }
            .accessibilityLabel("Import images")
            .accessibilityIdentifier("importButton")

            Button { showCamera = true } label: {
                Label("Scan", systemImage: "doc.viewfinder")
                    .font(.headline)
                    .padding(.horizontal, 20)
                    .frame(height: 56)
                    .foregroundStyle(.white)
                    .background(Color.accentColor, in: Capsule())
                    .shadow(radius: 3, y: 2)
            }
            .accessibilityIdentifier("scanButton")
        }
    }

    private func importPhotos(_ items: [PhotosPickerItem]) async {
        importing = true
        let doc = repo.createDoc()
        var ok = 0
        for item in items {
            if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data),
               await repo.addPage(docId: doc.id, image: image) {
                ok += 1
            }
        }
        pickerItems = []
        importing = false
        if ok > 0 {
            if ok < items.count { errorMessage = "Some images could not be imported." }
            path.append(Route.doc(doc.id))
        } else {
            repo.deleteDoc(doc.id)
            errorMessage = "Those images could not be imported."
        }
    }
}

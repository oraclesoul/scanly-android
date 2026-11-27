import SwiftUI
import UniformTypeIdentifiers

struct ExportSheet: View {
    let doc: ScanDoc
    @Binding var path: NavigationPath

    @EnvironmentObject private var repo: DocRepository
    @EnvironmentObject private var library: PDFLibrary
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var size: PDFPageSize = .a4
    @State private var quality: PDFQuality = .balanced
    @State private var working = false
    @State private var progress = 0.0
    @State private var saved: PDFRecord?
    @State private var shareURL: URL?
    @State private var pickFolder = false
    @State private var errorText: String?

    var body: some View {
        NavigationStack {
            Form {
                if let saved {
                    savedSection(saved)
                } else if working {
                    Section {
                        Text("Creating PDF…")
                        ProgressView(value: progress)
                    }
                } else {
                    formSections
                }
            }
            .navigationTitle(saved == nil ? "Create PDF" : "PDF saved")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if !working {
                        Button(saved == nil ? "Cancel" : "Done") { dismiss() }
                            .accessibilityIdentifier("doneButton")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if saved == nil, !working {
                        Button("Create") { create() }
                            .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || doc.pages.isEmpty)
                            .accessibilityIdentifier("createConfirm")
                    }
                }
            }
            .fileImporter(isPresented: $pickFolder, allowedContentTypes: [.folder]) { result in
                if case .success(let url) = result, !settings.setFolder(url: url) {
                    errorText = "Couldn't use that folder."
                }
            }
            .alert("Couldn't create PDF", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
                Button("OK", role: .cancel) { errorText = nil }
            } message: {
                Text(errorText ?? "")
            }
        }
        .presentationDetents([.large])
        .interactiveDismissDisabled(working)
        .onAppear { if name.isEmpty { name = doc.name } }
    }

    @ViewBuilder
    private var formSections: some View {
        Section("PDF name") {
            HStack {
                TextField("PDF name", text: $name)
                    .accessibilityIdentifier("pdfNameField")
                    .autocorrectionDisabled()
                Text(".pdf").foregroundStyle(.secondary)
            }
        }
        Section("Save to") {
            HStack {
                Text(settings.locationLabel).foregroundStyle(.secondary)
                Spacer()
                Button("Change") { pickFolder = true }
            }
        }
        Section("Page size") {
            Picker("Page size", selection: $size) {
                ForEach(PDFPageSize.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
        }
        Section("Quality") {
            Picker("Quality", selection: $quality) {
                ForEach(PDFQuality.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
        }
        Section {
            Text(pageCountText(doc.pages.count)).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func savedSection(_ record: PDFRecord) -> some View {
        Section {
            Text(record.name).font(.headline)
            Text("Saved in \(record.location)").foregroundStyle(.secondary)
        }
        Section {
            Button {
                dismiss()
                path.append(Route.pdf(record.id))
            } label: { Label("Open", systemImage: "eye") }
            .accessibilityIdentifier("openSaved")
            if let shareURL {
                ShareLink(item: shareURL) { Label("Share", systemImage: "square.and.arrow.up") }
            }
        }
    }

    private func create() {
        working = true
        progress = 0
        let pages = doc.pages.map { (url: repo.pageURL(doc.id, $0.id), page: $0) }
        let bookmark = settings.folderBookmark
        let location = settings.locationLabel
        let thumb = doc.pages.first.map { repo.thumbURL(doc.id, $0) }
        let chosenName = name, chosenSize = size, chosenQuality = quality

        Task {
            do {
                let result = try await Task.detached(priority: .userInitiated) { () -> PDFExporter.Result in
                    try PDFExporter.export(
                        pages: pages, name: chosenName, size: chosenSize, quality: chosenQuality, bookmark: bookmark
                    ) { done, total in
                        Task { @MainActor in progress = total == 0 ? 0 : Double(done) / Double(total) }
                    }
                }.value
                let record = PDFRecord(
                    id: UUID().uuidString, name: result.fileName, folderBookmark: bookmark, location: location,
                    createdAt: Date(), pageCount: pages.count, sizeBytes: result.sizeBytes
                )
                library.add(record, thumbnailFrom: thumb)
                shareURL = try? library.temporaryCopy(of: record)
                saved = record
            } catch {
                errorText = bookmark != nil ? "Check the save location and try again." : "Something went wrong while saving the PDF."
            }
            working = false
        }
    }
}

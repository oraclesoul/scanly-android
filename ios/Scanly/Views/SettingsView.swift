import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @State private var pickFolder = false
    @State private var errorText: String?

    var body: some View {
        Form {
            Section {
                HStack(spacing: 14) {
                    Image(systemName: "folder")
                        .font(.title2)
                        .foregroundStyle(Color.accentColor)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(settings.locationLabel)
                            .font(.headline)
                            .accessibilityIdentifier("locationLabel")
                        Text(settings.isCustom ? "Custom folder" : "Default folder")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Button("Choose folder") { pickFolder = true }
                    .accessibilityIdentifier("chooseFolder")
                Button("Reset to default") { settings.reset() }
                    .disabled(!settings.isCustom)
            } header: {
                Text("PDF save location")
            } footer: {
                Text("New PDFs are saved here. The default folder appears in the Files app under On My iPhone › Scanly. In the folder picker you can create a new folder.")
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $pickFolder, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result, !settings.setFolder(url: url) {
                errorText = "Couldn't use that folder."
            }
        }
        .alert("Scanly", isPresented: Binding(get: { errorText != nil }, set: { if !$0 { errorText = nil } })) {
            Button("OK", role: .cancel) { errorText = nil }
        } message: {
            Text(errorText ?? "")
        }
    }
}

import PhotosUI
import SwiftUI

struct CameraScanView: View {
    @EnvironmentObject private var repo: DocRepository
    let docId: String?
    let onFinished: (String?) -> Void

    @StateObject private var camera = CameraModel()
    @State private var currentDoc: String?
    @State private var captured = 0
    @State private var pending = 0
    @State private var pickerItems: [PhotosPickerItem] = []

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            switch camera.state {
            case .running:
                CameraPreview(session: camera.session).ignoresSafeArea()
            case .checking:
                ProgressView().tint(.white)
            case .denied:
                message("Camera access is needed to scan documents. You can allow it in Settings > Scanly, or import photos instead.")
            case .unavailable:
                message("No camera is available on this device. You can import photos instead.")
            }

            VStack {
                HStack {
                    circleButton("xmark", label: "Close") { finish() }
                    Spacer()
                    if camera.hasFlash {
                        circleButton(camera.flashOn ? "bolt.fill" : "bolt.slash", label: camera.flashOn ? "Flash on" : "Flash off") {
                            camera.flashOn.toggle()
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                Spacer()
                controls
            }
        }
        .statusBarHidden(true)
        .onAppear {
            currentDoc = docId
            camera.onPhoto = { image in handle(image) }
            camera.start()
        }
        .onDisappear { camera.stop() }
        .onChange(of: pickerItems) { items in
            guard !items.isEmpty else { return }
            Task { await importPhotos(items) }
        }
    }

    private var controls: some View {
        HStack {
            PhotosPicker(selection: $pickerItems, maxSelectionCount: 50, matching: .images) {
                Image(systemName: "photo.on.rectangle")
                    .font(.title2)
                    .foregroundStyle(.white)
                    .frame(width: 56, height: 56)
            }
            .accessibilityLabel("Import")
            .frame(maxWidth: .infinity, alignment: .leading)

            Button { camera.capture() } label: {
                Circle()
                    .fill(.white)
                    .frame(width: 66, height: 66)
                    .padding(5)
                    .overlay(Circle().stroke(.white, lineWidth: 4))
                    .opacity(camera.state == .running ? 1 : 0.35)
            }
            .disabled(camera.state != .running || camera.capturing)
            .accessibilityLabel("Take photo")

            HStack(spacing: 10) {
                if let id = currentDoc, let last = repo.doc(id)?.pages.last {
                    ThumbView(url: repo.thumbURL(id, last), fill: true)
                        .frame(width: 44, height: 44)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(.white, lineWidth: 2))
                }
                if pending > 0 {
                    ProgressView().tint(.white)
                } else if captured > 0 {
                    Button { finish() } label: {
                        Label("\(captured)", systemImage: "checkmark")
                            .font(.headline)
                            .padding(.horizontal, 14)
                            .frame(height: 40)
                            .background(Color.accentColor, in: Capsule())
                            .foregroundStyle(.white)
                    }
                    .accessibilityLabel("Done")
                }
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 16)
        .padding(.top, 12)
        .background(Color.black.opacity(0.35))
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .foregroundStyle(.white)
            .multilineTextAlignment(.center)
            .padding(32)
    }

    private func circleButton(_ icon: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.headline)
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(Color.black.opacity(0.4), in: Circle())
        }
        .accessibilityLabel(label)
    }

    private func ensureDoc() -> String {
        if let id = currentDoc { return id }
        let id = repo.createDoc().id
        currentDoc = id
        return id
    }

    private func handle(_ image: UIImage) {
        let id = ensureDoc()
        pending += 1
        Task {
            let ok = await repo.addPage(docId: id, image: image)
            pending -= 1
            if ok { captured += 1 }
        }
    }

    private func importPhotos(_ items: [PhotosPickerItem]) async {
        let id = ensureDoc()
        pending += 1
        for item in items {
            if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data),
               await repo.addPage(docId: id, image: image) {
                captured += 1
            }
        }
        pickerItems = []
        pending -= 1
    }

    private func finish() {
        guard pending == 0 else { return }
        onFinished(currentDoc)
    }
}

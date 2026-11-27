import SwiftUI
import UIKit

/// Small in-memory cache so list thumbnails don't hit the disk on every redraw.
final class ImageCache {
    static let shared = ImageCache()
    private let cache = NSCache<NSString, UIImage>()

    func image(for url: URL) -> UIImage? { cache.object(forKey: url.path as NSString) }

    func load(_ url: URL) async -> UIImage? {
        if let hit = image(for: url) { return hit }
        let img = await Task.detached(priority: .userInitiated) { UIImage(contentsOfFile: url.path) }.value
        if let img { cache.setObject(img, forKey: url.path as NSString) }
        return img
    }
}

struct ThumbView: View {
    let url: URL
    var fill = false
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color(.secondarySystemFill)
            if let image {
                if fill {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Image(uiImage: image).resizable().scaledToFit()
                }
            }
        }
        .clipped()
        .task(id: url) { image = await ImageCache.shared.load(url) }
    }
}

struct EmptyStateView: View {
    let icon: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 64))
                .foregroundStyle(Color.accentColor)
                .padding(.bottom, 8)
            Text(title).font(.headline)
            Text(message)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct CardRow<Leading: View, Trailing: View>: View {
    @ViewBuilder let leading: Leading
    let title: String
    let lines: [String]
    @ViewBuilder let trailing: Trailing

    var body: some View {
        HStack(spacing: 14) {
            leading
                .frame(width: 56, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline).lineLimit(1)
                ForEach(lines, id: \.self) { line in
                    Text(line).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            trailing
        }
        .padding(12)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16))
        .contentShape(RoundedRectangle(cornerRadius: 16))
    }
}

struct RenameState: Identifiable {
    let id = UUID()
    var text: String
    var onSave: (String) -> Void
}

func shortDate(_ date: Date) -> String {
    date.formatted(.dateTime.month(.abbreviated).day())
}

func fileSize(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}

func pageCountText(_ n: Int) -> String { "\(n) page\(n == 1 ? "" : "s")" }

import SwiftUI

/// 附件图片内存缓存（file_id → UIImage，进程内有效）
final class ImageCache {
    static let shared = ImageCache()
    private let cache = NSCache<NSString, UIImage>()

    func get(_ key: String) -> UIImage? { cache.object(forKey: key as NSString) }
    func set(_ key: String, _ image: UIImage) { cache.setObject(image, forKey: key as NSString) }
}

/// 带 Authorization 的远程图片加载器：GET /api/v1/files/{file_id} 拉回 Data → UIImage，
/// 命中 ImageCache 时直接返回（AsyncImage 无法带请求头，故不用）。
@MainActor
final class RemoteImageLoader: ObservableObject {
    @Published var image: UIImage?
    @Published var failed = false
    private var task: Task<Void, Never>?

    func load(server: String, token: String, fileId: String) {
        guard image == nil, !failed else { return }
        if let cached = ImageCache.shared.get(fileId) {
            image = cached
            return
        }
        guard task == nil else { return }
        task = Task {
            do {
                let data = try await APIClient.fetchFile(server: server, token: token, fileId: fileId)
                guard !Task.isCancelled else { return }
                if let img = UIImage(data: data) {
                    ImageCache.shared.set(fileId, img)
                    self.image = img
                } else {
                    self.failed = true
                }
            } catch {
                if !Task.isCancelled { self.failed = true }
            }
            self.task = nil
        }
    }

    func cancel() {
        task?.cancel()
        task = nil
    }
}

/// 消息气泡里的附件图片：加载中转圈，失败显示占位，成功限高显示
struct AuthedImage: View {
    let server: String
    let token: String
    let fileId: String
    var maxHeight: CGFloat = 220

    @StateObject private var loader = RemoteImageLoader()

    var body: some View {
        Group {
            if let img = loader.image {
                Image(uiImage: img)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: maxHeight)
                    .cornerRadius(12)
            } else if loader.failed {
                Label("图片加载失败", systemImage: "photo")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding(8)
            } else {
                ProgressView()
                    .frame(width: 72, height: 72)
            }
        }
        .onAppear { loader.load(server: server, token: token, fileId: fileId) }
        .onDisappear { loader.cancel() }
    }
}

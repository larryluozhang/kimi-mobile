import Foundation
import ZIPFoundation

/// 离线语音模型按需下载（v0.6.1 起模型不再打进 bundle）：
/// URLSessionDownloadTask 下载 zip 到 tmp → ZIPFoundation 解压到
/// Application Support/models/zipformer-bilingual/（SpeechOnnx.modelAvailable 检查该目录）。
@MainActor
final class ModelDownloadManager: NSObject, ObservableObject {
    /// 进度文本（nil = 空闲）；UI 直接显示
    @Published var progressText: String?
    @Published var isDownloading = false

    static let defaultModelURL = "https://github.com/larryluozhang/kimi-mobile/releases/download/v0.6.1-models/model-zipformer-bilingual.zip"
    private static let urlDefaultsKey = "voice_model_url"

    /// 模型下载地址（UserDefaults voice_model_url，可在设置页编辑）
    static var modelURL: String {
        get { UserDefaults.standard.string(forKey: urlDefaultsKey) ?? defaultModelURL }
        set { UserDefaults.standard.set(newValue, forKey: urlDefaultsKey) }
    }

    struct DownloadError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private var session: URLSession?
    private var continuation: CheckedContinuation<URL, Error>?

    /// 下载并安装模型；成功后 SpeechOnnx.modelAvailable == true
    func downloadAndInstall() async throws {
        guard !isDownloading else { return }
        let raw = Self.modelURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: raw), raw.hasPrefix("http://") || raw.hasPrefix("https://") else {
            throw DownloadError(message: "下载地址无效（需 http:// 或 https:// 开头）")
        }
        isDownloading = true
        progressText = "下载中…"
        defer {
            isDownloading = false
            progressText = nil
        }
        let zip = try await download(url)
        progressText = "解压中…"
        // 解压与文件搬移放后台线程（模型约 189MB）
        try await Task.detached(priority: .userInitiated) { try Self.unzip(zip) }.value
    }

    // MARK: - 下载（URLSessionDownloadTask → tmp zip）

    private func download(_ url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { cont in
            continuation = cont
            let s = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
            session = s
            s.downloadTask(with: url).resume()
        }
    }

    private func finishDownload(_ result: Result<URL, Error>) {
        session?.finishTasksAndInvalidate()
        session = nil
        guard let cont = continuation else { return }
        continuation = nil
        cont.resume(with: result)
    }

    // MARK: - 解压

    /// 解压 zip 到 Application Support/models/zipformer-bilingual/。
    /// zip 内可能是平铺的四个模型文件，也可能包一层目录；自动定位含 tokens.txt 的目录。
    static func unzip(_ zip: URL) throws {
        let fm = FileManager.default
        let staging = fm.temporaryDirectory
            .appendingPathComponent("model-unzip-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        defer {
            try? fm.removeItem(at: staging)
            try? fm.removeItem(at: zip)
        }
        try fm.unzipItem(at: zip, to: staging)

        var src = staging
        if !fm.fileExists(atPath: staging.appendingPathComponent(SpeechOnnx.tokensFile).path) {
            // 平铺没找到，找含 tokens.txt 的子目录（zip 包了一层目录的情况）
            for entry in (try? fm.contentsOfDirectory(atPath: staging.path)) ?? [] {
                let sub = staging.appendingPathComponent(entry, isDirectory: true)
                if fm.fileExists(atPath: sub.appendingPathComponent(SpeechOnnx.tokensFile).path) {
                    src = sub
                    break
                }
            }
        }
        guard fm.fileExists(atPath: src.appendingPathComponent(SpeechOnnx.tokensFile).path) else {
            throw DownloadError(message: "压缩包内未找到模型文件（tokens.txt 缺失）")
        }

        let dest = SpeechOnnx.modelDir
        try fm.createDirectory(at: SpeechOnnx.modelsBaseURL, withIntermediateDirectories: true)
        if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
        try fm.moveItem(at: src, to: dest)
    }
}

// MARK: - URLSessionDownloadDelegate

extension ModelDownloadManager: URLSessionDownloadDelegate {
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didWriteData bytesWritten: Int64,
                                totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let mb = String(format: "%.1f", Double(totalBytesWritten) / 1_000_000)
        let text: String
        if totalBytesExpectedToWrite > 0 {
            let pct = Int(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) * 100)
            text = "下载中… \(mb) MB（\(pct)%）"
        } else {
            text = "下载中… \(mb) MB"
        }
        Task { @MainActor in self.progressText = text }
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didFinishDownloadingTo location: URL) {
        // location 在 delegate 返回后即被清理，立即搬到自己的 tmp 路径
        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("model-\(UUID().uuidString).zip")
        do {
            try FileManager.default.moveItem(at: location, to: dest)
            Task { @MainActor in self.finishDownload(.success(dest)) }
        } catch {
            Task { @MainActor in self.finishDownload(.failure(error)) }
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask,
                                didCompleteWithError error: Error?) {
        // 成功时 didFinishDownloadingTo 已完成 continuation；这里只处理失败
        guard let error else { return }
        Task { @MainActor in self.finishDownload(.failure(error)) }
    }
}

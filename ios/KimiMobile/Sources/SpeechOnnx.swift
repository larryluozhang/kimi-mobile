import Foundation
import AVFoundation
import SherpaOnnx

/// 离线语音输入：sherpa-onnx 流式 zipformer 中英双语模型（int8），全程本地识别，无需网络与系统语音服务。
/// 接口对齐 SpeechInput：partialText / isRecording / lastError + requestPermissions / start / stop。
@MainActor
final class SpeechOnnx: ObservableObject {
    @Published var partialText = ""
    @Published var isRecording = false
    @Published var lastError: String?

    /// 模型按需下载，不再打进 bundle：运行时目录 Application Support/models/zipformer-bilingual/
    /// （设置页「下载离线模型」写入该目录，见 ModelDownloadManager）
    static let modelDirName = "zipformer-bilingual"

    /// 模型根目录：Application Support/models/
    static var modelsBaseURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("models", isDirectory: true)
    }

    /// 模型目录：Application Support/models/zipformer-bilingual/
    static var modelDir: URL {
        modelsBaseURL.appendingPathComponent(modelDirName, isDirectory: true)
    }

    private static let encoderFile = "encoder-epoch-99-avg-1.int8.onnx"
    private static let decoderFile = "decoder-epoch-99-avg-1.int8.onnx"
    private static let joinerFile = "joiner-epoch-99-avg-1.int8.onnx"
    /// 模型文件清单的判定锚点（ModelDownloadManager 解压定位也用）
    static let tokensFile = "tokens.txt"

    /// 模型文件是否已下载到运行时目录（四个文件齐全才算可用）
    static var modelAvailable: Bool { modelDirURL != nil }

    private static var modelDirURL: URL? {
        let dir = modelDir
        let fm = FileManager.default
        for f in [encoderFile, decoderFile, joinerFile, tokensFile] {
            guard fm.fileExists(atPath: dir.appendingPathComponent(f).path) else { return nil }
        }
        return dir
    }

    private var recognizer: SherpaOnnxRecognizer?
    private let audioEngine = AVAudioEngine()
    /// 端点检测切分后已确认的文本（音频线程写入，主线程只读快照）
    private var committedText = ""

    /// 申请麦克风权限（离线识别不需要系统语音识别权限）
    func requestPermissions() async -> Bool {
        await withCheckedContinuation { cont in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                cont.resume(returning: granted)
            }
        }
    }

    /// 确保识别器已加载（模型加载约数百毫秒，放后台线程）。成功返回 true。
    func prepare() async -> Bool {
        if recognizer != nil { return true }
        let loaded = await Task.detached(priority: .userInitiated) { Self.makeRecognizer() }.value
        guard let loaded else {
            lastError = Self.modelAvailable ? "离线模型加载失败" : "离线模型未安装"
            return false
        }
        recognizer = loaded
        return true
    }

    /// 从运行时目录构建识别器；模型缺失或初始化失败返回 nil
    private static func makeRecognizer() -> SherpaOnnxRecognizer? {
        guard let dir = modelDirURL else { return nil }
        let transducer = sherpaOnnxOnlineTransducerModelConfig(
            encoder: dir.appendingPathComponent(encoderFile).path,
            decoder: dir.appendingPathComponent(decoderFile).path,
            joiner: dir.appendingPathComponent(joinerFile).path
        )
        let modelConfig = sherpaOnnxOnlineModelConfig(
            tokens: dir.appendingPathComponent(tokensFile).path,
            transducer: transducer,
            numThreads: 2
        )
        var config = sherpaOnnxOnlineRecognizerConfig(
            featConfig: sherpaOnnxFeatureConfig(sampleRate: 16000, featureDim: 80),
            modelConfig: modelConfig,
            enableEndpoint: true,
            rule1MinTrailingSilence: 2.4,
            rule2MinTrailingSilence: 1.2,
            rule3MinUtteranceLength: 30
        )
        return SherpaOnnxRecognizer(config: &config)
    }

    /// 开始录音识别；调用前请先 await prepare()
    func start() {
        guard !isRecording else { return }
        lastError = nil
        partialText = ""
        committedText = ""
        guard let recognizer = recognizer else {
            lastError = Self.modelAvailable ? "离线模型未加载" : "离线模型未安装"
            return
        }

        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.record, mode: .measurement, options: .duckOthers)
            try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            lastError = "无法启动录音会话：\(error.localizedDescription)"
            return
        }

        let inputNode = audioEngine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        guard let targetFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32,
                                               sampleRate: 16000, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: inputFormat, to: targetFormat) else {
            lastError = "不支持的采集格式（\(inputFormat.sampleRate)Hz）"
            return
        }

        recognizer.reset()

        inputNode.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { [weak self] buffer, _ in
            self?.feed(buffer: buffer, converter: converter, targetFormat: targetFormat)
        }

        audioEngine.prepare()
        do {
            try audioEngine.start()
        } catch {
            lastError = "录音启动失败：\(error.localizedDescription)"
            inputNode.removeTap(onBus: 0)
            return
        }
        isRecording = true
    }

    func stop() {
        guard isRecording else { return }
        isRecording = false
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// 音频线程回调：重采样到 16kHz 单声道后流式喂入识别器
    nonisolated private func feed(buffer: AVAudioPCMBuffer,
                                  converter: AVAudioConverter,
                                  targetFormat: AVAudioFormat) {
        let ratio = 16000.0 / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 256
        guard let out = AVAudioPCMBuffer(pcmFormat: targetFormat, frameCapacity: capacity) else { return }
        var consumed = false
        var convError: NSError?
        converter.convert(to: out, error: &convError) { _, status in
            if consumed {
                status.pointee = .noDataNow
                return nil
            }
            consumed = true
            status.pointee = .haveData
            return buffer
        }
        guard convError == nil, out.frameLength > 0,
              let channel = out.floatChannelData else { return }
        let samples = Array(UnsafeBufferPointer(start: channel[0], count: Int(out.frameLength)))

        Task { @MainActor [weak self] in
            guard let self = self, self.isRecording, let recognizer = self.recognizer else { return }
            recognizer.acceptWaveform(samples: samples, sampleRate: 16000)
            while recognizer.isReady() { recognizer.decode() }
            var text = self.committedText + recognizer.getResult().text
            if recognizer.isEndpoint() {
                recognizer.reset()
                self.committedText = text
            }
            self.partialText = text
        }
    }
}

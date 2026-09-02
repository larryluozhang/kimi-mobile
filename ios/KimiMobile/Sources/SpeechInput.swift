import Foundation
import Speech
import AVFoundation

/// 中文语音输入（zh-CN）：SFSpeechRecognizer + AVAudioEngine 流式识别，支持部分结果实时上屏。
@MainActor
final class SpeechInput: ObservableObject {
    @Published var partialText = ""
    @Published var isRecording = false
    @Published var lastError: String?

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "zh-CN"))
    private let audioEngine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    var isAvailable: Bool { recognizer?.isAvailable ?? false }

    /// 申请麦克风+语音识别权限；都授权返回 true
    func requestPermissions() async -> Bool {
        let speechOK: Bool = await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status == .authorized)
            }
        }
        guard speechOK else { return false }
        let micOK: Bool = await withCheckedContinuation { cont in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                cont.resume(returning: granted)
            }
        }
        return micOK
    }

    func start() {
        guard !isRecording, let recognizer = recognizer, recognizer.isAvailable else {
            lastError = "语音识别服务不可用"
            return
        }
        lastError = nil
        partialText = ""

        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.record, mode: .measurement, options: .duckOthers)
            try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            lastError = "无法启动录音会话：\(error.localizedDescription)"
            return
        }

        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        request = req

        let inputNode = audioEngine.inputNode
        let format = inputNode.outputFormat(forBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            self?.request?.append(buffer)
        }

        audioEngine.prepare()
        do {
            try audioEngine.start()
        } catch {
            lastError = "录音启动失败：\(error.localizedDescription)"
            inputNode.removeTap(onBus: 0)
            request = nil
            return
        }

        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                if let result = result {
                    self.partialText = result.bestTranscription.formattedString
                    if result.isFinal { self.stop() }
                }
                if let error = error {
                    // 识别结束/取消也会回调 error；录制中才提示
                    if self.isRecording {
                        let nsErr = error as NSError
                        if nsErr.domain != "kAFAssistantErrorDomain" || nsErr.code != 1110 {
                            self.lastError = "语音识别失败：\(error.localizedDescription)"
                        }
                    }
                    self.stop()
                }
            }
        }
        isRecording = true
    }

    func stop() {
        guard isRecording else { return }
        isRecording = false
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        request = nil
        task?.cancel()
        task = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

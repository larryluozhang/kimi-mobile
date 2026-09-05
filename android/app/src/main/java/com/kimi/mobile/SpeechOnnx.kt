package com.kimi.mobile

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * sherpa-onnx 离线流式语音识别封装（中英双语 zipformer，模型按需下载到 filesDir/models/zipformer-bilingual/）。
 *
 * 用法：
 *   val engine = SpeechOnnx(context)
 *   if (engine.init()) { engine.start(callback) ... engine.stop() } else 回退系统 SpeechRecognizer
 *   engine.release()  // onDestroy 调用
 *
 * 回调均在主线程触发。
 */
class SpeechOnnx(private val context: Context) {

    interface Callback {
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(msg: String)
    }

    companion object {
        private const val TAG = "SpeechOnnx"
        private const val MODEL_DIR = "models/zipformer-bilingual"
        private const val SAMPLE_RATE = 16000

        val MODEL_FILES = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
        )

        /** 模型运行时目录（filesDir/models/zipformer-bilingual） */
        fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR)

        /** 模型 4 件套是否已下载就位（未下载返回 false，调用方回退系统识别或提示去设置页下载） */
        fun isModelAvailable(context: Context): Boolean {
            val dir = modelDir(context)
            return MODEL_FILES.all { File(dir, it).isFile }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var callback: Callback? = null

    /** 同步初始化（加载模型，耗时数百 ms；调用方应在子线程调用）。失败返回 false，不抛异常。 */
    fun init(): Boolean {
        if (recognizer != null) return true
        if (!isModelAvailable(context)) {
            Log.w(TAG, "model files missing in ${modelDir(context)}")
            return false
        }
        val dir = modelDir(context).absolutePath
        return try {
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80,
                ),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$dir/encoder-epoch-99-avg-1.int8.onnx",
                        decoder = "$dir/decoder-epoch-99-avg-1.onnx",
                        joiner = "$dir/joiner-epoch-99-avg-1.int8.onnx",
                    ),
                    tokens = "$dir/tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "zipformer",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.2f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
            // assetManager 传 null：按绝对文件路径加载（模型在 filesDir，不在 assets）
            recognizer = OnlineRecognizer(null, config)
            Log.i(TAG, "recognizer initialized")
            true
        } catch (e: Throwable) {
            // 模型损坏 / native 库缺失（UnsatisfiedLinkError）等均回退系统识别
            Log.e(TAG, "init failed", e)
            recognizer = null
            false
        }
    }

    val isReady: Boolean get() = recognizer != null
    val isRecording: Boolean get() = recording

    /** 开始录音识别。需在已获得 RECORD_AUDIO 权限后调用；识别循环在子线程跑。 */
    fun start(cb: Callback): Boolean {
        val rec = recognizer ?: return false
        if (recording) return true
        callback = cb

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE) // 至少 1s 缓冲
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord create failed", e)
            cb.onError("录音设备初始化失败")
            return false
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            cb.onError("录音设备不可用")
            return false
        }
        audioRecord = ar
        recording = true
        ar.startRecording()

        recordThread = Thread {
            val stream = rec.createStream()
            val buf = ShortArray(SAMPLE_RATE / 10) // 100ms 一帧
            var lastPartial = ""
            try {
                while (recording) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n) { buf[it] / 32768.0f }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (rec.isReady(stream)) {
                        rec.decode(stream)
                    }
                    val text = rec.getResult(stream).text
                    if (rec.isEndpoint(stream)) {
                        if (text.isNotEmpty()) {
                            val finalText = text
                            handler.post { callback?.onResult(finalText) }
                        }
                        rec.reset(stream)
                        lastPartial = ""
                    } else if (text != lastPartial && text.isNotEmpty()) {
                        lastPartial = text
                        handler.post { callback?.onPartial(text) }
                    }
                }
                // 停止时冲刷剩余音频，产出最终结果
                stream.inputFinished()
                while (rec.isReady(stream)) rec.decode(stream)
                val tail = rec.getResult(stream).text
                if (tail.isNotEmpty()) {
                    handler.post { callback?.onResult(tail) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "record loop error", e)
                handler.post { callback?.onError("离线识别出错：${e.message}") }
            } finally {
                try {
                    ar.stop()
                } catch (e: Exception) {
                    // ignore
                }
                ar.release()
            }
        }.apply { start() }
        return true
    }

    /** 停止录音并冲刷结果（最终结果通过 onResult 回调） */
    fun stop() {
        recording = false
        recordThread?.join(2000)
        recordThread = null
        audioRecord = null
    }

    /** 释放识别器（Activity onDestroy） */
    fun release() {
        stop()
        recognizer?.release()
        recognizer = null
    }
}

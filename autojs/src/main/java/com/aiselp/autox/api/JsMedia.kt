package com.aiselp.autox.api

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.os.Build
import androidx.annotation.RequiresApi
import com.caoccao.javet.annotations.V8Function
import com.caoccao.javet.interop.V8Runtime
import com.caoccao.javet.values.reference.V8ValueObject
import com.stardust.autojs.AutoJs
import com.stardust.autojs.core.audio.AudioRecordManager
import com.stardust.autojs.core.audio.asr.AsrDenoiseManager
import com.stardust.autojs.core.audio.asr.AsrManager
import com.stardust.autojs.core.audio.asr.AsrVadManager
import com.stardust.autojs.core.image.capture.ScreenCaptureRequester

class JsMedia(private val context: Context) : NativeApi {
    override val moduleId: String = "media"
    private val mediaScanner = MediaScannerConnection(context, null)
    private val recycleSet = mutableSetOf<MediaPlayer>()

    private val screenCaptureRequester: ScreenCaptureRequester
        get() = try {
            AutoJs.instance.screenCaptureRequester
        } catch (e: Throwable) {
            throw IllegalStateException("AutoJs 尚未初始化，无法录音")
        }
    override fun install(v8Runtime: V8Runtime, global: V8ValueObject): NativeApi.BindingMode {
        return NativeApi.BindingMode.ObjectBind
    }

    override fun recycle(v8Runtime: V8Runtime, global: V8ValueObject) {
        mediaScanner.disconnect()
        recycleSet.forEach { it.release() }
        recycleSet.clear()
    }

    @V8Function
    fun scanFile(path: String) {
        mediaScanner.scanFile(path, null)
    }

    // 录音与内录。录音跑在进程级单例里，脚本结束后仍会继续，且可跨脚本停止/复用投影。
    @V8Function
    fun startRecord(optionsJson: String): String =
        AudioRecordManager.startJson(context, screenCaptureRequester, optionsJson)

    @V8Function
    fun stopRecord(): String = AudioRecordManager.stopJson()

    @V8Function
    fun getRecordState(): String = AudioRecordManager.getStateJson()

    @V8Function
    fun getRecordLevel(): Double = AudioRecordManager.getLevel()

    /** 返回 {base64, bytes, sampleRate, channels, seq, droppedBytes} 的 JSON 字符串 */
    @V8Function
    fun readRecordPcmJson(maxBytes: Int): String = AudioRecordManager.readPcmJson(maxBytes)

    @V8Function
    fun waitRecordSound(timeoutMs: Double, thresholdDb: Double): Boolean =
        AudioRecordManager.waitForSound(timeoutMs.toLong(), thresholdDb)

    @V8Function
    fun isSystemAudioActive(): Boolean = AudioRecordManager.isSystemAudioActive(context)

    @V8Function
    fun isRecording(): Boolean = AudioRecordManager.isRecording()

    @V8Function
    fun pauseRecord() {
        AudioRecordManager.pause()
    }

    @V8Function
    fun resumeRecord() {
        AudioRecordManager.resume()
    }

    @V8Function
    fun hasRecordPermission(): Boolean = AudioRecordManager.hasRecordPermission(context)

    @V8Function
    fun requestRecordPermission(): Boolean =
        AudioRecordManager.requestRecordPermission(context, 15000L)

    // ---- 语音转文字：接口已预留，引擎由 AsrManager 注册 ----

    @V8Function
    fun asrStatusJson(): String = AsrManager.statusJson()

    /** 注册内置 sherpa-onnx 引擎（zipformer/paraformer/sensevoice），模型路径在配置里给。 */
    @V8Function
    fun asrLoadBuiltinEngineJson(configJson: String): String = AsrManager.loadBuiltinEngine(configJson)

    @V8Function
    fun asrLoadPluginEngine(packageName: String, className: String, configJson: String): String =
        AsrManager.loadPluginEngine(context, packageName, className, configJson)

    @V8Function
    fun asrUnloadEngine() {
        AsrManager.unloadEngine()
    }

    /** PCM16LE 的 Base64（readRecordPcmJson 返回的 base64 可直接用）。 */
    @V8Function
    fun asrTranscribePcmJson(base64: String, sampleRate: Int, channels: Int): String =
        AsrManager.transcribePcmJson(base64, sampleRate, channels)

    /** WAV 自动识别采样率/声道；裸 PCM 需传 sampleRate。 */
    @V8Function
    fun asrTranscribeFileJson(path: String, sampleRate: Int, channels: Int): String =
        AsrManager.transcribeFileJson(path, sampleRate, channels)

    @V8Function
    fun asrStartStreamJson(): String = AsrManager.startStreamJson()

    @V8Function
    fun asrFeedStreamJson(id: Int, base64: String, sampleRate: Int, channels: Int): String =
        AsrManager.feedStreamJson(id, base64, sampleRate, channels)

    @V8Function
    fun asrFinishStreamJson(id: Int): String = AsrManager.finishStreamJson(id)

    @V8Function
    fun asrCancelStream(id: Int): Boolean = AsrManager.cancelStream(id)

    // ---- VAD 切段（Silero）：整段文件切 或 流式逐块喂、按段吐 ----

    @V8Function
    fun asrVadSegmentFileJson(path: String, configJson: String): String =
        AsrVadManager.segmentFileJson(path, configJson)

    @V8Function
    fun asrVadStartStreamJson(configJson: String): String = AsrVadManager.startJson(configJson)

    @V8Function
    fun asrVadFeedJson(id: Int, base64: String, sampleRate: Int, channels: Int): String =
        AsrVadManager.feedJson(id, base64, sampleRate, channels)

    @V8Function
    fun asrVadFinishJson(id: Int): String = AsrVadManager.finishJson(id)

    @V8Function
    fun asrVadCancel(id: Int): Boolean = AsrVadManager.cancel(id)

    // ---- GTCRN 语音增强：文件 / PCM / 流式 ----

    @V8Function
    fun asrDenoiseFileJson(path: String, configJson: String): String =
        AsrDenoiseManager.fileJson(path, configJson)

    @V8Function
    fun asrDenoisePcmJson(base64: String, sampleRate: Int, channels: Int, configJson: String): String =
        AsrDenoiseManager.pcmJson(base64, sampleRate, channels, configJson)

    @V8Function
    fun asrDenoiseStartJson(configJson: String): String = AsrDenoiseManager.startJson(configJson)

    @V8Function
    fun asrDenoiseFeedJson(id: Int, base64: String, sampleRate: Int, channels: Int): String =
        AsrDenoiseManager.feedJson(id, base64, sampleRate, channels)

    @V8Function
    fun asrDenoiseFinishJson(id: Int): String = AsrDenoiseManager.finishJson(id)

    @V8Function
    fun asrDenoiseCancel(id: Int): Boolean = AsrDenoiseManager.cancel(id)

    @V8Function
    fun createMediaPlayer(): MediaPlayer {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            MediaPlayerWrapper(recycleSet, context)
        } else {
            MediaPlayerWrapper(recycleSet)
        }
    }

    class MediaPlayerWrapper : MediaPlayer {
        private val recycleSet: MutableSet<MediaPlayer>

        constructor(recycleSet: MutableSet<MediaPlayer>) : super() {
            this.recycleSet = recycleSet
            recycleSet.add(this)
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        constructor(recycleSet: MutableSet<MediaPlayer>, context: Context) : super(context) {
            this.recycleSet = recycleSet
            recycleSet.add(this)
        }

        override fun release() {
            super.release()
            recycleSet.remove(this)
        }
    }
}
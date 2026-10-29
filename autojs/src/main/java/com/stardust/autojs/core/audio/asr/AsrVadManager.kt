package com.stardust.autojs.core.audio.asr

import android.util.Base64
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Silero VAD 切段：文件整段切，或开流式会话逐块喂、按段吐出。
 * 「VAD 切段 + 非流式模型」是安卓上唯一能把离线模型做成准实时的形态。
 */
object AsrVadManager {
    private const val TAG = "AsrVadManager"
    private val lock = Any()
    private val nextId = AtomicInteger(1)
    private val sessions = LinkedHashMap<Int, VadSession>()

    data class VadSegment(
        val startMs: Long,
        val endMs: Long,
        val samples: ShortArray
    )

    private class VadSession(val vad: Vad, val sampleRate: Int, val includePcm: Boolean) {
        var finished = false
    }

    /** 文件整段切：返回 {sampleRate, segments:[{startMs,endMs,base64?}]} */
    @JvmStatic
    fun segmentFileJson(path: String, configJson: String?): String {
        val config = parseConfig(configJson)
        val lower = path.lowercase()
        if (listOf(".mp3", ".m4a", ".aac", ".amr").any { lower.endsWith(it) }) {
            throw IllegalArgumentException("VAD 只接受 WAV/PCM，压缩音频请先解码")
        }
        val file = File(resolvePath(path))
        if (!file.isFile) throw IllegalArgumentException("文件不存在: ${file.absolutePath}")
        val data = Pcm16.decode(file.readBytes(), 16000, 1)
        val samples = if (data.sampleRate == config.sampleRate && data.channels == 1) {
            data.samples
        } else {
            PcmResampler(config.sampleRate).process(data.samples, data.sampleRate, data.channels)
        }
        val segments = runVad(samples, config)
        return segmentsJson(config.sampleRate, segments, config.includePcm).toString()
    }

    /** 开流式会话，返回 {id, sampleRate} */
    @JvmStatic
    fun startJson(configJson: String?): String {
        val config = parseConfig(configJson)
        val session = VadSession(makeVad(config), config.sampleRate, config.includePcm)
        val id = synchronized(lock) {
            val v = nextId.getAndIncrement()
            sessions[v] = session
            v
        }
        return JSONObject().put("id", id).put("sampleRate", config.sampleRate).toString()
    }

    /** 喂一块 PCM，返回 {speech, segments:[…]}（segments 为本块内完成切出的段） */
    @JvmStatic
    fun feedJson(id: Int, base64: String, sampleRate: Int, channels: Int): String {
        val session = requireSession(id)
        synchronized(session) {
            check(!session.finished) { "VAD 会话已结束: $id" }
            val bytes = decodeBase64(base64)
            val pcm = PcmResampler(session.sampleRate).process(
                Pcm16.bytesToShorts(bytes),
                if (sampleRate > 0) sampleRate else session.sampleRate,
                if (channels > 0) channels else 1
            )
            val segments = collect(session) {
                session.vad.acceptWaveform(toFloats(pcm))
            }
            return feedJson(session, segments).toString()
        }
    }

    /** 收尾：flush 并吐出剩余段，会话随后被移除 */
    @JvmStatic
    fun finishJson(id: Int): String {
        val session = synchronized(lock) { sessions.remove(id) }
            ?: throw IllegalStateException("VAD 会话不存在或已结束: $id")
        synchronized(session) {
            val segments = collect(session) { session.vad.flush() }
            return feedJson(session, segments).toString()
        }
    }

    @JvmStatic
    fun cancel(id: Int): Boolean {
        val session = synchronized(lock) { sessions.remove(id) } ?: return false
        runCatching { session.vad.release() }
        return true
    }

    private fun parseConfig(configJson: String?): VadConfig {
        val json = runCatching { JSONObject(configJson ?: "{}") }
            .getOrElse { throw IllegalArgumentException("VAD 配置不是合法 JSON: ${it.message}", it) }
        val modelDir = json.optStringOrNull("modelDir")?.let { resolvePath(it) }
        val rawModel = json.optStringOrNull("model")
            ?: throw IllegalArgumentException("VAD 配置缺少 model（silero_vad.onnx 路径）")
        val modelPath = when {
            AsrAssets.isAssetPath(rawModel) -> AsrAssets.extract(rawModel)
            else -> run {
                val p = resolvePath(rawModel)
                if (File(p).isFile) p else modelDir?.let { d -> File(d, rawModel).takeIf { it.isFile }?.absolutePath } ?: p
            }
        }
        if (!File(modelPath).isFile) throw IllegalArgumentException("VAD 模型不存在: $modelPath")
        val sampleRate = json.optInt("sampleRate", 16000)
        return VadConfig(
            modelPath = modelPath,
            sampleRate = sampleRate,
            threshold = json.optDouble("threshold", 0.5).toFloat(),
            minSilenceDuration = json.optDouble("minSilenceDuration", 0.5).toFloat(),
            minSpeechDuration = json.optDouble("minSpeechDuration", 0.25).toFloat(),
            windowSize = json.optInt("windowSize", if (sampleRate == 16000) 512 else 256),
            maxSpeechDuration = json.optDouble("maxSpeechDuration", 20.0).toFloat(),
            numThreads = json.optInt("numThreads", 1),
            debug = json.optBoolean("debug", false),
            includePcm = json.optBoolean("includePcm", true),
        )
    }

    private data class VadConfig(
        val modelPath: String,
        val sampleRate: Int,
        val threshold: Float,
        val minSilenceDuration: Float,
        val minSpeechDuration: Float,
        val windowSize: Int,
        val maxSpeechDuration: Float,
        val numThreads: Int,
        val debug: Boolean,
        val includePcm: Boolean,
    )

    private fun makeVad(config: VadConfig): Vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = config.modelPath,
                threshold = config.threshold,
                minSilenceDuration = config.minSilenceDuration,
                minSpeechDuration = config.minSpeechDuration,
                windowSize = config.windowSize,
                maxSpeechDuration = config.maxSpeechDuration,
            ),
            sampleRate = config.sampleRate,
            numThreads = config.numThreads,
            debug = config.debug,
        )
    )

    private fun runVad(samples: ShortArray, config: VadConfig): List<VadSegment> {
        val vad = makeVad(config)
        try {
            val out = ArrayList<VadSegment>()
            collect(vad, config.sampleRate, out) { vad.acceptWaveform(toFloats(samples)) }
            collect(vad, config.sampleRate, out) { vad.flush() }
            return out
        } finally {
            runCatching { vad.release() }
        }
    }

    private inline fun collect(session: VadSession, block: () -> Unit): List<VadSegment> {
        block()
        val out = ArrayList<VadSegment>()
        drain(session.vad, session.sampleRate, out)
        return out
    }

    private fun collect(vad: Vad, sampleRate: Int, out: MutableList<VadSegment>, block: () -> Unit) {
        block()
        drain(vad, sampleRate, out)
    }

    private fun drain(vad: Vad, sampleRate: Int, out: MutableList<VadSegment>) {
        while (!vad.empty()) {
            val seg = vad.front()
            val shorts = toShorts(seg.samples)
            out.add(
                VadSegment(
                    startMs = seg.start * 1000L / sampleRate,
                    endMs = seg.start * 1000L / sampleRate + shorts.size * 1000L / sampleRate,
                    samples = shorts
                )
            )
            vad.pop()
        }
    }

    private fun feedJson(session: VadSession, segments: List<VadSegment>): JSONObject {
        val array = JSONArray()
        segments.forEach { seg ->
            val obj = JSONObject()
                .put("startMs", seg.startMs)
                .put("endMs", seg.endMs)
            if (session.includePcm) {
                obj.put("base64", Base64.encodeToString(Pcm16.shortsToBytes(seg.samples), Base64.NO_WRAP))
            }
            array.put(obj)
        }
        return JSONObject()
            .put("speech", session.vad.isSpeechDetected())
            .put("segments", array)
    }

    private fun segmentsJson(sampleRate: Int, segments: List<VadSegment>, includePcm: Boolean): JSONObject {
        val array = JSONArray()
        segments.forEach { seg ->
            val obj = JSONObject()
                .put("startMs", seg.startMs)
                .put("endMs", seg.endMs)
                .put("ms", seg.endMs - seg.startMs)
            if (includePcm) {
                obj.put("base64", Base64.encodeToString(Pcm16.shortsToBytes(seg.samples), Base64.NO_WRAP))
            }
            array.put(obj)
        }
        return JSONObject().put("sampleRate", sampleRate).put("segments", array)
    }

    private fun requireSession(id: Int): VadSession =
        synchronized(lock) { sessions[id] } ?: throw IllegalStateException("VAD 会话不存在或已结束: $id")

    private fun decodeBase64(base64: String): ByteArray = try {
        Base64.decode(base64, Base64.NO_WRAP)
    } catch (t: IllegalArgumentException) {
        throw IllegalArgumentException("PCM Base64 解码失败: ${t.message}", t)
    }
}

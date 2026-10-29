package com.stardust.autojs.core.audio.asr

import android.util.Base64
import com.k2fsa.sherpa.onnx.DenoisedAudio
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * GTCRN 语音增强（sherpa-onnx SpeechDenoiser 封装，模型路径由 JS 配置指定）。
 * 文件/PCM 走 OfflineSpeechDenoiser，实时流走 OnlineSpeechDenoiser（增量吐出）。
 * 输出为 16k 单声道 PCM16，可直接送 ASR 或落 WAV。
 */
object AsrDenoiseManager {
    private val lock = Any()
    private val nextId = AtomicInteger(1)
    private val sessions = LinkedHashMap<Int, DenoiseSession>()

    private class DenoiseSession(val denoiser: OnlineSpeechDenoiser) {
        var finished = false
    }

    /** 整文件增强：返回 {outPath, sampleRate, ms, samples}；outPath 缺省为 原名-denoised.wav */
    @JvmStatic
    fun fileJson(path: String, configJson: String?): String {
        val config = parseConfig(configJson)
        val lower = path.lowercase()
        if (listOf(".mp3", ".m4a", ".aac", ".amr").any { lower.endsWith(it) }) {
            throw IllegalArgumentException("降噪只接受 WAV/PCM，压缩音频请先解码")
        }
        val file = File(resolvePath(path))
        if (!file.isFile) throw IllegalArgumentException("文件不存在: ${file.absolutePath}")
        val data = Pcm16.decode(file.readBytes(), 16000, 1)
        val denoiser = makeOffline(config)
        try {
            val target = denoiser.sampleRate
            val samples = if (data.sampleRate == target && data.channels == 1) {
                data.samples
            } else {
                PcmResampler(target).process(data.samples, data.sampleRate, data.channels)
            }
            val denoised = denoiser.run(toFloats(samples), target)
            val outPath = config.outPath
                ?: File(file.parentFile, file.nameWithoutExtension + "-denoised.wav").absolutePath
            val ms = saveWav(outPath, denoised)
            return JSONObject()
                .put("outPath", outPath)
                .put("sampleRate", denoised.sampleRate)
                .put("ms", ms)
                .toString()
        } finally {
            runCatching { denoiser.release() }
        }
    }

    /** 整段 PCM 增强：base64 进、base64 出（16k 单声道 PCM16） */
    @JvmStatic
    fun pcmJson(base64: String, sampleRate: Int, channels: Int, configJson: String?): String {
        val config = parseConfig(configJson)
        val bytes = try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (t: IllegalArgumentException) {
            throw IllegalArgumentException("PCM Base64 解码失败: ${t.message}", t)
        }
        val denoiser = makeOffline(config)
        try {
            val target = denoiser.sampleRate
            val samples = PcmResampler(target).process(
                Pcm16.bytesToShorts(bytes),
                if (sampleRate > 0) sampleRate else target,
                if (channels > 0) channels else 1
            )
            val denoised = denoiser.run(toFloats(samples), target)
            return JSONObject()
                .put("base64", Base64.encodeToString(toPcmBytes(denoised), Base64.NO_WRAP))
                .put("sampleRate", denoised.sampleRate)
                .toString()
        } finally {
            runCatching { denoiser.release() }
        }
    }

    /** 开流式增强会话：{id, sampleRate, frameShiftSamples} */
    @JvmStatic
    fun startJson(configJson: String?): String {
        val config = parseConfig(configJson)
        val denoiser = OnlineSpeechDenoiser(
            config = OnlineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = config.modelPath),
                    numThreads = config.numThreads,
                    debug = config.debug,
                    provider = config.provider,
                )
            )
        )
        val id = synchronized(lock) {
            val v = nextId.getAndIncrement()
            sessions[v] = DenoiseSession(denoiser)
            v
        }
        return JSONObject()
            .put("id", id)
            .put("sampleRate", denoiser.sampleRate)
            .put("frameShiftSamples", denoiser.frameShiftInSamples)
            .toString()
    }

    /** 喂一块 PCM，返回本块增强后的 base64（采样率见会话返回值） */
    @JvmStatic
    fun feedJson(id: Int, base64: String, sampleRate: Int, channels: Int): String {
        val session = requireSession(id)
        synchronized(session) {
            check(!session.finished) { "降噪会话已结束: $id" }
            val bytes = try {
                Base64.decode(base64, Base64.NO_WRAP)
            } catch (t: IllegalArgumentException) {
                throw IllegalArgumentException("PCM Base64 解码失败: ${t.message}", t)
            }
            val target = session.denoiser.sampleRate
            val samples = PcmResampler(target).process(
                Pcm16.bytesToShorts(bytes),
                if (sampleRate > 0) sampleRate else target,
                if (channels > 0) channels else 1
            )
            val denoised = session.denoiser.run(toFloats(samples), target)
            return JSONObject()
                .put("base64", Base64.encodeToString(toPcmBytes(denoised), Base64.NO_WRAP))
                .put("sampleRate", denoised.sampleRate)
                .toString()
        }
    }

    /** 收尾：flush 剩余增强音频，会话随后被移除 */
    @JvmStatic
    fun finishJson(id: Int): String {
        val session = synchronized(lock) { sessions.remove(id) }
            ?: throw IllegalStateException("降噪会话不存在或已结束: $id")
        synchronized(session) {
            session.finished = true
            val denoised = session.denoiser.flush()
            val out = JSONObject()
                .put("base64", Base64.encodeToString(toPcmBytes(denoised), Base64.NO_WRAP))
                .put("sampleRate", denoised.sampleRate)
            runCatching { session.denoiser.release() }
            return out.toString()
        }
    }

    @JvmStatic
    fun cancel(id: Int): Boolean {
        val session = synchronized(lock) { sessions.remove(id) } ?: return false
        runCatching { session.denoiser.release() }
        return true
    }

    private data class DenoiseConfig(
        val modelPath: String,
        val numThreads: Int,
        val debug: Boolean,
        val provider: String,
        val outPath: String?,
    )

    private fun parseConfig(configJson: String?): DenoiseConfig {
        val json = runCatching { JSONObject(configJson ?: "{}") }
            .getOrElse { throw IllegalArgumentException("降噪配置不是合法 JSON: ${it.message}", it) }
        val modelDir = json.optStringOrNull("modelDir")?.let { resolvePath(it) }
        val rawModel = json.optStringOrNull("model")
            ?: throw IllegalArgumentException("降噪配置缺少 model（gtcrn onnx 路径）")
        val modelPath = when {
            AsrAssets.isAssetPath(rawModel) -> AsrAssets.extract(rawModel)
            else -> run {
                val p = resolvePath(rawModel)
                if (File(p).isFile) p else modelDir?.let { d -> File(d, rawModel).takeIf { it.isFile }?.absolutePath } ?: p
            }
        }
        if (!File(modelPath).isFile) throw IllegalArgumentException("降噪模型不存在: $modelPath")
        return DenoiseConfig(
            modelPath = modelPath,
            numThreads = json.optInt("numThreads", 1),
            debug = json.optBoolean("debug", false),
            provider = json.optStringOrNull("provider") ?: "cpu",
            outPath = json.optStringOrNull("outPath")?.let { resolvePath(it) },
        )
    }

    private fun makeOffline(config: DenoiseConfig): OfflineSpeechDenoiser = OfflineSpeechDenoiser(
        config = OfflineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = config.modelPath),
                numThreads = config.numThreads,
                debug = config.debug,
                provider = config.provider,
            )
        )
    )

    private fun toPcmBytes(audio: DenoisedAudio): ByteArray = Pcm16.shortsToBytes(toShorts(audio.samples))

    private fun saveWav(path: String, audio: DenoisedAudio): Long {
        val file = File(path)
        file.parentFile?.mkdirs()
        // 官方 DenoisedAudio.save()（内部写 WAV 头），不再手拼头
        check(audio.save(file.absolutePath)) { "降噪结果写入失败: $path" }
        return audio.samples.size * 1000L / audio.sampleRate
    }

    private fun requireSession(id: Int): DenoiseSession =
        synchronized(lock) { sessions[id] } ?: throw IllegalStateException("降噪会话不存在或已结束: $id")
}

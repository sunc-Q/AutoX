package com.stardust.autojs.core.audio.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONObject
import java.io.File

/**
 * sherpa-onnx 内置引擎（不打包模型，模型文件路径全部由 JS 配置指定）。
 *
 * type 决定模型族与形态：
 * - "zipformer"：流式 transducer（encoder/decoder/joiner 三件 + tokens）
 * - "paraformer"：流式 Paraformer（encoder/decoder 两件 + tokens）
 * - "paraformer-offline"：非流式 Paraformer（model + tokens）
 * - "sensevoice"：非流式 SenseVoice（model + tokens，自带 ITN）
 * - "auto"：有 joiner→zipformer，只有 encoder/decoder→paraformer，只有 model→paraformer-offline
 *
 * 所有路径支持绝对路径或相对 /sdcard 的相对路径；相对文件名可配 modelDir 作基准目录。
 * 该类在宿主 APK 内，Rhino 引擎里也可经 Packages.com.k2fsa.sherpa.onnx.* 直接调用底层方法。
 */
class SherpaAsrEngine : AsrEngine {

    private var online: OnlineRecognizer? = null
    private var offline: OfflineRecognizer? = null
    private var isParaformer = false

    override lateinit var info: AsrEngineInfo
        private set

    private lateinit var config: JSONObject

    override fun initialize(configJson: String?) {
        val json = runCatching { JSONObject(configJson ?: "{}") }
            .getOrElse { throw IllegalArgumentException("ASR 配置不是合法 JSON: ${it.message}", it) }
        config = json
        val modelDir = json.optStringOrNull("modelDir")?.let { resolvePath(it) }
        val type = json.optStringOrNull("type") ?: "auto"
        val sampleRate = json.optInt("sampleRate", 16000)
        val languages = json.optStringOrNull("languages")
            ?.split(',', '，')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("zh")

        fun path(name: String): String? {
            val raw = json.optStringOrNull(name) ?: return null
            // 裸文件名（不含 /）优先按 modelDir 拼；带 / 的才按绝对路径或 /sdcard 相对解析。
            // 不能先 resolvePath 再判 '/'——它会把相对名先拼到 sdcard 根，modelDir 就永远参与不上了。
            if (!raw.contains('/') && modelDir != null) return File(modelDir, raw).absolutePath
            return resolvePath(raw)
        }

        val encoder = path("encoder")
        val decoder = path("decoder")
        val joiner = path("joiner")
        val model = path("model")
        val tokens = path("tokens") ?: throw IllegalArgumentException("配置缺少 tokens 路径")

        val resolvedType = when (type) {
            "auto" -> when {
                joiner != null -> "zipformer"
                encoder != null && decoder != null -> "paraformer"
                model != null -> "paraformer-offline"
                else -> throw IllegalArgumentException(
                    "无法从配置推断模型族：请显式传 type（zipformer/paraformer/paraformer-offline/sensevoice）"
                )
            }
            else -> type
        }

        val numThreads = json.optInt("numThreads", 2)
        val debug = json.optBoolean("debug", false)
        val provider = json.optStringOrNull("provider") ?: "cpu"

        when (resolvedType) {
            "zipformer" -> {
                requireNotNull(encoder) { "zipformer 需要 encoder 路径" }
                requireNotNull(decoder) { "zipformer 需要 decoder 路径" }
                requireNotNull(joiner) { "zipformer 需要 joiner 路径" }
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder, decoder = decoder, joiner = joiner
                    ),
                    tokens = tokens,
                    numThreads = numThreads,
                    debug = debug,
                    provider = provider,
                    modelType = "zipformer",
                    modelingUnit = json.optStringOrNull("modelingUnit") ?: "cjkchar",
                    bpeVocab = json.optStringOrNull("bpeVocab") ?: "",
                )
                online = OnlineRecognizer(
                    config = OnlineRecognizerConfig(
                        featConfig = FeatureConfig(
                            sampleRate = sampleRate,
                            featureDim = json.optInt("featureDim", 80),
                            dither = json.optDouble("dither", 0.0).toFloat(),
                        ),
                        modelConfig = modelConfig,
                        endpointConfig = endpointConfig(json),
                        enableEndpoint = json.optBoolean("enableEndpoint", true),
                        decodingMethod = json.optStringOrNull("decodingMethod") ?: "modified_beam_search",
                        hotwordsFile = path("hotwords") ?: "",
                        hotwordsScore = json.optDouble("hotwordsScore", 1.5).toFloat(),
                        ruleFsts = path("ruleFsts") ?: "",
                        ruleFars = path("ruleFars") ?: "",
                        blankPenalty = json.optDouble("blankPenalty", 0.0).toFloat(),
                    )
                )
                info = AsrEngineInfo(
                    name = json.optStringOrNull("name") ?: "sherpa-zipformer-streaming",
                    sampleRate = sampleRate, streaming = true, offline = true, languages = languages
                )
            }
            "paraformer" -> {
                requireNotNull(encoder) { "流式 paraformer 需要 encoder 路径" }
                requireNotNull(decoder) { "流式 paraformer 需要 decoder 路径" }
                isParaformer = true
                online = OnlineRecognizer(
                    config = OnlineRecognizerConfig(
                        featConfig = FeatureConfig(
                            sampleRate = sampleRate,
                            featureDim = json.optInt("featureDim", 80),
                            dither = json.optDouble("dither", 0.0).toFloat(),
                        ),
                        modelConfig = OnlineModelConfig(
                            paraformer = OnlineParaformerModelConfig(
                                encoder = encoder, decoder = decoder
                            ),
                            tokens = tokens,
                            numThreads = numThreads,
                            debug = debug,
                            provider = provider,
                        ),
                        endpointConfig = endpointConfig(json),
                        enableEndpoint = json.optBoolean("enableEndpoint", true),
                        ruleFsts = path("ruleFsts") ?: "",
                        ruleFars = path("ruleFars") ?: "",
                    )
                )
                info = AsrEngineInfo(
                    name = json.optStringOrNull("name") ?: "sherpa-paraformer-streaming",
                    sampleRate = sampleRate, streaming = true, offline = true, languages = languages
                )
            }
            "paraformer-offline" -> {
                requireNotNull(model) { "离线 paraformer 需要 model 路径" }
                offline = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(
                            sampleRate = sampleRate,
                            featureDim = json.optInt("featureDim", 80),
                            dither = json.optDouble("dither", 0.0).toFloat(),
                        ),
                        modelConfig = OfflineModelConfig(
                            paraformer = OfflineParaformerModelConfig(model = model),
                            tokens = tokens,
                            numThreads = numThreads,
                            debug = debug,
                            provider = provider,
                        ),
                        ruleFsts = path("ruleFsts") ?: "",
                        ruleFars = path("ruleFars") ?: "",
                    )
                )
                info = AsrEngineInfo(
                    name = json.optStringOrNull("name") ?: "sherpa-paraformer-offline",
                    sampleRate = sampleRate, streaming = false, offline = true, languages = languages
                )
            }
            "sensevoice" -> {
                requireNotNull(model) { "sensevoice 需要 model 路径" }
                offline = OfflineRecognizer(
                    config = OfflineRecognizerConfig(
                        featConfig = FeatureConfig(
                            sampleRate = sampleRate,
                            featureDim = json.optInt("featureDim", 80),
                            dither = json.optDouble("dither", 0.0).toFloat(),
                        ),
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = model,
                                language = json.optStringOrNull("language") ?: "auto",
                                useInverseTextNormalization = json.optBoolean("useItn", true),
                            ),
                            tokens = tokens,
                            numThreads = numThreads,
                            debug = debug,
                            provider = provider,
                        ),
                        ruleFsts = path("ruleFsts") ?: "",
                        ruleFars = path("ruleFars") ?: "",
                    )
                )
                info = AsrEngineInfo(
                    name = json.optStringOrNull("name") ?: "sherpa-sensevoice-offline",
                    sampleRate = sampleRate, streaming = false, offline = true, languages = languages
                )
            }
            else -> throw IllegalArgumentException("未知 ASR type: $type（支持 zipformer/paraformer/paraformer-offline/sensevoice/auto）")
        }
    }

    override fun newStream(): AsrStream {
        val recognizer = online ?: throw IllegalStateException("当前引擎是非流式模型，流式请用 zipformer/paraformer 或 VAD 切段")
        // 流式 paraformer 在端点后需要补右侧上下文（官方 demo：MainActivity.kt "we need to manually
        // add some paddings"），否则最后一个词出不来；zipformer 不需要
        return SherpaOnlineStream(recognizer, info.sampleRate, isParaformer)
    }

    override fun transcribe(samples: ShortArray): AsrResult {
        val recognizer = offline
        if (recognizer != null) {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(toFloats(samples), info.sampleRate)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                return AsrResult(
                    text = result.text,
                    segments = if (result.text.isNotBlank()) {
                        listOf(AsrSegment(result.text, 0, samples.size * 1000L / info.sampleRate))
                    } else emptyList(),
                    isFinal = true
                )
            } finally {
                runCatching { stream.release() }
            }
        }
        val r = online ?: throw IllegalStateException("ASR 引擎未初始化")
        val stream = r.createStream()
        try {
            stream.acceptWaveform(toFloats(samples), info.sampleRate)
            // 官方 online-decode-files.py：inputFinished 前补尾部静音，最后一个词才有
            // 足够右侧上下文（paraformer 0.8s，其余流式 0.66s）
            val tailSec = if (isParaformer) 0.8f else 0.66f
            stream.acceptWaveform(FloatArray((tailSec * info.sampleRate).toInt()), info.sampleRate)
            stream.inputFinished()
            // 只能在 isReady 时 decode：帧耗尽后再调 decode 会触发 sherpa GetFrames 的
            // SHERPA_ONNX_EXIT(-1)，把整个 :script 进程直接退出（无 crash dump）
            while (r.isReady(stream)) r.decode(stream)
            val text = r.getResult(stream).text
            return AsrResult(
                text = text,
                segments = if (text.isNotBlank()) {
                    listOf(AsrSegment(text, 0, samples.size * 1000L / info.sampleRate))
                } else emptyList(),
                isFinal = true
            )
        } finally {
            runCatching { stream.release() }
        }
    }

    override fun release() {
        online?.let { runCatching { it.release() } }
        online = null
        offline?.let { runCatching { it.release() } }
        offline = null
    }

    private fun endpointConfig(json: JSONObject): EndpointConfig {
        fun rule(key: String): EndpointRule? {
            val obj = json.optJSONObject(key) ?: return null
            return EndpointRule(
                mustContainNonSilence = obj.optBoolean("mustContainNonSilence", true),
                minTrailingSilence = obj.optDouble("minTrailingSilence", 2.4).toFloat(),
                minUtteranceLength = obj.optDouble("minUtteranceLength", 0.0).toFloat(),
            )
        }
        val r1 = rule("rule1") ?: return EndpointConfig()
        return EndpointConfig(
            rule1 = r1,
            rule2 = rule("rule2") ?: EndpointRule(true, 1.2f, 0f),
            rule3 = rule("rule3") ?: EndpointRule(false, 0f, 20f),
        )
    }

    private class SherpaOnlineStream(
        private val recognizer: OnlineRecognizer,
        private val sampleRate: Int,
        private val isParaformer: Boolean
    ) : AsrStream {
        private val stream = recognizer.createStream()
        private var padded = false
        // 官方对齐：paraformer 端点/收尾 0.8s（MainActivity.kt），zipformer 收尾 0.66s
        // （online-decode-files.py），首部一律不补（官方只有 T-One CTC 特例补 0.3s）
        private val tailSeconds = if (isParaformer) 0.8f else 0.66f

        override fun accept(samples: ShortArray) {
            stream.acceptWaveform(toFloats(samples), sampleRate)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
        }

        override fun partialText(): String = recognizer.getResult(stream).text

        /** 端点判定；流式 paraformer 命中端点后先按官方 demo 补 0.8s 静音再解码，保证收尾词完整 */
        override fun isEndpoint(): Boolean {
            if (!recognizer.isEndpoint(stream)) return false
            if (isParaformer && !padded) {
                val tail = FloatArray((0.8 * sampleRate).toInt())
                stream.acceptWaveform(tail, sampleRate)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                padded = true
            }
            return true
        }

        override fun reset() {
            recognizer.reset(stream)
            padded = false
        }

        override fun finishText(): String {
            if (!padded) {
                val tail = FloatArray((tailSeconds * sampleRate).toInt())
                stream.acceptWaveform(tail, sampleRate)
            }
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).text
        }

        override fun release() {
            runCatching { stream.release() }
        }
    }
}

internal fun toFloats(samples: ShortArray): FloatArray {
    val out = FloatArray(samples.size)
    for (i in samples.indices) out[i] = samples[i] / 32768f
    return out
}

internal fun toShorts(samples: FloatArray): ShortArray {
    val out = ShortArray(samples.size)
    for (i in samples.indices) {
        val v = (samples[i] * 32768f).toInt()
        out[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
    return out
}

internal fun resolvePath(path: String): String {
    if (path.startsWith("/")) return path
    return File(android.os.Environment.getExternalStorageDirectory(), path).absolutePath
}

internal fun JSONObject.optStringOrNull(name: String): String? {
    if (!has(name) || isNull(name)) return null
    val v = optString(name, "")
    return v.ifEmpty { null }
}

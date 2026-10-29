package com.stardust.autojs.core.audio.asr

/**
 * ASR 引擎自述信息。[sampleRate] 是引擎要求的输入采样率，主流模型为 16000。
 * 录音侧可以是 44100/48000 或双声道，[AsrManager] 会先降混/重采样再送入引擎。
 */
data class AsrEngineInfo(
    val name: String,
    val sampleRate: Int = 16000,
    /** 是否支持 [AsrEngine.newStream] 流式识别 */
    val streaming: Boolean = true,
    /** 是否支持 [AsrEngine.transcribe] 整段识别 */
    val offline: Boolean = true,
    val languages: List<String> = emptyList()
)

data class AsrSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class AsrResult(
    val text: String,
    val segments: List<AsrSegment> = emptyList(),
    val isFinal: Boolean = true
)

/**
 * 流式识别句柄。输入固定为单声道 PCM16、采样率 [AsrEngineInfo.sampleRate]。
 *
 * 一次只处理一句话：[isEndpoint] 为 true 时调用方会取走 [partialText] 作为定稿段，
 * 然后调用 [reset] 开始下一句；整段结束时调用 [finishText] 收尾。
 */
interface AsrStream {
    fun accept(samples: ShortArray)

    /** 当前这句的实时假设文本，可能为空。 */
    fun partialText(): String

    /** 是否到达端点（一句话结束）。 */
    fun isEndpoint(): Boolean

    /** 端点后复位，开始识别下一句。 */
    fun reset()

    /** 收尾：返回当前句剩余文本并结束。 */
    fun finishText(): String

    fun release()
}

/**
 * ASR 引擎契约。实现可以编进 App，也可以放在插件 APK 里由
 * [AsrManager.loadPluginEngine] 加载（插件 classloader 的父加载器是 App，
 * 因此插件能直接实现本接口）。
 */
interface AsrEngine {
    val info: AsrEngineInfo

    /** 只调用一次；[configJson] 原样来自 JS，由引擎自行解释（模型路径/线程数/标点等）。 */
    fun initialize(configJson: String?)

    fun newStream(): AsrStream

    /** 整段转写；输入为单声道 PCM16、采样率 [AsrEngineInfo.sampleRate]。 */
    fun transcribe(samples: ShortArray): AsrResult

    fun release()
}

data class AsrSnapshot(
    val id: Int,
    /** 已定稿段 + 当前假设 */
    val text: String,
    val partial: String,
    val segments: List<AsrSegment>,
    val isFinal: Boolean,
    /** 已送入引擎的音频时长 */
    val fedMs: Long
)

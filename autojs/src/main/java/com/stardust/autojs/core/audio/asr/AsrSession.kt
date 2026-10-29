package com.stardust.autojs.core.audio.asr

/**
 * 一次流式识别会话：负责降混/重采样、按端点切句、累计定稿文本。
 * 纯 Kotlin，不依赖 Android，方便单测锁定契约。
 */
class AsrSession(
    val id: Int,
    private val engine: AsrEngine,
    private val stream: AsrStream
) {
    val sampleRate = engine.info.sampleRate
    private val resampler = PcmResampler(sampleRate)
    private val segments = ArrayList<AsrSegment>()

    private var fedSamples = 0L
    private var segmentStartMs = 0L
    private var partial = ""
    private var finished = false

    @Synchronized
    fun feed(samples: ShortArray, sampleRate: Int, channels: Int): AsrSnapshot {
        check(!finished) { "ASR 流已结束: $id" }
        val pcm = resampler.process(samples, sampleRate, channels)
        if (pcm.isNotEmpty()) {
            fedSamples += pcm.size
            stream.accept(pcm)
        }
        // 先判端点再取文本：流式 paraformer 命中端点时会补尾部 padding 再解码，
        // 端点后的 partialText 才是补完右侧上下文的定稿文本
        val endpoint = stream.isEndpoint()
        partial = stream.partialText()
        if (endpoint) {
            commit(partial, fedMs())
            stream.reset()
            partial = ""
            segmentStartMs = fedMs()
        }
        return snapshot(false)
    }

    @Synchronized
    fun finish(): AsrSnapshot {
        if (finished) return snapshot(true)
        val tail = stream.finishText()
        if (tail.isNotBlank()) {
            commit(tail, fedMs())
        }
        partial = ""
        finished = true
        runCatching { stream.release() }
        return snapshot(true)
    }

    @Synchronized
    fun cancel() {
        if (finished) return
        finished = true
        runCatching { stream.release() }
    }

    private fun commit(text: String, endMs: Long) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        segments.add(AsrSegment(trimmed, segmentStartMs, maxOf(endMs, segmentStartMs)))
        segmentStartMs = endMs
    }

    private fun fedMs(): Long = fedSamples * 1000L / sampleRate

    private fun snapshot(final: Boolean): AsrSnapshot {
        val text = buildString {
            segments.forEach { append(it.text) }
            append(partial)
        }
        return AsrSnapshot(id, text, partial, segments.toList(), final, fedMs())
    }
}

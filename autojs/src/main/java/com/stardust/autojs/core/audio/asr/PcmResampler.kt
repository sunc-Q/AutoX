package com.stardust.autojs.core.audio.asr

import kotlin.math.roundToInt

/**
 * 流式 PCM 重采样 + 降混：任意采样率/声道 → 单声道 [targetRate]（PCM16）。
 *
 * 逐块调用 [process] 时保留插值相位和上一块最后一个采样，块边界不会产生断点，
 * 分块处理与整段处理的结果逐采样一致。输入格式变化时自动复位相位。
 */
class PcmResampler(private val targetRate: Int) {

    init {
        require(targetRate > 0) { "targetRate 必须大于 0" }
    }

    private var sourceRate = 0
    private var channels = 1

    /** 相对本块起点的插值位置，可能落在 (-1, 0) */
    private var position = 0.0
    private var previous = 0f

    fun process(samples: ShortArray, sampleRate: Int, channels: Int): ShortArray {
        require(sampleRate > 0) { "sampleRate 必须大于 0" }
        require(channels > 0) { "channels 必须大于 0" }
        val mono = downmix(samples, channels)
        if (mono.isEmpty()) return ShortArray(0)
        if (sampleRate != sourceRate || channels != this.channels) {
            sourceRate = sampleRate
            this.channels = channels
            position = 0.0
            previous = 0f
        }
        if (sourceRate == targetRate) {
            previous = mono[mono.size - 1].toFloat()
            return mono
        }
        val step = sourceRate.toDouble() / targetRate
        val n = mono.size
        val out = ArrayList<Float>((n / step).toInt() + 2)
        var p = position
        while (p < n - 1) {
            val value = if (p < 0) {
                val q = (p + 1).toFloat()
                previous + (mono[0] - previous) * q
            } else {
                val i = p.toInt()
                val frac = (p - i).toFloat()
                val a = mono[i].toFloat()
                a + (mono[i + 1] - a) * frac
            }
            out.add(value)
            p += step
        }
        position = p - n
        previous = mono[n - 1].toFloat()
        return ShortArray(out.size) {
            out[it].roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun downmix(samples: ShortArray, channels: Int): ShortArray {
        if (channels == 1) return samples.copyOf()
        val frames = samples.size / channels
        if (frames == 0) return ShortArray(0)
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            val base = i * channels
            for (c in 0 until channels) {
                sum += samples[base + c].toInt()
            }
            out[i] = (sum / channels).toShort()
        }
        return out
    }
}

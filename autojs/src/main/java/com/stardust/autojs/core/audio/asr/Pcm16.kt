package com.stardust.autojs.core.audio.asr

/**
 * PCM16LE 字节与采样点互转，以及 WAV/裸 PCM 的解析。
 */
object Pcm16 {

    data class PcmData(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int
    )

    fun bytesToShorts(bytes: ByteArray): ShortArray {
        val count = bytes.size / 2
        val out = ShortArray(count)
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    fun shortsToBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * 解析音频字节：WAV（PCM16）自动识别采样率/声道；裸 PCM 使用调用方给的
     * [fallbackSampleRate]/[fallbackChannels]（采样率必须大于 0）。
     */
    fun decode(bytes: ByteArray, fallbackSampleRate: Int, fallbackChannels: Int): PcmData {
        val wav = parseWav(bytes)
        if (wav != null) {
            val data = bytes.copyOfRange(wav.dataOffset, wav.dataOffset + wav.dataSize)
            return PcmData(bytesToShorts(data), wav.sampleRate, wav.channels)
        }
        if (fallbackSampleRate <= 0) {
            throw IllegalArgumentException("裸 PCM 需要指定 sampleRate（WAV 文件可自动识别）")
        }
        return PcmData(bytesToShorts(bytes), fallbackSampleRate, if (fallbackChannels > 0) fallbackChannels else 1)
    }

    private data class WavInfo(
        val dataOffset: Int,
        val dataSize: Int,
        val sampleRate: Int,
        val channels: Int
    )

    private fun parseWav(bytes: ByteArray): WavInfo? {
        if (bytes.size < 44) return null
        if (!tagEquals(bytes, 0, "RIFF") || !tagEquals(bytes, 8, "WAVE")) return null
        var offset = 12
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = leInt(bytes, offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    if (size < 16 || body + 16 > bytes.size) return null
                    format = leShort(bytes, body)
                    channels = leShort(bytes, body + 2)
                    sampleRate = leInt(bytes, body + 4)
                    bits = leShort(bytes, body + 14)
                }
                "data" -> {
                    dataOffset = body
                    dataSize = if (size < 0) {
                        bytes.size - body
                    } else {
                        minOf(size, bytes.size - body)
                    }
                }
            }
            if (size < 0) break
            val next = body.toLong() + size + (size and 1)
            if (next <= offset || next > Int.MAX_VALUE) break
            offset = next.toInt()
        }
        if (dataOffset < 0 || sampleRate <= 0 || channels <= 0) return null
        if (format != FORMAT_PCM && format != FORMAT_EXTENSIBLE) {
            throw IllegalArgumentException("只支持 PCM WAV（当前 format=$format）")
        }
        if (bits != 16) {
            throw IllegalArgumentException("只支持 16 位 PCM（当前 ${bits}bit）")
        }
        return WavInfo(dataOffset, dataSize, sampleRate, channels)
    }

    private fun tagEquals(bytes: ByteArray, offset: Int, tag: String): Boolean {
        if (offset + tag.length > bytes.size) return false
        for (i in tag.indices) {
            if (bytes[offset + i] != tag[i].code.toByte()) return false
        }
        return true
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private const val FORMAT_PCM = 1
    private const val FORMAT_EXTENSIBLE = 0xFFFE
}

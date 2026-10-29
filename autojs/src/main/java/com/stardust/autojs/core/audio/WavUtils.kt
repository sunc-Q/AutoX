package com.stardust.autojs.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtils {
    const val HEADER_SIZE = 44
    private const val MAX_DATA_SIZE = 0xFFFFFFFFL - 36

    fun buildHeader(
        dataSize: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val data = dataSize.coerceIn(0, MAX_DATA_SIZE)
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val buf = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt((36 + data).toInt())
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(data.toInt())
        return buf.array()
    }

    /**
     * 只改写文件开头的 44 字节头，不触碰后面的 PCM 数据。
     * 录音开始时会先写一份占位头，因此这里 seek(0) 是安全的。
     */
    fun writeHeader(
        file: File,
        dataSize: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int = 16
    ) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(buildHeader(dataSize, sampleRate, channels, bitsPerSample))
        }
    }
}

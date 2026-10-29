package com.stardust.autojs.core.audio

/**
 * 有界 PCM 环形缓冲：录音线程写，脚本线程按需拉。
 *
 * 消费端跟不上时丢弃最旧的数据并计数，绝不阻塞录音线程。
 */
class PcmRingBuffer(capacityBytes: Int) {
    private val buffer = ByteArray(capacityBytes.coerceAtLeast(MIN_CAPACITY))
    private val lock = Any()

    private var readPos = 0
    private var writePos = 0
    private var size = 0
    private var totalWritten = 0L
    private var dropped = 0L

    fun write(src: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            totalWritten += length
            if (length >= buffer.size) {
                val start = offset + length - buffer.size
                System.arraycopy(src, start, buffer, 0, buffer.size)
                dropped += size + (length - buffer.size)
                readPos = 0
                writePos = 0
                size = buffer.size
                return
            }
            val overflow = size + length - buffer.size
            if (overflow > 0) {
                readPos = (readPos + overflow) % buffer.size
                size -= overflow
                dropped += overflow
            }
            val tail = buffer.size - writePos
            if (length <= tail) {
                System.arraycopy(src, offset, buffer, writePos, length)
                writePos = (writePos + length) % buffer.size
            } else {
                System.arraycopy(src, offset, buffer, writePos, tail)
                System.arraycopy(src, offset + tail, buffer, 0, length - tail)
                writePos = length - tail
            }
            size += length
        }
    }

    /** 取走最多 [maxBytes] 字节，没有数据返回 null。 */
    fun read(maxBytes: Int): ByteArray? {
        if (maxBytes <= 0) return null
        synchronized(lock) {
            if (size == 0) return null
            val count = minOf(maxBytes, size)
            val out = ByteArray(count)
            val first = minOf(count, buffer.size - readPos)
            System.arraycopy(buffer, readPos, out, 0, first)
            if (count > first) {
                System.arraycopy(buffer, 0, out, first, count - first)
            }
            readPos = (readPos + count) % buffer.size
            size -= count
            return out
        }
    }

    fun bufferedBytes(): Int = synchronized(lock) { size }

    fun totalWritten(): Long = synchronized(lock) { totalWritten }

    fun droppedBytes(): Long = synchronized(lock) { dropped }

    companion object {
        private const val MIN_CAPACITY = 64 * 1024
    }
}

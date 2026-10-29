package com.stardust.autojs.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRingBufferTest {

    @Test
    fun `read returns null when empty`() {
        val ring = PcmRingBuffer(64 * 1024)
        assertNull(ring.read(1024))
        assertEquals(0, ring.bufferedBytes())
        assertEquals(0L, ring.totalWritten())
    }

    @Test
    fun `write then read keeps byte order`() {
        val ring = PcmRingBuffer(64 * 1024)
        val data = ByteArray(1000) { (it % 251).toByte() }
        ring.write(data, 0, data.size)

        assertEquals(1000, ring.bufferedBytes())
        assertEquals(1000L, ring.totalWritten())
        assertArrayEquals(data, ring.read(4096))
        assertEquals(0, ring.bufferedBytes())
    }

    @Test
    fun `partial read leaves the rest buffered`() {
        val ring = PcmRingBuffer(64 * 1024)
        val data = ByteArray(1000) { it.toByte() }
        ring.write(data, 0, data.size)

        assertArrayEquals(data.copyOfRange(0, 400), ring.read(400))
        assertEquals(600, ring.bufferedBytes())
        assertArrayEquals(data.copyOfRange(400, 1000), ring.read(600))
        assertEquals(0, ring.bufferedBytes())
    }

    @Test
    fun `wraparound read returns contiguous data`() {
        val ring = PcmRingBuffer(64 * 1024)
        val first = ByteArray(50_000) { 1 }
        ring.write(first, 0, first.size)
        assertEquals(50_000, ring.read(50_000)!!.size)

        // 写指针已接近尾部，这一批会绕回数组头部
        val second = ByteArray(30_000) { 2 }
        ring.write(second, 0, second.size)
        assertEquals(30_000, ring.bufferedBytes())

        val out = ring.read(30_000)!!
        assertEquals(30_000, out.size)
        assertTrue(out.all { it == 2.toByte() })
        assertEquals(80_000L, ring.totalWritten())
        assertEquals(0L, ring.droppedBytes())
    }

    @Test
    fun `overflow drops oldest bytes and counts them`() {
        val capacity = 64 * 1024
        val ring = PcmRingBuffer(capacity)

        val first = ByteArray(capacity) { 1 }
        ring.write(first, 0, first.size)
        val second = ByteArray(1000) { 2 }
        ring.write(second, 0, second.size)

        assertEquals(capacity, ring.bufferedBytes())
        assertEquals(1000L, ring.droppedBytes())

        val out = ring.read(capacity)!!
        // 最旧的 1000 字节已经被挤掉，后面才是第二批
        assertEquals(1.toByte(), out[0])
        assertTrue(out.copyOfRange(capacity - 1000, capacity).all { it == 2.toByte() })
    }

    @Test
    fun `chunk larger than capacity keeps only the tail`() {
        val capacity = 64 * 1024
        val ring = PcmRingBuffer(capacity)

        val big = ByteArray(capacity + 5000) { (it % 97).toByte() }
        ring.write(big, 0, big.size)

        assertEquals(capacity, ring.bufferedBytes())
        assertEquals(5000L, ring.droppedBytes())
        assertArrayEquals(big.copyOfRange(5000, big.size), ring.read(capacity))
        assertEquals(big.size.toLong(), ring.totalWritten())
    }

    @Test
    fun `dropped bytes accumulate across writes`() {
        val capacity = 64 * 1024
        val ring = PcmRingBuffer(capacity)
        ring.write(ByteArray(capacity) { 0 }, 0, capacity)
        repeat(3) {
            ring.write(ByteArray(256) { 1 }, 0, 256)
        }
        assertEquals(768L, ring.droppedBytes())
        assertEquals(capacity, ring.bufferedBytes())
    }
}

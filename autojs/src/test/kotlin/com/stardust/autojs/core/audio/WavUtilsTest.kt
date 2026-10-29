package com.stardust.autojs.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class WavUtilsTest {

    @Test
    fun `mono 16k header has correct sizes`() {
        val dataSize = 32_000L
        val header = WavUtils.buildHeader(dataSize, 16_000, 1, 16)

        assertEquals(WavUtils.HEADER_SIZE, header.size)
        assertEquals("RIFF", ascii(header, 0, 4))
        assertEquals(36 + dataSize, intLe(header, 4).toLong())
        assertEquals("WAVE", ascii(header, 8, 4))
        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals(16, intLe(header, 16))
        assertEquals(1, shortLe(header, 20))
        assertEquals(1, shortLe(header, 22))
        assertEquals(16_000, intLe(header, 24))
        assertEquals(32_000, intLe(header, 28))
        assertEquals(2, shortLe(header, 32))
        assertEquals(16, shortLe(header, 34))
        assertEquals("data", ascii(header, 36, 4))
        assertEquals(dataSize, intLe(header, 40).toLong())
    }

    @Test
    fun `stereo 44k1 header has correct byte rate and block align`() {
        val dataSize = 176_400L
        val header = WavUtils.buildHeader(dataSize, 44_100, 2, 16)

        assertEquals(2, shortLe(header, 22))
        assertEquals(44_100, intLe(header, 24))
        assertEquals(176_400, intLe(header, 28))
        assertEquals(4, shortLe(header, 32))
        assertEquals(36 + dataSize, intLe(header, 4).toLong())
        assertEquals(dataSize, intLe(header, 40).toLong())
    }

    @Test
    fun `zero data size still produces valid header`() {
        val header = WavUtils.buildHeader(0, 16_000, 1, 16)
        assertEquals(36, intLe(header, 4))
        assertEquals(0, intLe(header, 40))
    }

    @Test
    fun `oversized data is clamped to riff limit`() {
        val header = WavUtils.buildHeader(8L * 1024 * 1024 * 1024, 44_100, 2, 16)
        assertEquals(-1, intLe(header, 4))          // 0xFFFFFFFF
        assertEquals(-37, intLe(header, 40))        // 0xFFFFFFFF - 36
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun shortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}

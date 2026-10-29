package com.stardust.autojs.core.audio.asr

import com.stardust.autojs.core.audio.WavUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Pcm16Test {

    @Test
    fun `字节与采样点往返`() {
        val samples = shortArrayOf(-32768, -1, 0, 1, 32767)
        assertArrayEquals(samples, Pcm16.bytesToShorts(Pcm16.shortsToBytes(samples)))
    }

    @Test
    fun `奇数尾字节被丢弃`() {
        val out = Pcm16.bytesToShorts(byteArrayOf(0x34, 0x12, 0x7F))
        assertArrayEquals(shortArrayOf(0x1234), out)
    }

    @Test
    fun `解析WAV自动识别采样率与声道`() {
        val samples = shortArrayOf(1, -2, 3, -4)
        val data = Pcm16.shortsToBytes(samples)
        val header = WavUtils.buildHeader(data.size.toLong(), 16000, 1, 16)
        val pcm = Pcm16.decode(header + data, 0, 0)
        assertEquals(16000, pcm.sampleRate)
        assertEquals(1, pcm.channels)
        assertArrayEquals(samples, pcm.samples)
    }

    @Test
    fun `裸PCM必须给采样率`() {
        try {
            Pcm16.decode(Pcm16.shortsToBytes(shortArrayOf(1, 2)), 0, 1)
            fail("应当抛出异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sampleRate"))
        }
        val pcm = Pcm16.decode(Pcm16.shortsToBytes(shortArrayOf(1, 2)), 8000, 2)
        assertEquals(8000, pcm.sampleRate)
        assertEquals(2, pcm.channels)
    }

    @Test
    fun `8位WAV被拒绝`() {
        val data = byteArrayOf(1, 2, 3, 4)
        val header = WavUtils.buildHeader(data.size.toLong(), 16000, 1, 8)
        try {
            Pcm16.decode(header + data, 0, 0)
            fail("应当抛出异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("16"))
        }
    }
}

package com.stardust.autojs.core.audio.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmResamplerTest {

    @Test
    fun `同采样率单声道原样返回`() {
        val input = shortArrayOf(-32768, -1, 0, 1, 32767)
        assertArrayEquals(input, PcmResampler(16000).process(input, 16000, 1))
    }

    @Test
    fun `立体声降混为平均`() {
        val input = shortArrayOf(100, 300, -100, 100)
        assertArrayEquals(shortArrayOf(200, 0), PcmResampler(16000).process(input, 16000, 2))
    }

    @Test
    fun `44100降到16000长度与直流电平`() {
        val input = ShortArray(44100) { 1000 }
        val out = PcmResampler(16000).process(input, 44100, 1)
        assertEquals(16000, out.size)
        assertTrue(out.all { it.toInt() in 999..1001 })
    }

    @Test
    fun `48000降到16000长度精确`() {
        val out = PcmResampler(16000).process(ShortArray(48000), 48000, 1)
        assertEquals(16000, out.size)
    }

    @Test
    fun `分块与整段结果逐采样一致`() {
        val input = ShortArray(44100) { ((it * 37) % 2000 - 1000).toShort() }
        val whole = PcmResampler(16000).process(input, 44100, 1)
        val chunked = PcmResampler(16000)
        val first = chunked.process(input.copyOfRange(0, 22050), 44100, 1)
        val second = chunked.process(input.copyOfRange(22050, 44100), 44100, 1)
        val merged = first + second
        assertEquals(whole.size, merged.size)
        for (i in whole.indices) {
            assertTrue(
                "第 $i 个采样块边界偏差过大: ${whole[i]} vs ${merged[i]}",
                kotlin.math.abs(whole[i] - merged[i]) <= 1
            )
        }
    }

    @Test
    fun `空输入返回空数组`() {
        assertEquals(0, PcmResampler(16000).process(ShortArray(0), 44100, 2).size)
    }
}

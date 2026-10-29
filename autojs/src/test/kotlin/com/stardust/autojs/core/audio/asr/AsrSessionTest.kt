package com.stardust.autojs.core.audio.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AsrSessionTest {

    private class FakeEngine(rate: Int = 16000) : AsrEngine {
        override val info = AsrEngineInfo("fake", rate)
        override fun initialize(configJson: String?) = Unit
        override fun newStream(): AsrStream = FakeStream()
        override fun transcribe(samples: ShortArray) = AsrResult("offline:${samples.size}")
        override fun release() = Unit
    }

    private class FakeStream : AsrStream {
        var accepts = 0
        var received = 0
        var resets = 0
        var releases = 0
        var endpointAfter = Int.MAX_VALUE
        var last: ShortArray = ShortArray(0)

        override fun accept(samples: ShortArray) {
            accepts++
            received += samples.size
            last = samples
        }

        override fun partialText(): String = if (received == 0) "" else "句$received"

        override fun isEndpoint(): Boolean = accepts >= endpointAfter

        override fun reset() {
            resets++
            accepts = 0
            received = 0
        }

        override fun finishText(): String = if (received == 0) "" else "尾$received"

        override fun release() {
            releases++
        }
    }

    @Test
    fun `feed返回实时假设`() {
        val engine = FakeEngine()
        val session = AsrSession(1, engine, engine.newStream())
        val snap = session.feed(ShortArray(1600) { 100 }, 16000, 1)
        assertEquals("句1600", snap.partial)
        assertEquals("句1600", snap.text)
        assertTrue(snap.segments.isEmpty())
        assertEquals(100L, snap.fedMs)
        assertTrue(!snap.isFinal)
    }

    @Test
    fun `端点切段并复位`() {
        val engine = FakeEngine()
        val stream = FakeStream().also { it.endpointAfter = 2 }
        val session = AsrSession(2, engine, stream)

        val first = session.feed(ShortArray(1600) { 1 }, 16000, 1)
        assertTrue(first.segments.isEmpty())

        val second = session.feed(ShortArray(1600) { 1 }, 16000, 1)
        assertEquals(1, second.segments.size)
        assertEquals("句3200", second.segments[0].text)
        assertEquals(0L, second.segments[0].startMs)
        assertEquals(200L, second.segments[0].endMs)
        assertEquals("句3200", second.text)
        assertEquals("", second.partial)
        assertEquals(1, stream.resets)

        val third = session.feed(ShortArray(1600) { 1 }, 16000, 1)
        assertEquals("句3200句1600", third.text)
    }

    @Test
    fun `finish提交尾部文本并释放`() {
        val engine = FakeEngine()
        val stream = FakeStream()
        val session = AsrSession(3, engine, stream)
        session.feed(ShortArray(800) { 1 }, 16000, 1)

        val final = session.finish()
        assertTrue(final.isFinal)
        assertEquals(1, final.segments.size)
        assertEquals("尾800", final.segments[0].text)
        assertEquals(50L, final.segments[0].endMs)
        assertEquals(1, stream.releases)

        assertTrue(session.finish().isFinal)
        assertEquals(1, stream.releases)
    }

    @Test
    fun `重采样后送引擎`() {
        val engine = FakeEngine(16000)
        val stream = FakeStream()
        val session = AsrSession(4, engine, stream)
        val stereo = ShortArray(44100 * 2) { 1000 }

        val snap = session.feed(stereo, 44100, 2)
        assertEquals(16000, stream.received)
        assertEquals(1000L, snap.fedMs)
        assertTrue(stream.last.all { it.toInt() == 1000 })
    }

    @Test
    fun `cancel只释放一次且之后拒绝feed`() {
        val engine = FakeEngine()
        val stream = FakeStream()
        val session = AsrSession(5, engine, stream)

        session.cancel()
        assertEquals(1, stream.releases)

        assertTrue(session.finish().isFinal)
        assertEquals(1, stream.releases)

        try {
            session.feed(ShortArray(1), 16000, 1)
            fail("结束后继续 feed 应抛异常")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("已结束"))
        }
    }
}

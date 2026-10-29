package com.stardust.autojs.core.audio

import android.util.Log
import com.zlw.main.recorderlib.recorder.mp3.Mp3Encoder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 把 16bit PCM（小端、交错）编码成 MP3 的后台线程。
 *
 * 相比上游 ZlwAudioRecorder 的 Mp3EncodeThread：
 * - 队列有界（默认 32 块），满了丢块并计数，不会无限吃内存
 * - 立体声先解交错再送 LAME（上游把 interleaved 长度当每声道样本数，越界读）
 * - 编码错误会被记录并在 stop 时返回给脚本
 */
class Mp3EncoderThread(
    private val file: File,
    sampleRate: Int,
    private val channels: Int,
    bitRateKbps: Int,
    maxSamplesPerChannel: Int,
    queueCapacity: Int = 32
) : Thread("AutoX-Mp3Encoder") {

    private val queue = ArrayBlockingQueue<ByteArray>(queueCapacity)
    private val mp3Buffer = ByteArray((maxSamplesPerChannel * 1.25).toInt() + 7200)
    private val finished = AtomicBoolean(false)
    private val dropped = AtomicLong(0)

    @Volatile
    var error: String? = null
        private set

    init {
        // 在调用线程初始化 LAME，库缺失或参数不支持时能在 startRecord 阶段直接暴露
        Mp3Encoder.init(sampleRate, channels, sampleRate, bitRateKbps, 7)
    }

    /** 入队一块 PCM（会被拷贝），队列满则丢块并计数。 */
    fun offer(pcm: ByteArray, length: Int) {
        if (length <= 0) return
        val copy = pcm.copyOf(length)
        if (!queue.offer(copy)) {
            dropped.addAndGet(length.toLong())
        }
    }

    override fun run() {
        try {
            BufferedOutputStream(FileOutputStream(file), 64 * 1024).use { out ->
                while (true) {
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        if (finished.get()) break
                        continue
                    }
                    encodeChunk(chunk, out)
                    if (error != null) break
                }
                if (error == null) {
                    val flushed = Mp3Encoder.flush(mp3Buffer)
                    if (flushed < 0) {
                        error = "LAME flush 失败: $flushed"
                    } else if (flushed > 0) {
                        out.write(mp3Buffer, 0, flushed)
                    }
                }
            }
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            Log.e(TAG, "MP3 编码线程异常", t)
        } finally {
            try {
                Mp3Encoder.close()
            } catch (t: Throwable) {
                Log.w(TAG, "关闭 LAME 失败", t)
            }
        }
    }

    private fun encodeChunk(chunk: ByteArray, out: BufferedOutputStream) {
        val frameBytes = channels * 2
        val samples = chunk.size / frameBytes
        if (samples <= 0) return
        val left = ShortArray(samples)
        val right: ShortArray
        if (channels == 2) {
            right = ShortArray(samples)
            for (i in 0 until samples) {
                left[i] = readShortLe(chunk, i * 4)
                right[i] = readShortLe(chunk, i * 4 + 2)
            }
        } else {
            for (i in 0 until samples) {
                left[i] = readShortLe(chunk, i * 2)
            }
            right = left // LAME 单声道忽略右声道
        }
        val encoded = Mp3Encoder.encode(left, right, samples, mp3Buffer)
        if (encoded < 0) {
            error = "LAME 编码失败: $encoded"
            return
        }
        if (encoded > 0) {
            out.write(mp3Buffer, 0, encoded)
        }
    }

    /** 停止接收新数据，等待队列排空并 flush，返回被丢弃的字节数。 */
    fun finishAndWait(timeoutMs: Long = 10_000): Long {
        finished.set(true)
        try {
            join(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (isAlive) {
            interrupt()
            error = error ?: "MP3 编码线程在 ${timeoutMs}ms 内未结束"
        }
        return dropped.get()
    }

    private fun readShortLe(buf: ByteArray, offset: Int): Short =
        ((buf[offset].toInt() and 0xFF) or (buf[offset + 1].toInt() shl 8)).toShort()

    companion object {
        private const val TAG = "AutoX-Mp3Encoder"
    }
}

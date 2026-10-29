package com.stardust.autojs.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class RecordResult(
    val path: String,
    val source: String,
    val format: String,
    val sampleRate: Int,
    val channels: Int,
    val bytes: Long,
    val durationMs: Long,
    val peakDb: Double,
    val droppedBytes: Long,
    val error: String?
)

/**
 * 一次录音会话的采集引擎（麦克风 / 系统内录）。
 *
 * 相比上游 ZlwAudioRecorder 的 RecordHelper：
 * - 开始时就写入 44 字节 WAV 占位头，结束时只回填头部，不会覆盖 PCM
 * - read() 的返回值逐个校验，负值按错误码分类上报
 * - 暂停用 AudioRecord.stop()，恢复用 startRecording()，同一文件追加，不需要分段合并
 * - MP3 编码队列有界，丢块计数
 */
class AudioRecordEngine(
    private val config: AudioRecordConfig,
    private val projection: MediaProjection?,
    private val onAborted: (String) -> Unit
) {
    enum class State(val jsonName: String) { IDLE("idle"), RECORDING("recording"), PAUSED("paused"), STOPPED("stopped") }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    private var errorMessage: String? = null

    private var file: File? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var outputStream: OutputStream? = null
    private var mp3Thread: Mp3EncoderThread? = null
    private var bufferSizeBytes = 0
    private var startedAt = 0L
    private var stoppedAt = 0L
    private val recordedBytes = AtomicLong(0)
    private val stopRequested = AtomicBoolean(false)
    private var finalized = false
    private var droppedBytes = 0L
    private var lastHeaderUpdate = 0L
    private var tapBuffer: PcmRingBuffer? = null
    private var startedAtElapsed = 0L

    @Volatile
    private var lastSoundAt = 0L

    @Volatile
    private var soundMs = 0L

    @Volatile
    private var lastLevelDb = MIN_LEVEL_DB

    @Volatile
    private var lastLevelAt = 0L

    @Volatile
    private var peakDb = MIN_LEVEL_DB

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (state == State.RECORDING || state == State.PAUSED) {
                abort("屏幕录制授权已停止，内录随之结束")
            }
        }
    }

    @Synchronized
    fun start() {
        check(state == State.IDLE) { "录音引擎状态错误: $state" }
        if (config.source == AudioSourceType.SYSTEM) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw IllegalStateException("系统内录需要 Android 10 (API 29) 及以上系统，当前 API ${Build.VERSION.SDK_INT}")
            }
            if (projection == null) {
                throw IllegalStateException("缺少屏幕录制授权，无法内录")
            }
        }
        val target = if (config.format == AudioOutputFormat.NONE) null else File(config.path)
        target?.let {
            val parent = it.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("无法创建录音目录: ${parent.absolutePath}")
            }
        }
        file = target
        try {
            // 先建 AudioRecord：bufferSizeBytes 决定 MP3 编码缓冲区大小
            val record = createAudioRecord()
            audioRecord = record
            openOutput(target)
            registerProjectionCallback()
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("录音启动失败：设备可能被占用或缺少录音权限")
            }
        } catch (t: Throwable) {
            releaseResources()
            closeOutput()
            throw t
        }
        startedAt = System.currentTimeMillis()
        startedAtElapsed = SystemClock.elapsedRealtime()
        tapBuffer = if (config.tapBufferBytes > 0) PcmRingBuffer(config.tapBufferBytes) else null
        state = State.RECORDING
        recordThread = Thread({ recordLoop() }, "AutoX-AudioRecord").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun pause() {
        check(state == State.RECORDING) { "当前状态不能暂停: ${state.jsonName}" }
        state = State.PAUSED
        try {
            audioRecord?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "暂停时停止 AudioRecord 失败", t)
        }
    }

    @Synchronized
    fun resume() {
        check(state == State.PAUSED) { "当前状态不能恢复: ${state.jsonName}" }
        val record = audioRecord ?: throw IllegalStateException("录音对象已释放")
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("恢复录音失败：设备可能被占用")
        }
        state = State.RECORDING
    }

    @Synchronized
    fun stop(): RecordResult {
        if (!finalized) {
            stopRequested.set(true)
            state = State.STOPPED
            try {
                audioRecord?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "停止 AudioRecord 失败", t)
            }
            recordThread?.let {
                try {
                    it.join(3000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            if (recordThread?.isAlive == true) {
                Log.w(TAG, "录音线程 3s 内未退出，强制释放")
            }
            closeOutput()
            releaseResources()
            finalized = true
            stoppedAt = System.currentTimeMillis()
        }
        return buildResult()
    }

    fun recordedBytes(): Long = recordedBytes.get()

    fun durationMs(): Long = config.durationMsOf(recordedBytes.get())

    fun elapsedMs(): Long =
        if (startedAt == 0L) 0 else (if (stoppedAt > 0) stoppedAt else System.currentTimeMillis()) - startedAt

    fun error(): String? = errorMessage

    fun snapshotResult(): RecordResult = buildResult()

    /** 最近一个采集缓冲的 RMS 电平（dBFS）。 */
    fun levelDb(): Double = levelDbInternal()

    fun peakDb(): Double = peakDb

    /** 累计「有声音」的毫秒数（按 silenceThreshold 判定）。 */
    fun soundMs(): Long = soundMs

    /** 距离上一次检测到声音过了多久；从没检测到则从开始录音算起。 */
    fun silentMs(): Long {
        if (startedAtElapsed == 0L) return 0
        val base = if (lastSoundAt > 0) lastSoundAt else startedAtElapsed
        return (SystemClock.elapsedRealtime() - base).coerceAtLeast(0)
    }

    fun soundDetected(): Boolean =
        lastSoundAt > 0 && SystemClock.elapsedRealtime() - lastSoundAt < SOUND_HOLD_MS

    fun thresholdDb(): Double = config.silenceThresholdDb

    fun tapDroppedBytes(): Long = tapBuffer?.droppedBytes() ?: 0

    fun tapBufferedBytes(): Int = tapBuffer?.bufferedBytes() ?: 0

    /**
     * 从环形缓冲拉取最多 [maxBytes] 字节的 PCM16LE（同时给出 Base64 和 short[]）。
     * 返回 null 表示当前没有可读数据。
     */
    fun readPcm(maxBytes: Int): PcmChunk? {
        val ring = tapBuffer ?: return null
        val data = ring.read(if (maxBytes <= 0) DEFAULT_PCM_READ_BYTES else maxBytes) ?: return null
        return PcmChunk(
            base64 = Base64.encodeToString(data, Base64.NO_WRAP),
            samples = bytesToShorts(data),
            bytes = data.size,
            sampleRate = config.sampleRate,
            channels = config.channels,
            seq = ring.totalWritten(),
            droppedBytes = ring.droppedBytes()
        )
    }

    private fun levelDbInternal(): Double {
        if (state == State.IDLE || state == State.STOPPED) return MIN_LEVEL_DB
        if (SystemClock.elapsedRealtime() - lastLevelAt > 1000) return MIN_LEVEL_DB
        return lastLevelDb
    }

    private fun bytesToShorts(data: ByteArray): ShortArray {
        val count = data.size / 2
        val samples = ShortArray(count)
        for (i in 0 until count) {
            samples[i] = ((data[i * 2].toInt() and 0xFF) or (data[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return samples
    }

    private fun recordLoop() {
        val buffer = ByteArray(bufferSizeBytes)
        try {
            while (true) {
                when (state) {
                    State.STOPPED -> return
                    State.PAUSED -> {
                        Thread.sleep(20)
                        continue
                    }
                    else -> Unit
                }
                val record = audioRecord ?: return
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    if (state != State.RECORDING) continue
                    write(buffer, read)
                    updateLevel(buffer, read)
                    updateWavHeaderPeriodically()
                } else if (read == 0) {
                    if (state == State.RECORDING) Thread.sleep(5)
                } else {
                    if (state != State.RECORDING) continue
                    abort("读取音频失败: ${readErrorName(read)} ($read)")
                    return
                }
            }
        } catch (t: Throwable) {
            if (!stopRequested.get()) {
                Log.e(TAG, "录音线程异常", t)
                abort(t.message ?: t.toString())
            }
        }
    }

    private fun write(buffer: ByteArray, length: Int) {
        tapBuffer?.write(buffer, 0, length)
        when (config.format) {
            AudioOutputFormat.MP3 -> mp3Thread?.offer(buffer, length)
            else -> outputStream?.write(buffer, 0, length)
        }
        recordedBytes.addAndGet(length.toLong())
    }

    /**
     * 录音过程中周期性回填 WAV 头，进程被杀时文件也至少有一个近似正确的头。
     * 只改文件前 44 字节，不影响正在追加的 PCM。
     */
    private fun updateWavHeaderPeriodically() {
        if (config.format != AudioOutputFormat.WAV) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastHeaderUpdate < HEADER_UPDATE_INTERVAL_MS) return
        lastHeaderUpdate = now
        writeWavHeader(recordedBytes.get())
    }

    private fun writeWavHeader(pcmBytes: Long) {
        val target = file ?: return
        try {
            WavUtils.writeHeader(target, pcmBytes, config.sampleRate, config.channels, 16)
        } catch (t: Throwable) {
            Log.w(TAG, "回填 WAV 头失败", t)
        }
    }

    private fun updateLevel(buffer: ByteArray, length: Int) {
        var sum = 0.0
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort()).toInt()
            val abs = if (sample == Short.MIN_VALUE.toInt()) 32767 else Math.abs(sample)
            if (abs > peak) peak = abs
            sum += sample.toDouble() * sample
            i += 2
        }
        val samples = i / 2
        if (samples <= 0) return
        val now = SystemClock.elapsedRealtime()
        val rms = Math.sqrt(sum / samples)
        lastLevelDb = amplitudeToDb(rms)
        lastLevelAt = now
        if (lastLevelDb >= config.silenceThresholdDb) {
            lastSoundAt = now
            soundMs += length * 1000L / (config.sampleRate.toLong() * config.bytesPerFrame)
        }
        val peakDbNow = amplitudeToDb(peak.toDouble())
        if (peakDbNow > peakDb) peakDb = peakDbNow
    }

    private fun openOutput(target: File?) {
        val target = target ?: return
        when (config.format) {
            AudioOutputFormat.WAV -> {
                FileOutputStream(target).use { it.write(WavUtils.buildHeader(0, config.sampleRate, config.channels, 16)) }
                outputStream = BufferedOutputStream(FileOutputStream(target, true), 64 * 1024)
            }
            AudioOutputFormat.PCM -> {
                outputStream = BufferedOutputStream(FileOutputStream(target, false), 64 * 1024)
            }
            AudioOutputFormat.MP3 -> {
                if (!target.exists()) FileOutputStream(target).use { }
                val maxSamples = (maxOf(bufferSizeBytes, 4096) / config.bytesPerFrame)
                mp3Thread = Mp3EncoderThread(
                    target, config.sampleRate, config.channels, config.bitRateKbps, maxSamples
                ).apply {
                    isDaemon = true
                    start()
                }
            }
            AudioOutputFormat.NONE -> Unit
        }
    }

    private fun closeOutput() {
        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "关闭输出文件失败", t)
        }
        outputStream = null
        mp3Thread?.let { encoder ->
            droppedBytes = encoder.finishAndWait()
            encoder.error?.let { if (errorMessage == null) errorMessage = it }
            mp3Thread = null
        }
        val target = file ?: return
        if (config.format == AudioOutputFormat.WAV && target.length() >= WavUtils.HEADER_SIZE) {
            writeWavHeader(recordedBytes.get())
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val channelMask = if (config.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            throw IllegalArgumentException(
                "设备不支持该录音参数（采样率 ${config.sampleRate}Hz、${config.channels} 声道），getMinBufferSize=$minBuffer"
            )
        }
        bufferSizeBytes = maxOf(minBuffer * 2, 4096)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(config.sampleRate)
            .setChannelMask(channelMask)
            .build()
        val builder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
        if (config.source == AudioSourceType.SYSTEM) {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection!!).apply {
                config.usages.forEach { addMatchingUsage(it) }
            }.build()
            builder.setAudioPlaybackCaptureConfig(captureConfig)
        } else {
            builder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        val record = builder.build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException(
                "AudioRecord 初始化失败（STATE_UNINITIALIZED），可尝试更换 sampleRate/channels"
            )
        }
        return record
    }

    private fun registerProjectionCallback() {
        val p = projection ?: return
        if (config.source != AudioSourceType.SYSTEM) return
        try {
            p.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            Log.w(TAG, "注册 MediaProjection 回调失败", t)
        }
    }

    private fun releaseResources() {
        val p = projection
        if (p != null && config.source == AudioSourceType.SYSTEM) {
            try {
                p.unregisterCallback(projectionCallback)
            } catch (t: Throwable) {
                // 投影已停止时 unregister 会抛异常，忽略
            }
        }
        try {
            audioRecord?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "释放 AudioRecord 失败", t)
        }
        audioRecord = null
        recordThread = null
    }

    private fun abort(message: String) {
        if (errorMessage == null) errorMessage = message
        state = State.STOPPED
        onAborted(message)
    }

    private fun buildResult(): RecordResult {
        val target = file
        val bytes = target?.length() ?: 0
        return RecordResult(
            path = target?.absolutePath ?: config.path,
            source = config.source.jsonName,
            format = config.format.jsonName,
            sampleRate = config.sampleRate,
            channels = config.channels,
            bytes = bytes,
            durationMs = durationMs(),
            peakDb = roundDb(peakDb),
            droppedBytes = droppedBytes,
            error = errorMessage
        )
    }

    companion object {
        private const val TAG = "AutoX-AudioRecord"
        private const val HEADER_UPDATE_INTERVAL_MS = 5000L
        private const val SOUND_HOLD_MS = 1000L
        private const val DEFAULT_PCM_READ_BYTES = 64 * 1024
        const val MIN_LEVEL_DB = -120.0

        private fun amplitudeToDb(amplitude: Double): Double {
            if (amplitude <= 0.5) return MIN_LEVEL_DB
            val db = 20.0 * Math.log10(amplitude / 32768.0)
            return if (db < MIN_LEVEL_DB) MIN_LEVEL_DB else db
        }

        fun roundDb(db: Double): Double = Math.round(db * 10.0) / 10.0

        private fun readErrorName(code: Int): String = when (code) {
            AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
            AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
            AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
            AudioRecord.ERROR -> "ERROR"
            else -> "UNKNOWN"
        }
    }
}

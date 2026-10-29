package com.stardust.autojs.core.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.stardust.autojs.core.image.capture.ScreenCaptureRequester
import com.stardust.autojs.core.image.capture.ScreenCapturer
import com.stardust.autojs.core.permission.Permissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 进程级录音管理器。
 *
 * 所有脚本都跑在 @string/text_script_process_name 进程，所以这里的单例天然跨脚本：
 * 脚本 A 开始录音（内录时顺带申请一次屏幕录制授权）→ A 结束 → 脚本 B 仍能
 * 停止/查询这次录音，也能复用同一个 MediaProjection 直接开始新的内录，
 * 不需要再弹授权框。录音线程和前台服务都不依赖脚本运行时。
 */
object AudioRecordManager {
    private const val TAG = "AudioRecordManager"
    private const val SERVICE_START_TIMEOUT_MS = 5000L

    private val lock = Any()
    private val serviceSignal = AtomicReference<CompletableDeferred<Unit>?>(null)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var lastResult: RecordResult? = null

    @Volatile
    private var lastError: String? = null

    /** 保留最近一次会话的引擎，停止后仍能把缓冲区里剩下的 PCM 拉走 */
    @Volatile
    private var lastEngine: AudioRecordEngine? = null

    private class Session(
        val engine: AudioRecordEngine,
        val config: AudioRecordConfig,
        val projection: MediaProjection?
    )

    @JvmStatic
    fun startJson(context: Context, requester: ScreenCaptureRequester, optionsJson: String?): String {
        val config = AudioRecordConfig.parse(optionsJson)
        return start(context, requester, config).toJson().put("state", "recording").toString()
    }

    @JvmStatic
    fun start(context: Context, requester: ScreenCaptureRequester, config: AudioRecordConfig): RecordResult {
        synchronized(lock) {
            session?.let {
                throw IllegalStateException(
                    "已有录音正在进行：${it.config.path}（${it.engine.state.jsonName}），请先 stopRecord()"
                )
            }
            if (!hasRecordPermission(context)) {
                throw SecurityException("缺少录音权限（RECORD_AUDIO），请先调用 media.requestRecordPermission()")
            }
            val projection = when (config.source) {
                AudioSourceType.MIC -> null
                AudioSourceType.SYSTEM -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        throw IllegalStateException(
                            "系统内录需要 Android 10 (API 29) 及以上系统，当前 API ${Build.VERSION.SDK_INT}"
                        )
                    }
                    requester.getAliveMediaProjection() ?: runBlocking {
                        requester.ensureMediaProjection(context, ScreenCapturer.ORIENTATION_AUTO)
                    }
                }
            }
            val app = context.applicationContext ?: context
            appContext = app
            val ready = CompletableDeferred<Unit>()
            serviceSignal.set(ready)
            try {
                AudioRecordService.start(
                    app,
                    projection != null,
                    if (config.source == AudioSourceType.SYSTEM) "系统内录" else "麦克风录音"
                )
            } catch (t: Throwable) {
                serviceSignal.set(null)
                throw t
            }
            try {
                runBlocking { withTimeout(SERVICE_START_TIMEOUT_MS) { ready.await() } }
            } catch (t: Throwable) {
                serviceSignal.set(null)
                AudioRecordService.stop(app)
                throw IllegalStateException("录音前台服务启动失败: ${t.message}", t)
            }
            val engine = AudioRecordEngine(config, projection) { message -> handleAbort(message) }
            try {
                engine.start()
            } catch (t: Throwable) {
                AudioRecordService.stop(app)
                throw t
            }
            session = Session(engine, config, projection)
            lastError = null
            return engine.snapshotResult()
        }
    }

    @JvmStatic
    fun stopJson(): String {
        val result = stop()
        val obj = result?.toJson() ?: JSONObject()
        obj.put("state", "idle")
        if (result == null) {
            lastResult?.let { obj.put("last", it.toJson()) }
            lastError?.let { obj.put("error", it) }
        }
        return obj.toString()
    }

    @JvmStatic
    fun stop(): RecordResult? {
        synchronized(lock) {
            val active = session ?: return lastResult
            return stopSession(active, null)
        }
    }

    @JvmStatic
    fun pause() {
        synchronized(lock) {
            val active = session ?: throw IllegalStateException("当前没有正在进行的录音")
            active.engine.pause()
        }
    }

    @JvmStatic
    fun resume() {
        synchronized(lock) {
            val active = session ?: throw IllegalStateException("当前没有正在进行的录音")
            active.engine.resume()
        }
    }

    @JvmStatic
    fun isRecording(): Boolean = session != null

    @JvmStatic
    fun getLevel(): Double {
        val active = session ?: return AudioRecordEngine.MIN_LEVEL_DB
        return AudioRecordEngine.roundDb(active.engine.levelDb())
    }

    /**
     * 拉取 PCM 波形数据（PCM16LE，字段同时提供 Base64 和 short[]）。
     * 返回 null 表示当前没有可读数据；录音结束后仍可继续拉取缓冲区剩余部分。
     */
    @JvmStatic
    fun readPcm(maxBytes: Int): PcmChunk? {
        val engine = session?.engine ?: lastEngine ?: return null
        return engine.readPcm(maxBytes)
    }

    @JvmStatic
    fun readPcmJson(maxBytes: Int): String {
        val chunk = readPcm(maxBytes) ?: return JSONObject().put("bytes", 0).toString()
        val samples = JSONArray()
        chunk.samples.forEach { samples.put(it.toInt()) }
        return JSONObject()
            .put("base64", chunk.base64)
            .put("samples", samples)
            .put("bytes", chunk.bytes)
            .put("sampleRate", chunk.sampleRate)
            .put("channels", chunk.channels)
            .put("seq", chunk.seq)
            .put("droppedBytes", chunk.droppedBytes)
            .toString()
    }

    /**
     * 阻塞等待「有声音」，最多 [timeoutMs]。level 达到 [thresholdDb] 即返回 true。
     * 需要在脚本线程调用。
     */
    @JvmStatic
    fun waitForSound(timeoutMs: Long, thresholdDb: Double): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val active = session ?: return false
            if (active.engine.levelDb() >= thresholdDb) return true
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /** 系统当前是否有 App 在播放声音（AudioManager.isMusicActive）。 */
    @JvmStatic
    fun isSystemAudioActive(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            ?: return false
        return am.isMusicActive
    }

    @JvmStatic
    fun getStateJson(): String {
        synchronized(lock) {
            val active = session
            val obj = JSONObject()
            if (active != null) {
                obj.put("state", active.engine.state.jsonName)
                obj.put("path", if (active.config.path.isEmpty()) "" else File(active.config.path).absolutePath)
                obj.put("source", active.config.source.jsonName)
                obj.put("format", active.config.format.jsonName)
                obj.put("sampleRate", active.config.sampleRate)
                obj.put("channels", active.config.channels)
                obj.put("bytes", active.engine.recordedBytes())
                obj.put("durationMs", active.engine.durationMs())
                obj.put("elapsedMs", active.engine.elapsedMs())
                obj.put("level", AudioRecordEngine.roundDb(active.engine.levelDb()))
                obj.put("peakDb", AudioRecordEngine.roundDb(active.engine.peakDb()))
                obj.put("soundDetected", active.engine.soundDetected())
                obj.put("silentMs", active.engine.silentMs())
                obj.put("soundMs", active.engine.soundMs())
                obj.put("thresholdDb", active.config.silenceThresholdDb)
                obj.put("bufferedBytes", active.engine.tapBufferedBytes())
                obj.put("droppedBytes", active.engine.tapDroppedBytes())
                active.engine.error()?.let { obj.put("error", it) }
            } else {
                obj.put("state", "idle")
                lastResult?.let { obj.put("last", it.toJson()) }
                lastError?.let { obj.put("error", it) }
            }
            return obj.toString()
        }
    }

    @JvmStatic
    fun hasRecordPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * 申请录音权限并最多等待 [timeoutMs] 毫秒。会阻塞调用线程（脚本线程），
     * 不要在主线程调用。
     */
    @JvmStatic
    fun requestRecordPermission(context: Context, timeoutMs: Long): Boolean {
        if (hasRecordPermission(context)) return true
        Permissions.requestPermissions(context, arrayOf(Manifest.permission.RECORD_AUDIO))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasRecordPermission(context)) return true
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return hasRecordPermission(context)
    }

    internal fun onServiceForeground() {
        serviceSignal.getAndSet(null)?.complete(Unit)
    }

    internal fun onServiceStartFailed(t: Throwable) {
        serviceSignal.getAndSet(null)
            ?.completeExceptionally(IllegalStateException(t.message ?: t.toString(), t))
    }

    private fun handleAbort(message: String) {
        // 该回调可能来自录音线程或主线程，不能原地 stop（stop 需要 join 录音线程）
        Thread({
            try {
                synchronized(lock) {
                    val active = session
                    if (active != null && active.engine.error() != null) {
                        stopSession(active, message)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "录音异常终止处理失败", t)
            }
        }, "AutoX-AudioRecordAbort").start()
    }

    private fun stopSession(target: Session, error: String?): RecordResult {
        if (session === target) {
            session = null
        }
        lastEngine = target.engine
        val result = try {
            target.engine.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "停止录音失败", t)
            RecordResult(
                path = if (target.config.path.isEmpty()) "" else File(target.config.path).absolutePath,
                source = target.config.source.jsonName,
                format = target.config.format.jsonName,
                sampleRate = target.config.sampleRate,
                channels = target.config.channels,
                bytes = 0,
                durationMs = 0,
                peakDb = AudioRecordEngine.MIN_LEVEL_DB,
                droppedBytes = 0,
                error = t.message ?: t.toString()
            )
        }
        val finalResult = if (error != null && result.error == null) result.copy(error = error) else result
        lastResult = finalResult
        lastError = finalResult.error
        appContext?.let { AudioRecordService.stop(it) }
        return finalResult
    }
}

fun RecordResult.toJson(): JSONObject = JSONObject().apply {
    put("path", path)
    put("source", source)
    put("format", format)
    put("sampleRate", sampleRate)
    put("channels", channels)
    put("bytes", bytes)
    put("durationMs", durationMs)
    put("peakDb", peakDb)
    put("droppedBytes", droppedBytes)
    putOpt("error", error)
}

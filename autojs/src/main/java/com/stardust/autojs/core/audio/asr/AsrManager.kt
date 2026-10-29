package com.stardust.autojs.core.audio.asr

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * 进程级 ASR 管理器（当前只预留接口，不自带识别引擎）。
 *
 * 引擎注册后所有脚本共享同一个实例，可跨脚本做流式识别；引擎未注册时
 * [statusJson] 返回 available=false，其余调用抛出带说明的异常。
 */
object AsrManager {
    private const val TAG = "AsrManager"
    private val lock = Any()
    private val nextSessionId = AtomicInteger(1)

    @Volatile
    private var engine: AsrEngine? = null

    private val sessions = LinkedHashMap<Int, AsrSession>()

    @JvmStatic
    fun statusJson(): String = synchronized(lock) { statusObject().toString() }

    /**
     * 注册内置 sherpa-onnx 引擎（zipformer/paraformer/sensevoice，模型路径在配置里给）。
     * 返回注册后的状态 JSON。
     */
    @JvmStatic
    fun loadBuiltinEngine(configJson: String?): String {
        registerEngine(SherpaAsrEngine(), configJson)
        return statusJson()
    }

    /** App 内注册引擎（插件引擎请用 [loadPluginEngine]）。 */
    @JvmStatic
    fun registerEngine(engine: AsrEngine, configJson: String?) {        synchronized(lock) {
            unloadLocked()
            try {
                engine.initialize(configJson)
            } catch (t: Throwable) {
                runCatching { engine.release() }
                throw IllegalStateException("ASR 引擎初始化失败: ${t.message}", t)
            }
            this.engine = engine
        }
    }

    /**
     * 从已安装的插件 APK 加载引擎：插件类需 public、有无参构造、实现 [AsrEngine]。
     * 返回注册后的状态 JSON。
     */
    @JvmStatic
    fun loadPluginEngine(
        context: Context,
        packageName: String,
        className: String,
        configJson: String?
    ): String {
        val pkg = try {
            context.createPackageContext(
                packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
        } catch (t: Throwable) {
            throw IllegalStateException("加载 ASR 插件失败：无法访问包 $packageName（是否已安装？）", t)
        }
        val clazz = try {
            pkg.classLoader.loadClass(className)
        } catch (t: Throwable) {
            throw IllegalStateException("加载 ASR 插件失败：$packageName 中找不到 $className", t)
        }
        val instance = try {
            clazz.getDeclaredConstructor().newInstance()
        } catch (t: Throwable) {
            throw IllegalStateException("加载 ASR 插件失败：$className 需要 public 无参构造函数", t)
        }
        if (instance !is AsrEngine) {
            throw IllegalStateException("加载 ASR 插件失败：$className 没有实现 AsrEngine 接口")
        }
        registerEngine(instance, configJson)
        return statusJson()
    }

    @JvmStatic
    fun unloadEngine() {
        synchronized(lock) {
            unloadLocked()
        }
    }

    /** 整段识别：PCM16LE 的 Base64（录音 readPcm 返回的 base64 可直接用）。 */
    @JvmStatic
    fun transcribePcmJson(base64: String, sampleRate: Int, channels: Int): String {
        val target = requireEngine()
        val samples = decodeSamples(base64)
        if (samples.isEmpty()) {
            throw IllegalArgumentException("PCM 数据为空")
        }
        val rate = if (sampleRate > 0) sampleRate else target.info.sampleRate
        val ch = if (channels > 0) channels else 1
        val pcm = if (rate == target.info.sampleRate && ch == 1) {
            samples
        } else {
            PcmResampler(target.info.sampleRate).process(samples, rate, ch)
        }
        val result = synchronized(target) { target.transcribe(pcm) }
        return result.toJson(target.info.sampleRate).toString()
    }

    /**
     * 整段识别 WAV/PCM 文件。WAV 自动识别采样率/声道；裸 PCM 需传 sampleRate。
     * 相对路径按 /sdcard 解析。
     */
    @JvmStatic
    fun transcribeFileJson(path: String, sampleRate: Int, channels: Int): String {
        val target = requireEngine()
        val lower = path.lowercase()
        if (lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".amr")) {
            throw IllegalArgumentException("ASR 只接受 WAV/PCM，压缩音频请先解码（录音时用 format:'wav' 或 'pcm'）")
        }
        val file = File(resolvePath(path))
        if (!file.isFile) {
            throw IllegalArgumentException("文件不存在: ${file.absolutePath}")
        }
        val data = Pcm16.decode(file.readBytes(), sampleRate, channels)
        if (data.samples.isEmpty()) {
            throw IllegalArgumentException("音频数据为空: ${file.absolutePath}")
        }
        val pcm = if (data.sampleRate == target.info.sampleRate && data.channels == 1) {
            data.samples
        } else {
            PcmResampler(target.info.sampleRate).process(data.samples, data.sampleRate, data.channels)
        }
        val result = synchronized(target) { target.transcribe(pcm) }
        return result.toJson(target.info.sampleRate).toString()
    }

    @JvmStatic
    fun startStreamJson(): String {
        val target = requireEngine()
        if (!target.info.streaming) {
            throw IllegalStateException("当前 ASR 引擎不支持流式识别（${target.info.name}）")
        }
        return synchronized(lock) {
            val id = nextSessionId.getAndIncrement()
            sessions[id] = AsrSession(id, target, target.newStream())
            JSONObject()
                .put("id", id)
                .put("sampleRate", target.info.sampleRate)
                .put("streaming", true)
                .toString()
        }
    }

    /** 送一段 PCM16LE Base64 进流，返回当前识别快照（含实时假设文本）。 */
    @JvmStatic
    fun feedStreamJson(id: Int, base64: String, sampleRate: Int, channels: Int): String {
        val session = synchronized(lock) { sessions[id] }
            ?: throw IllegalStateException("ASR 流不存在或已结束: $id")
        val samples = decodeSamples(base64)
        val snapshot = session.feed(
            samples,
            if (sampleRate > 0) sampleRate else session.sampleRate,
            if (channels > 0) channels else 1
        )
        return snapshot.toJson().toString()
    }

    /** 收尾并返回最终文本；会话随后被移除。 */
    @JvmStatic
    fun finishStreamJson(id: Int): String {
        val session = synchronized(lock) { sessions.remove(id) }
            ?: throw IllegalStateException("ASR 流不存在或已结束: $id")
        return session.finish().toJson().toString()
    }

    @JvmStatic
    fun cancelStream(id: Int): Boolean {
        val session = synchronized(lock) { sessions.remove(id) } ?: return false
        session.cancel()
        return true
    }

    private fun requireEngine(): AsrEngine = synchronized(lock) {
        engine ?: throw IllegalStateException(
            "ASR 引擎未注册：当前版本只预留了接口，请先用 media.asr.loadPluginEngine(包名, 类名, 配置) 加载引擎插件"
        )
    }

    private fun unloadLocked() {
        sessions.values.forEach { it.cancel() }
        sessions.clear()
        engine?.let { e ->
            synchronized(e) {
                runCatching { e.release() }.onFailure { Log.w(TAG, "释放 ASR 引擎失败", it) }
            }
        }
        engine = null
    }

    private fun statusObject(): JSONObject {
        val current = engine
        return JSONObject().apply {
            if (current == null) {
                put("available", false)
                put("engine", JSONObject.NULL)
                put("sampleRate", 16000)
                put("streaming", false)
                put("offline", false)
                put("languages", JSONArray())
                put("error", "ASR 引擎未注册（接口已预留，等待接入引擎）")
            } else {
                val info = current.info
                put("available", true)
                put("engine", info.name)
                put("sampleRate", info.sampleRate)
                put("streaming", info.streaming)
                put("offline", info.offline)
                put("languages", JSONArray(info.languages))
            }
        }
    }

    private fun decodeSamples(base64: String): ShortArray {
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (t: IllegalArgumentException) {
            throw IllegalArgumentException("PCM Base64 解码失败: ${t.message}", t)
        }
        return Pcm16.bytesToShorts(bytes)
    }

    private fun resolvePath(path: String): String {
        if (path.startsWith("/")) return path
        return File(Environment.getExternalStorageDirectory(), path).absolutePath
    }
}

private fun AsrResult.toJson(sampleRate: Int): JSONObject = JSONObject().apply {
    put("text", text)
    put("isFinal", isFinal)
    put("sampleRate", sampleRate)
    put("segments", segments.toJson())
}

private fun AsrSnapshot.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("text", text)
    put("partial", partial)
    put("isFinal", isFinal)
    put("fedMs", fedMs)
    put("segments", segments.toJson())
}

private fun List<AsrSegment>.toJson(): JSONArray = JSONArray().also { array ->
    forEach { segment ->
        array.put(
            JSONObject()
                .put("text", segment.text)
                .put("startMs", segment.startMs)
                .put("endMs", segment.endMs)
        )
    }
}

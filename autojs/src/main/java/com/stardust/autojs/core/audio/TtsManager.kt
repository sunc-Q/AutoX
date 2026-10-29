package com.stardust.autojs.core.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 系统 TTS（android.speech.tts.TextToSpeech）的薄封装。
 * UtteranceProgressListener 是抽象类，Rhino 无法继承，完成回调必须由这层代持；
 * 其余能力（engines/voices/params）JS 仍可经 Packages.android.speech.tts.* 直调。
 *
 * init 配置 JSON：{ engine?: "com.xxx", language?: "zh-CN", pitch?: 1.0, rate?: 1.0, volume?: 1.0 }
 * speak/synthesizeToFile 的 config 可按次覆盖 pitch/rate/volume/language。
 * 所有方法返回 JSON 字符串，错误以 { "ok": false, "error": "..." } 表达。
 */
object TtsManager {

    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var enginePackage: String? = null
    private val seq = AtomicLong(0)

    @JvmStatic
    fun initJson(context: Context, configJson: String?): String = synchronized(lock) {
        val json = runCatching { JSONObject(configJson ?: "{}") }
            .getOrElse { return err("TTS 配置不是合法 JSON: ${it.message}") }
        shutdownLocked()
        enginePackage = json.optStringOrNull("engine")
        val latch = CountDownLatch(1)
        var initStatus = Int.MIN_VALUE
        val listener = TextToSpeech.OnInitListener { status ->
            initStatus = status
            ready = status == TextToSpeech.SUCCESS
            latch.countDown()
        }
        tts = if (enginePackage != null) {
            TextToSpeech(context.applicationContext, listener, enginePackage)
        } else {
            TextToSpeech(context.applicationContext, listener)
        }
        if (!latch.await(8, TimeUnit.SECONDS)) {
            shutdownLocked()
            return err("TTS 初始化超时（8s）")
        }
        if (!ready) {
            shutdownLocked()
            return err("TTS 初始化失败: status=$initStatus" + if (enginePackage != null) " (engine=$enginePackage)" else "")
        }
        applyParamsLocked(json)
        statusJson()
    }

    /** 阻塞朗读直到播完（或超时）。返回 { ok, ms }。 */
    @JvmStatic
    fun speakJson(text: String, configJson: String?): String = withReady { t ->
        val json = parseOverride(configJson)
        val langErr = applyParamsLocked(json)
        if (langErr != null) return err(langErr)
        val timeout = json.optLong("timeoutMs", 60_000L)
        val id = "speak-${seq.incrementAndGet()}"
        val done = CountDownLatch(1)
        var error: Int = TextToSpeech.SUCCESS
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) { error = -1; done.countDown() } }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) { error = errorCode; done.countDown() } }
        })
        val params = android.os.Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, json.optDouble("volume", 1.0).toFloat())
        val t0 = System.currentTimeMillis()
        val rc = t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        if (rc != TextToSpeech.SUCCESS) return err("speak 入队失败: rc=$rc")
        if (!done.await(timeout, TimeUnit.MILLISECONDS)) {
            t.stop()
            return err("speak 超时（${timeout}ms）")
        }
        JSONObject().put("ok", error == TextToSpeech.SUCCESS)
            .put("ms", System.currentTimeMillis() - t0)
            .put("error", if (error == TextToSpeech.SUCCESS) JSONObject.NULL else "tts error=$error")
            .toString()
    }

    /** 阻塞合成到 WAV 文件。path 相对路径按脚本目录解析（Media 层已处理）。 */
    @JvmStatic
    fun synthesizeToFileJson(text: String, path: String, configJson: String?): String = withReady { t ->
        val json = parseOverride(configJson)
        val file = File(path)
        file.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        val langErr = applyParamsLocked(json)
        if (langErr != null) return err(langErr)
        val timeout = json.optLong("timeoutMs", 120_000L)
        val id = "file-${seq.incrementAndGet()}"
        val done = CountDownLatch(1)
        var error: Int = TextToSpeech.SUCCESS
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) { error = -1; done.countDown() } }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) { error = errorCode; done.countDown() } }
        })
        val t0 = System.currentTimeMillis()
        val rc = t.synthesizeToFile(text as CharSequence, android.os.Bundle(), file, id)
        if (rc != TextToSpeech.SUCCESS) return err("synthesizeToFile 入队失败: rc=$rc")
        if (!done.await(timeout, TimeUnit.MILLISECONDS)) {
            t.stop()
            return err("synthesizeToFile 超时（${timeout}ms）")
        }
        if (!file.isFile || file.length() <= 44L) {
            return err("合成产物缺失或过小: ${file.absolutePath} (${file.length()}B)")
        }
        JSONObject().put("ok", true)
            .put("path", file.absolutePath)
            .put("bytes", file.length())
            .put("ms", System.currentTimeMillis() - t0)
            .toString()
    }

    @JvmStatic
    fun stop(): Boolean = synchronized(lock) { tts?.stop() == TextToSpeech.SUCCESS }

    @JvmStatic
    fun shutdown() = synchronized(lock) { shutdownLocked() }

    @JvmStatic
    fun statusJson(): String = synchronized(lock) {
        val t = tts
        val obj = JSONObject()
            .put("available", ready)
            .put("engine", enginePackage ?: JSONObject.NULL)
            .put("language", if (t != null) t.language?.toString() ?: JSONObject.NULL else JSONObject.NULL)
        if (t != null) {
            val engines = JSONArray()
            for (e in t.engines) {
                engines.put(JSONObject().put("name", e.name).put("label", e.label))
            }
            obj.put("engines", engines)
        }
        obj.toString()
    }

    private inline fun withReady(block: (TextToSpeech) -> String): String = synchronized(lock) {
        val t = tts
        if (t == null || !ready) return err("TTS 未初始化，先调 tts.init()")
        try {
            block(t)
        } catch (e: Exception) {
            err("TTS 调用失败: ${e.message}")
        }
    }

    private fun parseOverride(configJson: String?): JSONObject =
        runCatching { JSONObject(configJson ?: "{}") }.getOrElse { JSONObject() }

    /** 返回 null 表示成功；语言设置失败时返回错误描述（只在显式传了 language 时才设置） */
    private fun applyParamsLocked(json: JSONObject): String? {
        val t = tts ?: return "TTS 未初始化"
        val lang = json.optStringOrNull("language")
        if (lang != null) {
            val parts = lang.split('-', '_')
            val locale = when (parts.size) {
                1 -> Locale(parts[0])
                else -> Locale(parts[0], parts[1])
            }
            val rc = t.setLanguage(locale)
            if (rc == TextToSpeech.LANG_MISSING_DATA || rc == TextToSpeech.LANG_NOT_SUPPORTED) {
                return "语言不可用: $lang (rc=$rc)"
            }
        }
        if (json.has("pitch")) t.setPitch(json.optDouble("pitch", 1.0).toFloat())
        if (json.has("rate")) t.setSpeechRate(json.optDouble("rate", 1.0).toFloat())
        return null
    }

    private fun shutdownLocked() {
        tts?.let { runCatching { it.stop() }; runCatching { it.shutdown() } }
        tts = null
        ready = false
    }

    private fun err(msg: String): String =
        JSONObject().put("ok", false).put("error", msg).toString()

    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name, "").ifEmpty { null }
    }
}

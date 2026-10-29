package com.stardust.autojs.core.audio

import android.media.AudioAttributes
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AudioSourceType(val id: Int, val jsonName: String, val label: String) {
    MIC(0, "mic", "麦克风"),
    SYSTEM(1, "system", "系统内录");

    companion object {
        fun fromJson(name: String?): AudioSourceType =
            values().firstOrNull { it.jsonName == name?.trim()?.lowercase() }
                ?: throw IllegalArgumentException("不支持的录音来源: $name（可选 mic / system）")
    }
}

enum class AudioOutputFormat(val ext: String, val jsonName: String) {
    WAV(".wav", "wav"),
    PCM(".pcm", "pcm"),
    MP3(".mp3", "mp3"),

    /** 不落盘：只采集 + 波形环形缓冲/静音统计，适合边采边分析或 WS 外送 */
    NONE("", "none");

    companion object {
        fun fromJson(name: String?): AudioOutputFormat =
            values().firstOrNull { it.jsonName == name?.trim()?.lowercase() }
                ?: throw IllegalArgumentException("不支持的录音格式: $name（可选 wav / pcm / mp3 / none）")

        fun fromPath(path: String): AudioOutputFormat? =
            values().firstOrNull { it != NONE && path.endsWith(it.ext, ignoreCase = true) }
    }
}

data class AudioRecordConfig(
    val source: AudioSourceType,
    val format: AudioOutputFormat,
    val path: String,
    val sampleRate: Int,
    val channels: Int,
    val bitRateKbps: Int,
    val usages: IntArray,
    /** JS 侧 PCM 环形缓冲大小，0 表示不缓存（只落盘） */
    val tapBufferBytes: Int,
    /** 判定「有声音」的 RMS 阈值（dBFS） */
    val silenceThresholdDb: Double
) {
    val bytesPerFrame: Int get() = channels * 2

    fun durationMsOf(pcmBytes: Long): Long =
        if (bytesPerFrame <= 0 || sampleRate <= 0) 0
        else pcmBytes * 1000 / (bytesPerFrame.toLong() * sampleRate)

    companion object {
        const val MIN_SAMPLE_RATE = 8000
        const val MAX_SAMPLE_RATE = 48000
        const val DEFAULT_TAP_BUFFER_BYTES = 2 * 1024 * 1024
        const val MAX_TAP_BUFFER_BYTES = 64 * 1024 * 1024
        const val DEFAULT_SILENCE_THRESHOLD_DB = -50.0

        private val USAGE_NAMES = mapOf(
            "unknown" to AudioAttributes.USAGE_UNKNOWN,
            "media" to AudioAttributes.USAGE_MEDIA,
            "game" to AudioAttributes.USAGE_GAME,
            "voice_communication" to AudioAttributes.USAGE_VOICE_COMMUNICATION,
            "alarm" to AudioAttributes.USAGE_ALARM,
            "notification" to AudioAttributes.USAGE_NOTIFICATION,
            "notification_ringtone" to AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            "assistant" to AudioAttributes.USAGE_ASSISTANT
        )

        fun parse(json: String?): AudioRecordConfig {
            val obj = if (json.isNullOrBlank()) JSONObject() else try {
                JSONObject(json)
            } catch (e: Exception) {
                throw IllegalArgumentException("录音参数不是合法的 JSON: ${e.message}")
            }
            val source = AudioSourceType.fromJson(obj.optString("source", AudioSourceType.MIC.jsonName))

            var path = obj.optString("path", "").trim()
            val explicitFormat = obj.optString("format", "").trim()
            val pathFormat = if (path.isEmpty()) null else AudioOutputFormat.fromPath(path)
            val format = when {
                explicitFormat.isNotEmpty() -> AudioOutputFormat.fromJson(explicitFormat)
                pathFormat != null -> pathFormat
                else -> AudioOutputFormat.WAV
            }
            if (format == AudioOutputFormat.NONE) {
                // 不落盘，path 无意义
                path = ""
            } else if (path.isEmpty()) {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                path = "record_$stamp${format.ext}"
            } else if (!path.endsWith(format.ext, ignoreCase = true)) {
                path = if (pathFormat != null) {
                    path.dropLast(pathFormat.ext.length) + format.ext
                } else {
                    path + format.ext
                }
            }
            // 相对路径落到外部存储根目录，避免按进程 CWD(/) 解析导致无权限
            if (path.isNotEmpty() && !path.startsWith("/")) {
                path = File(Environment.getExternalStorageDirectory(), path).absolutePath
            }

            val sampleRate = obj.optInt(
                "sampleRate",
                if (source == AudioSourceType.SYSTEM) 44100 else 16000
            )
            if (sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) {
                throw IllegalArgumentException(
                    "sampleRate 超出范围: $sampleRate（${MIN_SAMPLE_RATE}~${MAX_SAMPLE_RATE}）"
                )
            }
            val channels = obj.optInt("channels", if (source == AudioSourceType.SYSTEM) 2 else 1)
            if (channels !in 1..2) {
                throw IllegalArgumentException("channels 只支持 1（单声道）或 2（立体声），当前: $channels")
            }
            val bitRateKbps = obj.optInt("bitRate", 128)
            if (format == AudioOutputFormat.MP3 && bitRateKbps !in 8..320) {
                throw IllegalArgumentException("MP3 bitRate 超出范围: $bitRateKbps kbps（8~320）")
            }

            return AudioRecordConfig(
                source = source,
                format = format,
                path = path,
                sampleRate = sampleRate,
                channels = channels,
                bitRateKbps = bitRateKbps,
                usages = parseUsages(obj.optJSONArray("usages")),
                tapBufferBytes = obj.optInt("tapBufferBytes", DEFAULT_TAP_BUFFER_BYTES)
                    .coerceIn(0, MAX_TAP_BUFFER_BYTES),
                silenceThresholdDb = obj.optDouble(
                    "silenceThresholdDb",
                    obj.optDouble("silenceThreshold", DEFAULT_SILENCE_THRESHOLD_DB)
                ).coerceIn(-120.0, 0.0)
            )
        }

        private fun parseUsages(array: JSONArray?): IntArray {
            if (array == null || array.length() == 0) {
                return intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)
            }
            val result = ArrayList<Int>(array.length())
            for (i in 0 until array.length()) {
                when (val item = array.opt(i)) {
                    is Number -> result.add(item.toInt())
                    is String -> result.add(
                        USAGE_NAMES[item.trim().lowercase()]
                            ?: throw IllegalArgumentException(
                                "不支持的 usage: $item（可选 ${USAGE_NAMES.keys.joinToString(" / ")} 或直接写数值）"
                            )
                    )
                    else -> throw IllegalArgumentException("usages 数组第 ${i + 1} 项类型不支持: $item")
                }
            }
            return result.toIntArray()
        }
    }
}

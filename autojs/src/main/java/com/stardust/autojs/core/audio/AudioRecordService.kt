package com.stardust.autojs.core.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.stardust.autojs.R

/**
 * 录音前台服务。和 CaptureForegroundService 一样跑在脚本进程：
 * 进程内单例 AudioRecordManager 靠它保活，脚本结束后录音不会中断，
 * Android 11+ 也靠它拿到后台麦克风采集资格。
 */
class AudioRecordService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // stop() 要等录音线程退出 + MP3 编码收尾，不能占着主线程
            Thread({
                try {
                    AudioRecordManager.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "通知栏停止录音失败", t)
                }
                stopSelf()
            }, "AutoX-AudioRecordStop").start()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat()
            AudioRecordManager.onServiceForeground()
        } catch (t: Throwable) {
            Log.e(TAG, "录音前台服务启动失败", t)
            AudioRecordManager.onServiceStartFailed(t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            Log.w(TAG, "清除录音通知失败", t)
        }
        releaseWakeLock()
    }

    private fun startForegroundCompat() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoX 正在${pendingLabel}")
            .setContentText("录音进行中，脚本结束后仍会继续")
            .setSmallIcon(R.drawable.autojs_logo)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true)
            .setVibrate(LongArray(0))
            .addAction(createStopAction())
            .setChannelId(CHANNEL_ID)
            .build()
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    (if (pendingWithProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pendingWithProjection ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "AutoX 录音", NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "录音进行时的常驻通知"
        channel.enableLights(false)
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
    }

    private fun createStopAction(): NotificationCompat.Action {
        val pendingIntent = PendingIntent.getService(
            this, 21, Intent(this, AudioRecordService::class.java).apply {
                action = ACTION_STOP
            }, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(null, "停止录音", pendingIntent).build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoX:AudioRecord").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "获取录音 WakeLock 失败", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "释放录音 WakeLock 失败", t)
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "AudioRecordService"
        private const val ACTION_STOP = "com.stardust.autojs.core.audio.STOP"
        private const val NOTIFICATION_ID = 27
        private val CHANNEL_ID = "autox_audio_record"

        @Volatile
        private var pendingWithProjection = false

        @Volatile
        private var pendingLabel = "录音"

        fun start(context: Context, withProjection: Boolean, label: String) {
            pendingWithProjection = withProjection
            pendingLabel = label
            val intent = Intent(context, AudioRecordService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "无法启动录音前台服务（系统可能限制了后台启动前台服务，请先打开悬浮窗权限或在 AutoX 界面内开始录音）: ${t.message}",
                    t
                )
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AudioRecordService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "停止录音前台服务失败", t)
            }
        }
    }
}

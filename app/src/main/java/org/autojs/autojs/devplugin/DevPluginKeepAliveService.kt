package org.autojs.autojs.devplugin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.autojs.autoxjs.R
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 9317 远程控制服务的前台保活服务。
 *
 * 作用：
 * 1. 默认后台保活：以前台服务运行，App 退到后台/锁屏后 9317 服务不被系统杀掉。
 * 2. 通知显示后台网页地址：常驻通知展示 http://手机IP:9317，点击通知直接用浏览器打开控制页。
 */
class DevPluginKeepAliveService : Service() {

    companion object {
        private const val TAG = "DevPluginKeepAlive"
        private const val CHANNEL_ID = "devplugin_keepalive"
        private const val NOTIFICATION_ID = 9317
        const val ACTION_START = "org.autojs.autojs.devplugin.ACTION_START_KEEPALIVE"
        const val ACTION_STOP = "org.autojs.autojs.devplugin.ACTION_STOP_KEEPALIVE"

        fun start(context: Context) {
            val intent = Intent(context, DevPluginKeepAliveService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DevPluginKeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** 获取本机局域网 IPv4（优先 wlan 网卡），用于通知展示网页地址。 */
        fun getLocalIp(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
                var fallback: String? = null
                for (ni in interfaces) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val name = ni.name
                    for (addr in ni.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (name.startsWith("wlan")) return ip
                            if (fallback == null) fallback = ip
                        }
                    }
                }
                fallback ?: "127.0.0.1"
            } catch (e: Exception) {
                "127.0.0.1"
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                CoroutineScope(Dispatchers.IO).launch {
                    DevPlugin.stopUSBDebug()
                    Log.i(TAG, "9317 服务已停止")
                }
                stopSelf()
            }

            else -> {
                startForegroundCompat()
                // 幂等启动 9317 服务：服务已运行时跳过，避免重复监听端口
                if (!DevPlugin.isUSBDebugServiceActive) {
                    scope.launch {
                        DevPlugin.startUSBDebug()
                        Log.i(TAG, "9317 服务已启动")
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "远程控制服务",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "9317 远程控制后台服务保活"
        channel.enableLights(false)
        manager.createNotificationChannel(channel)

        val url = "http://${getLocalIp()}:${DevPlugin.SERVER_PORT}"
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            webIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("远程控制服务运行中")
            .setContentText("浏览器访问 $url 控制手机")
            .setStyle(NotificationCompat.BigTextStyle().bigText("浏览器访问 $url 控制手机"))
            .setSmallIcon(R.drawable.autojs_logo)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVibrate(LongArray(0))
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
    }
}

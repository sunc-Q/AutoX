package com.stardust.autojs.servicecomponents

import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import com.google.gson.Gson
import com.stardust.app.service.AbstractAutoService.Companion.stopAllServices
import com.stardust.autojs.AutoJs
import com.stardust.autojs.IndependentScriptService
import com.stardust.autojs.core.shizuku.ShizukuClient
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.script.ScriptFile
import com.stardust.autojs.script.ScriptSource
import com.stardust.notification.NotificationListenerService
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess
import java.lang.ref.WeakReference

class ScriptBinder(service: IndependentScriptService, val scope: CoroutineScope) : Binder() {
    val wService = WeakReference(service)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        runBlocking {
            data.enforceInterface(DESCRIPTOR)
            Log.d(TAG, "action id = $code")
            when (code) {
                Action.GET_ALL_TASKS.id -> getAllScriptTasks(data, reply!!)
                Action.RUN_SCRIPT.id -> runScript(data)
                Action.STOP_SCRIPT.id -> stopScript(data)
                Action.STOP_ALL_SCRIPT.id -> stopAllScript()
                Action.APP_EXIT.id -> appExit()
                Action.REGISTER_GLOBAL_SCRIPT_LISTENER.id -> registerGlobalScriptListener(data)
                Action.REGISTER_GLOBAL_CONSOLE_LISTENER.id -> registerGlobalConsoleListener(data)
                Action.NOTIFICATION_LISTENER_SERVICE_STATUS.id -> notificationListenerServiceStatus(
                    reply!!
                )

                Action.GET_ALL_LOGS.id -> getAllLogs(data, reply!!)

                Action.BIND_SHIZUKU_SERVICE.id -> bindShizukuUserService()
                Action.GET_MEMORY_INFO.id -> getMemoryInfo(reply!!)
                else -> Log.w(TAG, "unknown action id = $code")
            }
            Log.d(TAG, "action id = $code, complete")
            return@runBlocking true
        }

    private fun appExit() {
        stopAllScript()
        stopAllServices()
        // 只停服务的话 :script 会空跑，无障碍服务还可能被系统重新拉起，所以让它自己退出。
        // 这里不能用 scope：stopAllServices 触发 onDestroy 时已把它 cancel 掉了。
        Thread {
            try {
                // 等一下让 onTransact 的 binder 回复发出去，否则调用侧收到 DeadObjectException
                Thread.sleep(500)
            } catch (_: InterruptedException) {
            }
            exitProcess(0)
        }.start()
    }

    private fun getAllScriptTasks(data: Parcel, reply: Parcel) {
        val scriptExecutions = AutoJs.instance.scriptEngineService.scriptExecutions
        val bundle = Bundle().apply { putInt("size", scriptExecutions.size) }
        for ((i: Int, scriptExecution) in scriptExecutions.withIndex()) {
            bundle.putBundle(
                i.toString(),
                TaskInfo.ExecutionTaskInfo(scriptExecution).toBundle()
            )
        }
        reply.writeNoException()
        reply.writeBundle(bundle)
    }

    private fun runScript(data: Parcel) {
        val bundle = data.readBundle(ClassLoader.getSystemClassLoader())
        check(bundle != null) { "bundle is null" }
        val taskInfo = bundle.getBundle(TaskInfo.TAG)!!.let {
            TaskInfo.fromBundle(it)
        }
        val listener = bundle.getBinder(BinderScriptListener.TAG)?.let {
            BinderScriptListener.ServerInterface(it)
        }
        val config = bundle.getString(ExecutionConfig.tag)?.let {
            ExecutionConfig.fromJson(it)
        }
        Log.d(TAG, "engineName = ${taskInfo.engineName}")
        val source: ScriptSource = ScriptFile(taskInfo.sourcePath).toSource()
        AutoJs.instance.scriptEngineService.execute(
            source, listener,
            config ?: ExecutionConfig(workingDirectory = taskInfo.workerDirectory)
        )
    }

    private fun stopScript(data: Parcel) {
        val id = data.readInt()
        check(id >= 0) { "invalid id" }
        val scriptExecutions = AutoJs.instance.scriptEngineService.scriptExecutions
        for (scriptExecution in scriptExecutions) {
            if (scriptExecution.id == id) {
                scriptExecution.engine.forceStop()
                break
            }
        }
    }

    private fun stopAllScript() = AutoJs.instance.scriptEngineService.stopAll()

    private fun registerGlobalScriptListener(data: Parcel) {
        val binder = data.readStrongBinder()
        val listener = BinderScriptListener.ServerInterface(binder)
        AutoJs.instance.scriptEngineService.registerGlobalScriptExecutionListener(listener)
    }

    private fun registerGlobalConsoleListener(data: Parcel) {
        val binder = data.readStrongBinder()
        val listener = BinderConsoleListener.ServerInterface(binder)
        val sub = AutoJs.instance.globalConsole.logPublish
            .observeOn(Schedulers.single()).subscribe(listener::onPrintln)
    }

    /** 全局日志回放：远程控制台打开时看不到握手前的日志，历史只在 globalConsole.allLogs 里。 */
    private fun getAllLogs(data: Parcel, reply: Parcel) {
        val limit = data.readInt().coerceIn(1, 5000)
        val console = AutoJs.instance.globalConsole
        val logs = synchronized(console.allLogs) {
            val size = console.allLogs.size
            ArrayList(console.allLogs.subList(if (size > limit) size - limit else 0, size))
        }
        reply.writeNoException()
        reply.writeString(GSON.toJson(logs))
    }

    private fun notificationListenerServiceStatus(reply: Parcel) {
        reply.writeNoException()
        reply.writeInt(if (NotificationListenerService.instance != null) 1 else 0)
    }

    private fun bindShizukuUserService() {
        if (ShizukuClient.instance.shizukuConnection.service == null) {
            ShizukuClient.instance.bindService()
        }
    }

    private fun getMemoryInfo(reply: Parcel) {
        reply.writeNoException()
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        Log.d(TAG, "memoryInfo: ${memoryInfo.totalPss / 1024}")
        memoryInfo.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
    }

    enum class Action(val id: Int) {
        START(1),
        STOP(2),
        GET_ALL_TASKS(3),
        RUN_SCRIPT(4),
        STOP_SCRIPT(5),
        STOP_ALL_SCRIPT(6),
        REGISTER_GLOBAL_SCRIPT_LISTENER(7),
        REGISTER_GLOBAL_CONSOLE_LISTENER(8),
        NOTIFICATION_LISTENER_SERVICE_STATUS(9),
        BIND_SHIZUKU_SERVICE(10),
        GET_MEMORY_INFO(11),
        GET_ALL_LOGS(12),
        APP_EXIT(99);
    }

    companion object {
        const val TAG = "ScriptBinder"
        const val DESCRIPTOR = "com.stardust.autojs.servicecomponents.ScriptBinder"
        private val GSON = Gson()
        suspend fun <T> connect(binder: IBinder, n: suspend TanBinder.() -> T): T {
            val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Parcel.obtain(binder)
            } else Parcel.obtain()
            d.writeInterfaceToken(DESCRIPTOR)
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Parcel.obtain(binder)
            } else Parcel.obtain()
            d.setDataSize(120)
            r.setDataSize(120)
            try {
                return TanBinder(binder, data = d, reply = r).n()
            } finally {
                d.recycle()
                r.recycle()
            }
        }
    }
}
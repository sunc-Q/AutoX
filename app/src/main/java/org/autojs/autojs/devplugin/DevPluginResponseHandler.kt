package org.autojs.autojs.devplugin

import android.annotation.SuppressLint
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.stardust.app.GlobalAppContext
import com.stardust.app.GlobalAppContext.toast
import com.stardust.autojs.project.ProjectConfig
import com.stardust.autojs.servicecomponents.BinderScriptListener
import com.stardust.autojs.servicecomponents.EngineController
import com.stardust.autojs.servicecomponents.TaskInfo
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.io.Zip
import com.stardust.pio.PFiles
import com.stardust.util.MD5
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.autojs.autojs.Pref
import org.autojs.autojs.model.script.ScriptFile
import org.autojs.autojs.model.script.Scripts
import org.autojs.autojs.storage.database.TimedTaskDatabase
import org.autojs.autojs.timing.TimedTask
import org.autojs.autojs.timing.TimedTaskManager
import org.autojs.autoxjs.R
import org.joda.time.LocalDateTime
import org.joda.time.LocalTime
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Created by Stardust on 2017/5/11.
 */
class DevPluginResponseHandler(private val cacheDir: File) : Handler {

    companion object {
        val TAG = DevPluginResponseHandler::class.java.simpleName
    }

    /** 命令结果回发器：由 DevPlugin.Connection 在每次处理前指向当前 WS 连接。 */
    var responder: ((type: String, data: JsonObject) -> Unit)? = null

    /** 循环运行中标记：stop/stopAll 时置位，循环协程据此提前退出。 */
    private val mLoopRunning = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 通过 connect_compute 发起的 PC 连接地址（DevPlugin.connection 是单槽：控制页连接也占槽，
     *  未真正连 PC 时 disconnect 不能调 DevPlugin.close()，否则会误关控制页连接）。 */
    private var mPcConnectedUrl: String? = null

    private val router = Router.RootRouter("type")
        .handler("command", Router("command")
            .handler("run") { data: JsonObject ->
                val script = data["script"].asString
                val name = getName(data) ?: ""
                val id = data["id"].asString
                runScript(id, name, script)
                true
            }
            .handler("stop") { data: JsonObject ->
                val id = data["id"].asString
                mLoopRunning.remove(id)
                stopScript(id)
                true
            }
            .handler("save") { data: JsonObject ->
                val script = data["script"].asString
                val name = getName(data) ?: ""
                saveScript(name, script)
                true
            }
            .handler("rerun") { data: JsonObject ->
                val id = data["id"].asString
                val script = data["script"].asString
                val name = getName(data) ?: ""
                try {
                    stopScript(id)
                } catch (e: Exception) {
                }
                runScript(id, name, script)
                true
            }
            .handler("stopAll") { data: JsonObject? ->
                mLoopRunning.clear()
                EngineController.stopAllScript()
                true
            }
            .handler("list_scripts") { data: JsonObject ->
                val base = Pref.getScriptDirPath()
                val path = if (data.has("path") && !data.get("path").isJsonNull)
                    data["path"].asString else base
                val items = JsonArray()
                val dir = File(path)
                dir.listFiles()?.sortedWith(
                    compareBy({ !it.isDirectory }, { it.name.lowercase() })
                )?.forEach { f ->
                    items.add(JsonObject().apply {
                        addProperty("name", f.name)
                        addProperty("path", f.path)
                        addProperty("isDir", f.isDirectory)
                        addProperty("size", f.length())
                        addProperty("mtime", f.lastModified())
                    })
                }
                responder?.invoke("list_scripts", JsonObject().apply {
                    addProperty("path", path)
                    addProperty("root", base)
                    add("items", items)
                })
                true
            }
            .handler("run_path") { data: JsonObject ->
                val path = data["path"].asString
                val file = File(path)
                if (file.exists() && file.isFile) {
                    // 直接在原路径运行：保持同目录依赖模块与工作目录，避免缓存复制破坏 require
                    runScriptOnce(path)
                    toast("运行成功")
                } else {
                    toast("run_path: 文件不存在: $path")
                }
                true
            }
            .handler("tasks") { data: JsonObject? ->
                CoroutineScope(Dispatchers.Main).launch {
                    val tasks = JsonArray()
                    val list = try {
                        EngineController.getAllScriptTasks().await()
                    } catch (e: Exception) {
                        emptyList<TaskInfo>()
                    }
                    list.forEach { t ->
                        tasks.add(JsonObject().apply {
                            addProperty("id", t.id)
                            addProperty("name", t.name)
                            addProperty("path", t.sourcePath)
                            addProperty("running", t.isRunning)
                        })
                    }
                    val execs = JsonArray()
                    mScriptExecutions.forEach { (viewId, engineId) ->
                        execs.add(JsonObject().apply {
                            addProperty("viewId", viewId)
                            addProperty("engineId", engineId)
                        })
                    }
                    responder?.invoke("tasks", JsonObject().apply {
                        add("tasks", tasks)
                        add("executions", execs)
                    })
                }
                true
            }
            .handler("create_file") { data: JsonObject ->
                val dir = File(data["path"].asString)
                val name = data["name"].asString
                val ext = if (data.has("ext")) data["ext"].asString else "js"
                val fileName = if (name.endsWith(".$ext")) name else "$name.$ext"
                val file = File(dir, fileName)
                val ok = if (file.exists()) false else file.createNewFile()
                responder?.invoke("result", JsonObject().apply {
                    addProperty("ok", ok)
                    addProperty("path", file.path)
                })
                true
            }
            .handler("create_dir") { data: JsonObject ->
                val dir = File(File(data["path"].asString), data["name"].asString)
                val ok = dir.mkdirs()
                responder?.invoke("result", JsonObject().apply { addProperty("ok", ok) })
                true
            }
            .handler("rename") { data: JsonObject ->
                val f = File(data["path"].asString)
                val newName = data["newName"].asString
                val dest = File(f.parentFile, newName)
                val ok = f.renameTo(dest)
                responder?.invoke("result", JsonObject().apply {
                    addProperty("ok", ok)
                    addProperty("path", dest.path)
                })
                true
            }
            .handler("delete") { data: JsonObject ->
                val ok = File(data["path"].asString).deleteRecursively()
                responder?.invoke("result", JsonObject().apply { addProperty("ok", ok) })
                true
            }
            .handler("read_file") { data: JsonObject ->
                val f = File(data["path"].asString)
                responder?.invoke("read_file", JsonObject().apply {
                    addProperty("path", f.path)
                    addProperty("size", f.length())
                    addProperty("content",
                        if (f.isFile && f.length() < 2 * 1024 * 1024) f.readText() else "")
                })
                true
            }
            .handler("write_file") { data: JsonObject ->
                val f = File(data["path"].asString)
                f.parentFile?.mkdirs()
                f.writeText(data["content"].asString)
                responder?.invoke("result", JsonObject().apply { addProperty("ok", true) })
                true
            }
            .handler("run_loop") { data: JsonObject ->
                val path = data["path"].asString
                val file = File(path)
                if (file.exists() && file.isFile) {
                    val loopTimes = if (data.has("loopTimes")) data["loopTimes"].asInt else 1
                    val interval = if (data.has("interval")) data["interval"].asLong else 1000L
                    val delay0 = if (data.has("delay")) data["delay"].asLong else 0L
                    mLoopRunning[path] = true
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            if (delay0 > 0) delay(delay0)
                            var i = 0
                            while (mLoopRunning[path] == true && (loopTimes < 0 || i < loopTimes)) {
                                runScriptOnce(path)
                                i++
                                if (mLoopRunning[path] == true && (loopTimes < 0 || i < loopTimes)) {
                                    delay(interval)
                                }
                            }
                        } finally {
                            mLoopRunning.remove(path)
                        }
                    }
                    toast("循环运行: ${file.name} ×${if (loopTimes < 0) "∞" else loopTimes}")
                } else {
                    toast("run_loop: 文件不存在: $file")
                }
                true
            }
            .handler("timed_tasks") { data: JsonObject? ->
                CoroutineScope(Dispatchers.IO).launch {
                    val arr = JsonArray()
                    try {
                        TimedTaskDatabase(GlobalAppContext.get()).queryAll().forEach { t ->
                            arr.add(JsonObject().apply {
                                addProperty("id", t.id)
                                addProperty("scriptPath", t.scriptPath)
                                addProperty("timeFlag", t.timeFlag)
                                addProperty("millis", t.millis)
                                addProperty("isScheduled", t.isScheduled)
                                addProperty("isDisposable", t.isDisposable)
                                addProperty("isDaily", t.isDaily)
                                addProperty("delay", t.delay)
                                addProperty("interval", t.interval)
                                addProperty("loopTimes", t.loopTimes)
                            })
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    responder?.invoke("timed_tasks", JsonObject().apply { add("tasks", arr) })
                }
                true
            }
            .handler("timed_task_add") { data: JsonObject ->
                val scriptPath = data["path"].asString
                val type = if (data.has("type")) data["type"].asString else "daily"
                val hhmm = data["time"].asString
                val parts = hhmm.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val task: TimedTask? = try {
                    when (type) {
                        "disposable" -> {
                            val date = if (data.has("date")) data["date"].asString else ""
                            val dp = date.split("-")
                            val dateTime = if (dp.size == 3)
                                LocalDateTime(dp[0].toInt(), dp[1].toInt(), dp[2].toInt(), hour, minute)
                            else LocalDateTime.now().withTime(hour, minute, 0, 0)
                            TimedTask.disposableTask(dateTime, scriptPath, ExecutionConfig.default)
                        }
                        "weekly" -> {
                            var flag = 0L
                            if (data.has("days")) data["days"].asJsonArray.forEach { el ->
                                flag = flag or TimedTask.getDayOfWeekTimeFlag(el.asInt)
                            }
                            if (flag == 0L) flag = TimedTask.FLAG_EVERYDAY.toLong()
                            TimedTask.weeklyTask(LocalTime(hour, minute), flag, scriptPath, ExecutionConfig.default)
                        }
                        else -> TimedTask.dailyTask(LocalTime(hour, minute), scriptPath, ExecutionConfig.default)
                    }
                } catch (e: Exception) {
                    toast("定时任务参数错误: ${e.message}")
                    null
                }
                if (task != null) {
                    TimedTaskManager.addTask(task)
                    responder?.invoke("result", JsonObject().apply {
                        addProperty("ok", true)
                        addProperty("id", task.id)
                    })
                } else {
                    responder?.invoke("result", JsonObject().apply { addProperty("ok", false) })
                }
                true
            }
            .handler("timed_task_remove") { data: JsonObject ->
                val id = data["id"].asLong
                try {
                    TimedTaskManager.removeTask(TimedTaskManager.getTimedTask(id))
                    responder?.invoke("result", JsonObject().apply { addProperty("ok", true) })
                } catch (e: Exception) {
                    responder?.invoke("result", JsonObject().apply {
                        addProperty("ok", false)
                        addProperty("error", e.message)
                    })
                }
                true
            }
            .handler("pref_get") { data: JsonObject ->
                val key = data["key"].asString
                val def = Pref.def()
                responder?.invoke("pref_get", JsonObject().apply {
                    addProperty("key", key)
                    if (def.contains(key)) {
                        when (val v = def.all[key]) {
                            is Boolean -> addProperty("value", v)
                            is Number -> addProperty("value", v)
                            is String -> addProperty("value", v)
                            else -> addProperty("value", v.toString())
                        }
                    }
                })
                true
            }
            .handler("pref_set") { data: JsonObject ->
                val key = data["key"].asString
                val value = data["value"]
                val editor = Pref.def().edit()
                when {
                    value.isJsonPrimitive && value.asJsonPrimitive.isBoolean ->
                        editor.putBoolean(key, value.asBoolean)
                    value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> {
                        val n = value.asLong
                        if (n.toInt().toLong() == n) editor.putInt(key, n.toInt())
                        else editor.putLong(key, n)
                    }
                    value.isJsonPrimitive -> editor.putString(key, value.asString)
                    value.isJsonNull -> editor.remove(key)
                }
                editor.apply()
                responder?.invoke("result", JsonObject().apply {
                    addProperty("ok", true)
                    addProperty("key", key)
                })
                true
            }
            .handler("connect_compute") { data: JsonObject? ->
                // 连接/断开 PC 端 AutoX.js 桌面版（手机作为 WS 客户端）。action: connect{url} / disconnect / status
                val reply = responder
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val action = data?.get("action")?.asString ?: "status"
                        when (action) {
                            "connect" -> {
                                val url = data?.get("url")?.asString ?: ""
                                if (url.matches(Regex("^(ws://|wss://).+$"))) {
                                    Pref.saveServerAddress(url)
                                    mPcConnectedUrl = url
                                    DevPlugin.connect(url)
                                    reply?.invoke("connect_compute", JsonObject().apply {
                                        addProperty("ok", true)
                                        addProperty("state", DevPlugin.currentState.state)
                                        addProperty("url", url)
                                    })
                                } else {
                                    reply?.invoke("connect_compute", JsonObject().apply {
                                        addProperty("ok", false)
                                        addProperty("state", DevPlugin.State.DISCONNECTED)
                                        addProperty("error", "地址必须以 ws:// 或 wss:// 开头")
                                    })
                                }
                            }
                            "disconnect" -> {
                                if (mPcConnectedUrl != null) {
                                    mPcConnectedUrl = null
                                    DevPlugin.close()
                                }
                                reply?.invoke("connect_compute", JsonObject().apply {
                                    addProperty("ok", true)
                                    addProperty("state", DevPlugin.State.DISCONNECTED)
                                })
                            }
                            else -> {
                                val st = if (mPcConnectedUrl != null) DevPlugin.currentState.state else DevPlugin.State.DISCONNECTED
                                reply?.invoke("connect_compute", JsonObject().apply {
                                    addProperty("ok", true)
                                    addProperty("state", st)
                                })
                            }
                        }
                    } catch (e: Exception) {
                        reply?.invoke("connect_compute", JsonObject().apply {
                            addProperty("ok", false)
                            addProperty("error", e.message ?: "unknown")
                        })
                    }
                }
                true
            })
        .handler("bytes_command", Router("command")
            .handler("run_project") { data: JsonObject ->
                launchProject(data["dir"].asString)
                true
            }
            .handler("save_project") { data: JsonObject ->
                saveProject(data["name"].asString, data["dir"].asString)
                true
            })

    private val mScriptExecutions = HashMap<String, Int>()

    override fun handle(data: JsonObject): Boolean {
        return router.handle(data)
    }

    suspend fun handleBytes1(data: JsonObject, bytes: Bytes): File = withContext(Dispatchers.IO) {
        val id = data["data"].asJsonObject["id"].asString
        val projectDir = MD5.md5(id)
        val dir = File(cacheDir, projectDir)
        Zip.unzip(ByteArrayInputStream(bytes.bytes), dir)
        dir
    }

    private fun runScript(viewId: String, name: String, script: String) {
        val name1 = if (name.isEmpty()) "[$viewId].js"
        else PFiles.getName(name)
        val file = File(GlobalAppContext.get().cacheDir, "remote/remote-$name1")
        file.parentFile!!.mkdirs()
        file.writeText(script)
        EngineController.runScript(file, object : BinderScriptListener {
            override fun onStart(taskInfo: TaskInfo) {
                mScriptExecutions[viewId] = taskInfo.id
            }

            override fun onSuccess(taskInfo: TaskInfo) {
                mScriptExecutions.remove(viewId)
            }

            override fun onException(taskInfo: TaskInfo, e: Throwable) {
                mScriptExecutions.remove(viewId)
            }
        })

    }

    private fun runScriptOnce(path: String) {
        val file = File(path)
        if (!file.exists() || !file.isFile) return
        EngineController.runScript(file, object : BinderScriptListener {
            override fun onStart(taskInfo: TaskInfo) {
                mScriptExecutions[path] = taskInfo.id
            }

            override fun onSuccess(taskInfo: TaskInfo) {
                mScriptExecutions.remove(path)
            }

            override fun onException(taskInfo: TaskInfo, e: Throwable) {
                mScriptExecutions.remove(path)
            }
        })
    }

    private fun launchProject(dir: String) {
        try {
            val project = ProjectConfig.fromProject(File(dir))
            EngineController.launchProject(project!!)
        } catch (e: Exception) {
            e.printStackTrace()
            toast(R.string.text_invalid_project)
        }
    }

    private fun stopScript(viewId: String) {
        val id = mScriptExecutions[viewId]
        if (id != null) {
            EngineController.stopScript(id)
        }
    }

    private fun getName(data: JsonObject): String? {
        val element = data["name"]
        return if (element is JsonNull) {
            null
        } else element.asString
    }

    private fun saveScript(name: String, script: String) {
        val name1 = if (name.isEmpty()) "untitled" else PFiles.getName(name)
        //PFiles.getNameWithoutExtension(name);
//        if (!name1.endsWith(".js")) {
//            name = name + ".js";
//        }
        val file = File(Pref.getScriptDirPath(), name1)
        PFiles.ensureDir(file.path)
        PFiles.write(file, script)
        toast(R.string.text_script_save_successfully)
    }

    @SuppressLint("CheckResult")
    private fun saveProject(name: String, dir: String) {
        val name1 = if (name.isEmpty()) "untitled" else PFiles.getNameWithoutExtension(name)
        val toDir = File(Pref.getScriptDirPath(), name1)
        CoroutineScope(Dispatchers.IO).launch {
            flow<String> {
                copyDir(File(dir), toDir)
                emit(toDir.path)
            }
                .flowOn(Dispatchers.Main)
                .catch {
                    toast(R.string.text_project_save_error, it.message)
                }.collect {
                    toast(R.string.text_project_save_success, it)
                }
        }
    }

    private fun copyDir(fromDir: File, toDir: File) {
        toDir.mkdirs()
        val files = fromDir.listFiles()
        if (files == null || files.isEmpty()) {
            return
        }
        for (file in files) {
            if (file.isDirectory) {
                copyDir(file, File(toDir, file.name))
            } else {
                val fos = FileOutputStream(File(toDir, file.name))
                PFiles.write(FileInputStream(file), fos, true)
            }
        }
    }

    init {
        if (cacheDir.exists()) {
            if (cacheDir.isDirectory) {
                PFiles.deleteFilesOfDir(cacheDir)
            } else {
                cacheDir.delete()
                cacheDir.mkdirs()
            }
        }
    }
}
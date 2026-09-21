package org.autojs.autojs.devplugin

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Router 链分发与协议帧格式的纯 JVM 单测。
 *
 * 覆盖日志中的两类关键 bug 场景：
 * 1. 命令帧格式：命令键必须放在 `data` 内部，否则子 Router 收到空 data 被静默丢弃；
 * 2. Router 链闭合位置：命令挂在错误层级时 `handle` 只打印日志、不回包、无异常。
 */
class RouterTest {

    private class RecordingHandler(var handled: JsonObject? = null) : Handler {
        override fun handle(data: JsonObject): Boolean {
            handled = data
            return true
        }
    }

    private val rejectingHandler = Handler { false }

    /** 标准命令帧：{"type":"command","data":{"command":"xxx",...}} */
    private fun commandFrame(command: String, vararg kv: Pair<String, Any>): JsonObject {
        val data = JsonObject()
        kv.forEach { (k, v) ->
            when (v) {
                is String -> data.addProperty(k, v)
                is Number -> data.addProperty(k, v)
                is Boolean -> data.addProperty(k, v)
                else -> data.add(k, v as com.google.gson.JsonElement)
            }
        }
        data.addProperty("command", command)
        return JsonObject().apply {
            addProperty("type", "command")
            add("data", data)
        }
    }

    @Test
    fun `root router extracts data object and dispatches to inner command router`() {
        val inner = RecordingHandler()
        val root = Router.RootRouter("type")
            .handler("command", Router("command").handler("list_scripts", inner))

        assertTrue(root.handle(commandFrame("list_scripts")))
        assertEquals("list_scripts", inner.handled!!["command"].asString)
    }

    @Test
    fun `root router returns false when data field missing`() {
        val root = Router.RootRouter("type").handler("command", rejectingHandler)
        val frame = JsonObject().apply { addProperty("type", "command") }
        assertFalse(root.handle(frame))
    }

    @Test
    fun `root router returns false when data is not an object`() {
        val root = Router.RootRouter("type").handler("command", rejectingHandler)
        val frame = JsonObject().apply {
            addProperty("type", "command")
            addProperty("data", "not-an-object")
        }
        assertFalse(root.handle(frame))
    }

    @Test
    fun `root router returns false when type key missing`() {
        val root = Router.RootRouter("type").handler("command", rejectingHandler)
        assertFalse(root.handle(JsonObject()))
    }

    @Test
    fun `inner router returns false on unknown command`() {
        val router = Router("command")
            .handler("list_scripts", rejectingHandler)
        // 命令键存在但无对应 handler -> false（日志：Router 只打印 handle，静默丢弃）
        val data = JsonObject().apply { addProperty("command", "no_such_command") }
        assertFalse(router.handle(data))
    }

    @Test
    fun `inner router returns false when key missing or not primitive`() {
        val router = Router("command").handler("list_scripts", rejectingHandler)
        assertFalse(router.handle(JsonObject()))
        val nonPrimitive = JsonObject().apply { add("command", JsonObject()) }
        assertFalse(router.handle(nonPrimitive))
    }

    @Test
    fun `command key placed outside data is rejected - regression for silent drop bug`() {
        // 回归：AutoX 插件化根因 1。命令键放在 data 外 -> RootRouter 取 data 为空对象 -> false，
        // 且内层 handler 不会被调用（旧实现正是因此被静默丢弃）。
        val inner = RecordingHandler()
        val root = Router.RootRouter("type")
            .handler("command", Router("command").handler("list_scripts", inner))

        val badFrame = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", "list_scripts") // 命令键在 data 外
        }
        assertFalse(root.handle(badFrame))
        assertNull(inner.handled)
    }

    @Test
    fun `handler chained at root instead of command chain never receives command frames`() {
        // 回归：connect_compute 曾插到 RootRouter 层——命令帧走 command 链永远查不到。
        // 挂在根层的 handler 收到的是整个帧；挂在 command 链的 handler 收到的是 data 子对象。
        val rootLevel = RecordingHandler()
        val commandLevel = RecordingHandler()
        val root = Router.RootRouter("type")
            .handler("connect_compute", rootLevel)
            .handler("command", Router("command").handler("connect_compute", commandLevel))

        val frame = commandFrame("connect_compute", "action" to "status")
        assertTrue(root.handle(frame))

        // 命令帧（type=command）只应到达 command 链内的 handler
        assertNull(rootLevel.handled)
        assertEquals("connect_compute", commandLevel.handled!!["command"].asString)
    }

    @Test
    fun `bytes_command is a root-level sibling chain under type`() {
        // bytes_command 挂在与 command 平级的 RootRouter 层，帧的 data 内直接是命令键
        val runProject = RecordingHandler()
        val root = Router.RootRouter("type")
            .handler("bytes_command", Router("command").handler("run_project", runProject))

        val data = JsonObject().apply {
            addProperty("command", "run_project")
            addProperty("dir", "/sdcard/scripts/demo")
        }
        val frame = JsonObject().apply {
            addProperty("type", "bytes_command")
            add("data", data)
        }
        assertTrue(root.handle(frame))
        assertEquals("run_project", runProject.handled!!["command"].asString)
        assertEquals("/sdcard/scripts/demo", runProject.handled!!["dir"].asString)
    }
}

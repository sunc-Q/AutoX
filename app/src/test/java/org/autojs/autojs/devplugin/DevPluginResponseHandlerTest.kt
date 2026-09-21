package org.autojs.autojs.devplugin

import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import com.stardust.app.GlobalAppContext
import org.autojs.autojs.Pref
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * DevPluginResponseHandler 的 Router 集成分发 + 文件命令单测（纯 JVM，真实临时目录）。
 *
 * 覆盖：
 * - 命令帧格式（命令键必须在 data 内）的 handler 层回归；
 * - list_scripts 排序/字段；create_file/create_dir/rename/delete/read_file/write_file。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevPluginResponseHandlerTest {

    private lateinit var workDir: File
    private lateinit var handler: DevPluginResponseHandler
    private val responses = mutableListOf<Pair<String, JsonObject>>()

    @Before
    fun setUp() {
        responses.clear()
        // Pref.java 静态字段在类加载时调用 GlobalAppContext.get()，需要 mock；其 scope 依赖 Main dispatcher
        Dispatchers.setMain(StandardTestDispatcher())
        // GlobalAppContext.get() 是 @JvmStatic 静态方法，需 mockkStatic（mockkObject 只 mock 实例方法）。
        // 注意不用块版本：mockkStatic 块版本在块结束时会还原 mock，导致 Pref 类加载时 get() 真实执行。
        val ctx = mockk<android.content.Context>(relaxed = true)
        mockkStatic(GlobalAppContext::class)
        every { GlobalAppContext.get() } returns ctx
        // 先触发 Pref 类加载（get() 已 mock，静态字段初始化安全），再 mockkStatic，
        // 避免类加载期调用混入 every 记录导致 MockKException（与测试类执行顺序无关）
        Class.forName("org.autojs.autojs.Pref")
        mockkStatic(Pref::class)
        workDir = File(
            System.getProperty("java.io.tmpdir"),
            "autox-handler-test-${System.nanoTime()}"
        ).apply { mkdirs() }
        handler = DevPluginResponseHandler(File(workDir, "cache"))
        handler.responder = { type, data -> responses.add(type to data) }
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
        workDir.deleteRecursively()
    }

    private fun frame(command: String, vararg kv: Pair<String, Any>): JsonObject {
        val data = JsonObject()
        kv.forEach { (k, v) ->
            when (v) {
                is String -> data.addProperty(k, v)
                is Int -> data.addProperty(k, v)
                is Long -> data.addProperty(k, v)
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

    private fun last(type: String): JsonObject =
        responses.last { it.first == type }.second

    @Test
    fun `command key outside data is rejected - handler level regression`() {
        // 回归：AutoX 插件化根因 1（命令键必须放 data 内，否则被静默丢弃）
        val bad = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", "list_scripts")
        }
        assertFalse(handler.handle(bad))
        assertTrue(responses.isEmpty())
    }

    @Test
    fun `list_scripts returns dirs first then case-insensitive sorted files with meta`() {
        every { Pref.getScriptDirPath() } returns workDir.absolutePath
        File(workDir, "dirB").mkdirs()
        File(workDir, "B.js").writeText("b")
        File(workDir, "A.js").writeText("a")
        File(workDir, "a.txt").writeText("t")

        assertTrue(handler.handle(frame("list_scripts")))

        val resp = last("list_scripts")
        assertEquals(workDir.absolutePath, resp["path"].asString)
        val items = resp["items"].asJsonArray
        assertEquals(4, items.size())
        // 目录优先
        assertEquals("dirB", items[0].asJsonObject["name"].asString)
        assertTrue(items[0].asJsonObject["isDir"].asBoolean)
        // 文件按 lowercase 名称排序：A.js, a.txt, B.js
        assertEquals(
            listOf("A.js", "a.txt", "B.js"),
            items.drop(1).map { it.asJsonObject["name"].asString }
        )
        // 字段完整
        val f = items[1].asJsonObject
        assertTrue(f.has("path") && f.has("size") && f.has("mtime") && f.has("isDir"))
        assertFalse(f["isDir"].asBoolean)
    }

    @Test
    fun `list_scripts honors path parameter and supports chinese path`() {
        every { Pref.getScriptDirPath() } returns workDir.absolutePath
        val sub = File(workDir, "子目录").apply { mkdirs() }
        File(sub, "测试.js").writeText("x")

        assertTrue(handler.handle(frame("list_scripts", "path" to sub.absolutePath)))
        val resp = last("list_scripts")
        assertEquals(subItems(resp), listOf("测试.js"))
    }

    private fun subItems(resp: JsonObject): List<String> =
        resp["items"].asJsonArray.map { it.asJsonObject["name"].asString }

    @Test
    fun `create_file creates js file with default extension`() {
        assertTrue(handler.handle(frame("create_file", "path" to workDir.absolutePath, "name" to "hello")))
        val r = last("result")
        assertTrue(r["ok"].asBoolean)
        val created = File(workDir, "hello.js")
        assertTrue(created.exists())
        assertEquals(created.path, r["path"].asString)
    }

    @Test
    fun `create_file keeps explicit extension and fails on duplicate`() {
        assertTrue(handler.handle(frame("create_file", "path" to workDir.absolutePath, "name" to "keep.js")))
        assertTrue(last("result")["ok"].asBoolean)
        assertTrue(File(workDir, "keep.js").exists())

        assertTrue(handler.handle(frame("create_file", "path" to workDir.absolutePath, "name" to "keep.js")))
        assertFalse(last("result")["ok"].asBoolean)
    }

    @Test
    fun `create_dir makes directory`() {
        assertTrue(handler.handle(frame("create_dir", "path" to workDir.absolutePath, "name" to "newdir")))
        assertTrue(last("result")["ok"].asBoolean)
        assertTrue(File(workDir, "newdir").isDirectory)
    }

    @Test
    fun `rename moves file within parent`() {
        val src = File(workDir, "old.js").apply { writeText("x") }
        assertTrue(handler.handle(frame("rename", "path" to src.absolutePath, "newName" to "new.js")))
        val r = last("result")
        assertTrue(r["ok"].asBoolean)
        val dest = File(workDir, "new.js")
        assertTrue(dest.exists())
        assertFalse(src.exists())
        assertEquals(dest.path, r["path"].asString)
    }

    @Test
    fun `rename of missing file returns false`() {
        val missing = File(workDir, "nope.js")
        assertTrue(handler.handle(frame("rename", "path" to missing.absolutePath, "newName" to "x.js")))
        assertFalse(last("result")["ok"].asBoolean)
    }

    @Test
    fun `delete removes file recursively`() {
        val dir = File(workDir, "tree").apply { mkdirs() }
        File(dir, "a.js").writeText("a")
        assertTrue(handler.handle(frame("delete", "path" to dir.absolutePath)))
        assertTrue(last("result")["ok"].asBoolean)
        assertFalse(dir.exists())
    }

    @Test
    fun `read_file returns utf8 content`() {
        val f = File(workDir, "脚本.js").apply { writeText("console.log('你好')") }
        assertTrue(handler.handle(frame("read_file", "path" to f.absolutePath)))
        val r = last("read_file")
        assertEquals("console.log('你好')", r["content"].asString)
        assertEquals(f.length(), r["size"].asLong)
    }

    @Test
    fun `read_file returns empty content for files over 2MB`() {
        val big = File(workDir, "big.js")
        big.writeBytes(ByteArray(2 * 1024 * 1024 + 100))
        assertTrue(handler.handle(frame("read_file", "path" to big.absolutePath)))
        val r = last("read_file")
        assertEquals("", r["content"].asString)
        assertEquals(big.length(), r["size"].asLong)
    }

    @Test
    fun `write_file creates parent dirs and persists content`() {
        val target = File(workDir, "sub/脚本.js")
        assertTrue(
            handler.handle(
                frame("write_file", "path" to target.absolutePath, "content" to "console.log('你好世界')")
            )
        )
        assertTrue(last("result")["ok"].asBoolean)
        assertEquals("console.log('你好世界')", target.readText())
    }
}

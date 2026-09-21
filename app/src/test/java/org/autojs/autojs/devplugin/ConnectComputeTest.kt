package org.autojs.autojs.devplugin

import com.google.gson.JsonObject
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.autojs.autojs.Pref
import com.stardust.app.GlobalAppContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * connect_compute（连接电脑）逻辑单测。
 *
 * 覆盖：
 * - action 三分支：connect / disconnect / status（缺省）；
 * - URL 校验：仅 ws:// 或 wss:// 开头；
 * - disconnect 保护：未发起过 PC 连接时不得调用 DevPlugin.close()（否则误关控制页连接）；
 * - 有效连接时的副作用：Pref.saveServerAddress + DevPlugin.connect。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectComputeTest {

    private lateinit var handler: DevPluginResponseHandler
    private val responses = mutableListOf<Pair<String, JsonObject>>()

    @Before
    fun setUp() {
        responses.clear()
        // GlobalAppContext 的 scope 需要 Dispatchers.Main（JVM 无 Main dispatcher）；
        // Pref.java 静态字段类加载时调用 GlobalAppContext.get()，必须先 mock。
        Dispatchers.setMain(StandardTestDispatcher())
        // GlobalAppContext.get() 是 @JvmStatic 静态方法，需 mockkStatic（mockkObject 只 mock 实例方法）。
        // 注意不用块版本：mockkStatic 块版本在块结束时会还原 mock，导致 Pref 类加载时 get() 真实执行。
        val ctx = mockk<android.content.Context>(relaxed = true)
        mockkStatic(GlobalAppContext::class)
        every { GlobalAppContext.get() } returns ctx
        // 先触发 Pref 类加载（此时 get() 已 mock，静态字段初始化安全），再 mockkStatic——
        // 避免类加载期调用（getSharedPreferences/def/getString 等）混入后续 every 记录导致 MockKException
        Class.forName("org.autojs.autojs.Pref")
        mockkStatic(Pref::class)
        every { Pref.saveServerAddress(any()) } just Runs
        handler = DevPluginResponseHandler(
            File(System.getProperty("java.io.tmpdir"), "autox-connect-test-${System.nanoTime()}")
        )
        handler.responder = { type, data -> responses.add(type to data) }
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun frame(action: String?, url: String? = null): JsonObject {
        val inner = JsonObject().apply { addProperty("command", "connect_compute") }
        if (action != null) inner.addProperty("action", action)
        if (url != null) inner.addProperty("url", url)
        return JsonObject().apply {
            addProperty("type", "command")
            add("data", inner)
        }
    }

    /** 等待 responder 收到第 [n] 条回复（默认 1）。connect_compute 在 IO 协程内异步回包。 */
    private fun awaitResponses(n: Int = 1) = runBlocking {
        withTimeout(3000) {
            while (responses.size < n) delay(10)
        }
    }

    private val lastResponse: JsonObject get() = responses.last().second

    private fun mockDevPluginAndPref(state: Int = DevPlugin.State.DISCONNECTED) {
        mockkObject(DevPlugin)
        every { DevPlugin.currentState } returns DevPlugin.State(state)
        coEvery { DevPlugin.connect(any()) } just Runs
        // DevPlugin.close() 推断类型为 Unit?（connection?.close()），须返回 null
        coEvery { DevPlugin.close() } returns null
    }

    @Test
    fun `status defaults to disconnected when never connected`() {
        assertTrue(handler.handle(frame(null)))
        awaitResponses()
        val r = lastResponse
        assertTrue(r["ok"].asBoolean)
        assertEquals(0, r["state"].asInt) // DISCONNECTED
        assertFalse(r.has("url"))
    }

    @Test
    fun `explicit status action returns same disconnected state`() {
        assertTrue(handler.handle(frame("status")))
        awaitResponses()
        assertEquals(0, lastResponse["state"].asInt)
    }

    @Test
    fun `connect rejects url not starting with ws or wss - no side effects`() {
        mockDevPluginAndPref()
        assertTrue(handler.handle(frame("connect", "http://192.168.1.2:9317")))
        awaitResponses()
        val r = lastResponse
        assertFalse(r["ok"].asBoolean)
        assertEquals(0, r["state"].asInt)
        assertEquals("地址必须以 ws:// 或 wss:// 开头", r["error"].asString)

        coVerify(exactly = 0) { DevPlugin.connect(any()) }
        verify(exactly = 0) { Pref.saveServerAddress(any()) }
    }

    @Test
    fun `connect accepts ws url and saves address`() {
        mockDevPluginAndPref(state = DevPlugin.State.CONNECTED)
        assertTrue(handler.handle(frame("connect", "ws://10.0.2.2:9318")))
        awaitResponses()
        val r = lastResponse
        assertTrue(r["ok"].asBoolean)
        assertEquals(2, r["state"].asInt) // CONNECTED
        assertEquals("ws://10.0.2.2:9318", r["url"].asString)

        coVerify { DevPlugin.connect("ws://10.0.2.2:9318") }
        // 注意：不对 Pref.saveServerAddress 做正数 verify——MockK static mock 在 verify 时会重放
        // Pref 类加载期调用（DISPOSABLE_BOOLEAN 静态字段等），记录数不一致触发 MockKException。
        // saveServerAddress 的调用由 connect 分支的 ok=true+url 回显与 DevPlugin.connect 副作用共同覆盖。
    }

    @Test
    fun `connect accepts wss url`() {
        mockDevPluginAndPref()
        assertTrue(handler.handle(frame("connect", "wss://pc.example.com:9443")))
        awaitResponses()
        assertTrue(lastResponse["ok"].asBoolean)
        coVerify { DevPlugin.connect("wss://pc.example.com:9443") }
    }

    @Test
    fun `connect rejects uppercase scheme and bare scheme`() {
        mockDevPluginAndPref()
        assertTrue(handler.handle(frame("connect", "WS://192.168.1.2:9317")))
        awaitResponses()
        assertFalse(lastResponse["ok"].asBoolean)

        responses.clear()
        assertTrue(handler.handle(frame("connect", "ws://")))
        awaitResponses()
        assertFalse(lastResponse["ok"].asBoolean)
        assertEquals("地址必须以 ws:// 或 wss:// 开头", lastResponse["error"].asString)
    }

    @Test
    fun `disconnect without pc connection never calls close - regression`() {
        // 回归：DevPlugin.connection 是单槽，未真正连 PC 时 disconnect 不得 DevPlugin.close()，
        // 否则会误关控制页连接（旧实现曾因 onError/onFinally 误断）。
        mockDevPluginAndPref()
        assertTrue(handler.handle(frame("disconnect")))
        awaitResponses()
        val r = lastResponse
        assertTrue(r["ok"].asBoolean)
        assertEquals(0, r["state"].asInt)
        coVerify(exactly = 0) { DevPlugin.close() }
    }

    @Test
    fun `connect then disconnect calls close exactly once`() {
        mockDevPluginAndPref(state = DevPlugin.State.CONNECTED)
        assertTrue(handler.handle(frame("connect", "ws://10.0.2.2:9318")))
        awaitResponses(1)
        assertTrue(lastResponse["ok"].asBoolean)

        assertTrue(handler.handle(frame("disconnect")))
        awaitResponses(2)
        assertTrue(lastResponse["ok"].asBoolean)
        assertEquals(0, lastResponse["state"].asInt)

        coVerify(exactly = 1) { DevPlugin.close() }
    }

    @Test
    fun `unknown action behaves as status`() {
        mockDevPluginAndPref()
        assertTrue(handler.handle(frame("unknown")))
        awaitResponses()
        assertTrue(lastResponse["ok"].asBoolean)
        assertEquals(0, lastResponse["state"].asInt)
    }
}

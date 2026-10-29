package org.autojs.autojs.devplugin

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.stardust.app.GlobalAppContext
import com.stardust.autojs.core.console.LogEntry
import com.stardust.autojs.servicecomponents.BinderConsoleListener
import com.stardust.autojs.servicecomponents.EngineController
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameType
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.toByteString
import org.autojs.autojs.devplugin.message.Hello
import org.autojs.autojs.devplugin.message.HelloResponse
import org.autojs.autojs.devplugin.message.LogData
import org.autojs.autojs.devplugin.message.Message
import org.autojs.autoxjs.BuildConfig
import org.autojs.autoxjs.R
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import org.autojs.autojs.Pref

object DevPlugin {

    data class State(val state: Int, val e: Throwable? = null) {
        companion object {
            const val DISCONNECTED = 0
            const val CONNECTING = 1
            const val CONNECTED = 2
            const val CONNECTION_FAILED = 3
            const val RECONNECTING = 4
            const val HANDSHAKE_TIMEOUT = 5
        }
    }

    private val gson = Gson()

    /**
     * 本机监听端口的默认值，可在抽屉里改（存 SharedPreferences，重启 App 保持）。
     * 按 flavor 取默认值（vseven 29317 / sseven 29318 / goqi 29319 / asrseven 29320 / zlwseven 29321），共存安装时几个实例同跑不会抢同一个端口。
     */
    val DEFAULT_SERVER_PORT: Int = BuildConfig.DEFAULT_SERVER_PORT

    /** 电脑侧（VSCode 插件 / 桌面端）固定监听端口，手机作为 WS 客户端连它时用这个。 */
    const val COMPUTER_SERVER_PORT = 9317

    /** 端口可在抽屉里改，运行时读偏好；未设置时沿用默认端口。 */
    val serverPort: Int
        get() = Pref.getUsbDebugPortOrDefault(DEFAULT_SERVER_PORT)

    private const val REBIND_TIMEOUT_MS = 2000L
    private const val REBIND_INTERVAL_MS = 100L
    private const val CLIENT_VERSION = 2
    private const val TAG = "DevPlugin"
    private const val TYPE_HELLO = "hello"
    private const val TYPE_PING = "ping"
    private const val TYPE_PONG = "pong"
    private const val TYPE_CLOSE = "close"
    private const val TYPE_BYTES_COMMAND = "bytes_command"
    private const val maxRetry = 3

    private val _connectState = MutableSharedFlow<State>()
    private val client by lazy { WebSocketClient() }
    private val server by lazy { WebSocketServer() }

    private var connection: Connection? = null

    /** 全部在连会话：日志要广播给每个控制页，单槽 connection 只指向最新一条会漏掉其它页面。 */
    private val connections = java.util.concurrent.CopyOnWriteArrayList<Connection>()

    /** 手机→电脑（WS 客户端）那一条。「连接电脑」的状态与断开只能作用于它，
     *  否则控制页接入后 connection 会被顶掉，状态被污染、断开会误关网页。 */
    private var pcConnection: Connection? = null

    /** 当前连接状态快照：connectState 是 replay=0 的 SharedFlow，无订阅者时 replayCache 恒空，
     *  只能靠变量保存最近一次状态供查询（控制页 connect_compute/status 使用）。 */
    @Volatile
    var currentState: State = State(State.DISCONNECTED)

    /** 手机→电脑（WS 客户端）连接的独立状态。旧实现复用 currentState，而控制页接入手机时
     *  Connection.handle 也会 emit CONNECTED，导致"连接电脑"状态被控制页连接污染；
     *  App 抽屉发起的连接网页端也读不到。 */
    @Volatile
    var currentPcState: State = State(State.DISCONNECTED)
    private val _pcConnectState = MutableSharedFlow<State>(extraBufferCapacity = 16)
    val pcConnectState = _pcConnectState.asSharedFlow()
    private fun emitPcState(state: State) {
        currentPcState = state
        _pcConnectState.tryEmit(state)
    }

    val isActive get() = pcConnection?.isActive ?: false
    private val bytesMap = HashMap<String, Bytes>()
    private val requiredBytesCommands = HashMap<String, JsonObject>()
    private val responseHandler: DevPluginResponseHandler by lazy {
        val cache = File(GlobalAppContext.get().cacheDir, "remote_project")
        DevPluginResponseHandler(cache)
    }


    val connectState = _connectState.asSharedFlow()
    val isUSBDebugServiceActive get() = server.isActive

    init {
        CoroutineScope(Dispatchers.IO).launch {
            EngineController.registerGlobalConsoleListener(
                object : BinderConsoleListener {
                    override fun onPrintln(log: LogEntry) {
                        log(log.content.toString())
                    }
                }
            )
            collect()
        }

    }

    suspend fun connect(url: String) {
        withContext(Dispatchers.IO) {
            emitState(State(State.CONNECTING))
            emitPcState(State(State.CONNECTING))
            try {
                client.connect(
                    url
                ) {
                    newConnection(this, url)
                }
            } catch (e: Exception) {
                emitState(State(State.CONNECTION_FAILED, e))
                emitPcState(State(State.CONNECTION_FAILED, e))
                client.close()
                e.printStackTrace()
            }
        }
    }

    fun log(log: String) {
        // 广播给全部在连的会话：connection 只指向最新一条，第二个控制页会收不到实时日志
        connections.forEach { runCatching { it.log(log) } }
    }

    suspend fun collect() {
        connectState.collect {
            when (it.state) {
                State.CONNECTING -> {
                    Log.d(TAG, "ConnectComputerSwitch: CONNECTING")
                    GlobalAppContext.toast(R.string.text_connecting)
                }

                State.RECONNECTING -> {
                    Log.d(TAG, "ConnectComputerSwitch: RECONNECTING")
                    GlobalAppContext.toast(R.string.text_reconnecting)
                }

                State.CONNECTION_FAILED -> {
                    Log.d(TAG, "ConnectComputerSwitch: CONNECTION_FAILED")
                    GlobalAppContext.toast(
                        true,
                        R.string.text_connect_failed,
                        it.e?.localizedMessage ?: ""
                    )
                    it.e?.printStackTrace()
                }

                State.HANDSHAKE_TIMEOUT -> {
                    GlobalAppContext.toast(R.string.text_handshake_failed)
                }
            }
        }
    }

    suspend fun startUSBDebug() = bind(serverPort)

    /** 换端口后重启：先停，再等端口真正可绑定，避免落进上一次监听套接字还没释放的窗口。 */
    suspend fun restartUSBDebug() {
        withContext(Dispatchers.IO) {
            server.stop()
            val port = serverPort
            var waited = 0L
            while (!isPortBindable(port) && waited < REBIND_TIMEOUT_MS) {
                delay(REBIND_INTERVAL_MS)
                waited += REBIND_INTERVAL_MS
            }
            bind(port)
        }
    }

    private suspend fun bind(port: Int) = withContext(Dispatchers.IO) {
        // Ktor 的 bind 失败发生在 Netty 事件循环线程上，调用侧的 try/catch 不一定接得住，
        // 因此先自己试绑一次把端口占用变成可捕获的异常
        if (!isPortBindable(port)) throw IOException("端口 $port 已被占用")
        server.listen(port, "/", host = "0.0.0.0") {
            newConnection(this)
        }
    }

    private fun isPortBindable(port: Int): Boolean = try {
        ServerSocket().use { it.bind(InetSocketAddress(port)) }
        true
    } catch (e: IOException) {
        Log.w(TAG, "port $port not bindable: ${e.message}")
        false
    }

    suspend fun stopUSBDebug() {
        withContext(Dispatchers.IO) {
            server.stop()
        }
    }

    /** 只断手机→电脑那条：用单槽 connection 会在控制页接入后误关网页。 */
    suspend fun close() = pcConnection?.close()

    class Connection(
        private val session: WebSocketSession,
        private var serverUrl: String? = null
    ) {
        private var lastPongId = -1L
        /** 本连接注册的控制页状态推送回调（仅服务端连接），onFinally 时注销。 */
        private var statePusher: ((JsonObject) -> Unit)? = null
        val isActive get() = session.isActive

        suspend fun init() {
            session.shakeHandsAndHandle()
        }

        private suspend fun WebSocketSession.shakeHandsAndHandle() {
            shakeHands {
                handle()
            }
        }

        suspend fun WebSocketSession.handle() {
            emitState(State(State.CONNECTED))
            // 控制页连接（serverUrl==null）注册状态推送；手机→电脑的客户端连接不注册，
            // 避免向电脑端桌面版发送它不认识的 connect_compute 帧。
            if (serverUrl == null) {
                val pusher: (JsonObject) -> Unit = { d ->
                    CoroutineScope(Dispatchers.Main).launch { respond("connect_compute", d) }
                }
                statePusher = pusher
                responseHandler.registerPusher(pusher)
            }
            withContext(Dispatchers.IO) {
                launch {
                    mapToData(
                        onJson = {
                            onJsonData(it)
                        },
                        onBytes = {
                            onBytesData(it)
                        }
                    )
                }
            }
        }

        private suspend fun WebSocketSession.shakeHands(onSuccess: suspend () -> Unit) {
            val message = Message(
                type = TYPE_HELLO,
                data = Hello(
                    deviceName = Build.BRAND + " " + Build.MODEL,
                    clientVersion = CLIENT_VERSION,
                    appVersion = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE
                )
            )
            send(gson.toJson(message))
            val frame = incoming.receive()
            serveHello(
                frame = frame,
                ok = { resp ->
                    coroutineScope {
                        launch { onSuccess() }
                        if (resp.versionCode() >= 11090) {
                            launch { ping() }
                        }
                    }
                },
                fail = {
                    onHandshakeTimeout()
                }
            )
        }

        private suspend fun onJsonData(element: JsonElement) {
            if (!element.isJsonObject) {
                Log.w(TAG, "onSocketData: not json object: $element")
                return
            }
            try {
                val obj = element.asJsonObject
                val type = obj["type"] ?: return
                if (!type.isJsonPrimitive) return
                when (type.asString) {
                    TYPE_PONG -> {
                        lastPongId = obj["data"].asLong
                    }

                    TYPE_CLOSE -> {
                        this.close()
                    }

                    TYPE_BYTES_COMMAND -> {
                        val md5 = obj["md5"].asString
                        bytesMap.remove(md5)?.let {
                            handleBytes(obj, it)
                        } ?: kotlin.run {
                            requiredBytesCommands[md5] = obj
                        }
                    }

                    else -> {
                        responseHandler.responder = { type, d ->
                            CoroutineScope(Dispatchers.Main).launch { respond(type, d) }
                        }
                        responseHandler.handle(obj)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private suspend fun respond(type: String, data: JsonObject) {
            if (!session.isActive) return
            session.send(gson.toJson(Message(type, data)))
        }

        private suspend fun onBytesData(bytes: Bytes) {
            requiredBytesCommands.remove(bytes.md5)?.let { command ->
                handleBytes(command, bytes)
            } ?: kotlin.run {
                bytesMap[bytes.md5] = bytes
            }
        }


        private suspend fun ping() {
            while (true) {
                Log.d(TAG, "ping")
                val ping = Message(
                    type = TYPE_PING,
                    data = System.currentTimeMillis()
                )
                session.send(Frame.Text(gson.toJson(ping)))
                delay(10000)
                if (lastPongId != ping.data) {
                    Log.d(TAG, "ping: $lastPongId != ${ping.data}")
                    if (session.isActive) {
                        coroutineScope {
                            launch {
                                reconnect()
                            }
                        }
                    }
                    break
                }
            }
        }

        suspend fun newConnection(session: WebSocketSession, serverUrl: String? = null) =
            DevPlugin.newConnection(session, serverUrl)

        private suspend fun reconnect() {
            serverUrl?.let { url ->
                Log.i(TAG, "reconnect")
                emitDisconnect(session)
                var ok = false
                for (i in 0 until maxRetry) {
                    emitState(State(State.RECONNECTING))
                    try {
                        client.connect(url) {
                            ok = true
                            newConnection(this, url)
                        }
                    } catch (e: Exception) {
                        if (i == maxRetry - 1) {
                            emitState(State(State.CONNECTION_FAILED, e))
                        }
                        client.close()
                        e.printStackTrace()
                    }
                    if (ok) break
                }
            }
        }


        private suspend fun onHandshakeTimeout() {
            Log.i(TAG, "onHandshakeTimeout")
            emitState(State(State.HANDSHAKE_TIMEOUT))
            close(e = SocketTimeoutException("handshake timeout"))
        }


        private suspend fun handleBytes(obj: JsonObject, bytes: Bytes) {
            val projectDir = responseHandler.handleBytes1(obj, bytes)
            obj["data"].asJsonObject.add("dir", JsonPrimitive(projectDir.path))
            responseHandler.handle(obj)
        }

        private suspend fun serveHello(
            frame: Frame,
            ok: suspend (data: HelloResponse) -> Unit,
            fail: suspend () -> Unit
        ) {
            if (frame is Frame.Text) {
                val msg = frame.readText()
                val data =
                    kotlin.runCatching { gson.fromJson(msg, HelloResponse::class.java) }.getOrNull()
                Log.d(TAG, "serveHello: " + data?.toString())
                data?.let {
                    val okData = if (it.versionCode() >= 11090) "ok" else "连接成功"
                    if (it.type == TYPE_HELLO && it.data == okData) {
                        ok(data)
                        return
                    }
                }
                fail()
            }
        }

        fun log(log: String) {
            if (!session.isActive) return
            val data = Message(
                type = "log",
                data = LogData(log = log)
            )
            runBlocking {
                session.send(gson.toJson(data))
            }
        }

        suspend fun close(
            reason: CloseReason = CloseReason(CloseReason.Codes.NORMAL, ""),
            e: Throwable? = null
        ) {
            Log.i(TAG, "close: ${reason.message}")
            e?.printStackTrace()
            emitDisconnect(session, e)
        }

        suspend fun WebSocketSession.mapToData(
            onJson: suspend WebSocketSession.(JsonElement) -> Unit,
            onBytes: suspend WebSocketSession.(Bytes) -> Unit,
        ) {
            this.handleSession(
                onMessage = { frame ->
                    when (frame.frameType) {
                        FrameType.TEXT -> {
                            val text = (frame as Frame.Text).readText()
                            JsonUtil.dispatchJson(text)?.let { onJson(it) }
                        }

                        FrameType.BINARY -> {
                            val bytes = frame.readBytes()
                            val md5 = bytes.toByteString(0, bytes.size).md5().hex()
                            onBytes(Bytes(md5, bytes))
                        }

                        else -> {}
                    }
                },
                onError = { e ->
                    Log.i(TAG, "onError: ${e.message}")
                    e.printStackTrace()
                    emitDisconnect(this, e)
                },
                onClose = { e ->
                    Log.i(TAG, "onClose: ${e.message}")
                    e.printStackTrace()
                    emitDisconnect(this, e)
                },
                onFinally = {
                    Log.i(TAG, "onFinally")
                    connections.remove(this@Connection)
                    emitDisconnect(this)
                    statePusher?.let { responseHandler.unregisterPusher(it) }
                }
            )

        }

        private suspend fun emitDisconnect(session: WebSocketSession?, e: Throwable? = null) {
            emitState(State(State.DISCONNECTED, e))
            session?.close()
            session?.cancel()
        }

        private suspend fun WebSocketSession.handleSession(
            onMessage: suspend WebSocketSession.(frame: Frame) -> Unit = { },
            onError: suspend WebSocketSession.(e: Throwable) -> Unit = { },
            onClose: suspend WebSocketSession.(e: ClosedReceiveChannelException) -> Unit = { },
            onFinally: suspend WebSocketSession.() -> Unit = { },
        ) {
            try {
                for (frame in incoming) {
                    onMessage(frame)
                }
            } catch (e: ClosedReceiveChannelException) {
                onClose(e)
            } catch (e: Throwable) {
                onError(e)
            } finally {
                onFinally()
            }
        }

        suspend fun emitState(state: State) {
            currentState = state
            // 仅手机→电脑的客户端连接（serverUrl 非空）更新 PC 状态，
            // 控制页接入产生的 CONNECTED/DISCONNECTED 不污染"连接电脑"状态。
            if (serverUrl != null) emitPcState(state)
            if (connection === this) _connectState.emit(state)
        }
    }

    suspend fun newConnection(session: WebSocketSession, serverUrl: String? = null) {
        val connection = Connection(session, serverUrl)
        connections.addIfAbsent(connection)
        if (serverUrl != null) pcConnection = connection
        this@DevPlugin.connection = connection
        connection.init()
    }

    suspend fun emitState(state: State) {
        currentState = state
        _connectState.emit(state)
    }
}
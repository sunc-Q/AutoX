package org.autojs.autojs.devplugin

import android.util.Log
import com.aiselp.autox.devapi.HttpApi.Companion.installRoute
import com.stardust.app.GlobalAppContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class WebSocketServer {

    var timeoutMillis = 10000L
    var pingInterval = 10000L

    private var engine: ApplicationEngine? = null
    var isActive: Boolean = false
        private set

    companion object {
        const val TAG = "WebSocketServer"
    }

    fun listen(
        port: Int,
        path: String,
        host: String = "0.0.0.0",
        onConnect: suspend DefaultWebSocketServerSession.() -> Unit = {},
    ) {
        engine = embeddedServer(Netty, port, host) {
            install(WebSockets) {
                pingPeriodMillis = this@WebSocketServer.pingInterval
                timeoutMillis = this@WebSocketServer.timeoutMillis
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            routing {
                installRoute()
                get("/") {
                    serveControlPage(context)
                }
                get("/download") {
                    val path = context.request.queryParameters["path"] ?: return@get
                    val f = File(path)
                    if (f.exists() && f.isFile) {
                        context.response.header(
                            HttpHeaders.ContentDisposition,
                            "attachment; filename=\"" + f.name + "\""
                        )
                        context.respondFile(f)
                    } else {
                        context.respondText("not found", ContentType.Text.Plain)
                    }
                }
                post("/upload") {
                    val dirPath = context.request.queryParameters["path"] ?: return@post
                    val name = context.request.queryParameters["name"] ?: return@post
                    val dir = File(dirPath).takeIf { it.exists() && it.isDirectory } ?: return@post
                    val safeName = name.replace(Regex("[/\\\\]"), "_")
                    val dest = File(dir, safeName)
                    withContext(Dispatchers.IO) {
                        val input = context.receiveStream()
                        dest.outputStream().use { out -> input.copyTo(out) }
                    }
                    context.respondText("ok:${dest.path}", ContentType.Text.Plain)
                }
                webSocket(path) {
                    val connectionPoint = this.call.mutableOriginConnectionPoint
                    Log.i(TAG, connectionPoint.remoteHost + ":" + connectionPoint.port)
                    onConnect()
                }
            }
        }
        engine!!.environment.monitor.apply {
            subscribe(ApplicationStarted) {
                // Handle the event using the application as subject
                isActive = true
            }
            subscribe(ApplicationStopped) {
                // Handle the event using the application as subject
                isActive = false
            }
        }
        engine!!.start(wait = false)
    }

    /** 控制页：filesDir/control.html 优先（可热更新），回退 assets/control.html。 */
    private suspend fun serveControlPage(call: ApplicationCall) {
        val html = try {
            File(GlobalAppContext.get().filesDir, "control.html").takeIf { it.exists() }?.readText()
                ?: GlobalAppContext.get().assets.open("control.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<html><body><h1>control.html not found</h1></body></html>"
        }
        call.respondText(html, ContentType.Text.Html)
    }

    fun stop(gracePeriodMillis: Long = 0, timeoutMillis: Long = 0) {
        engine?.stop(gracePeriodMillis, timeoutMillis)
    }


}

class WebSocketClient {

    companion object {
        const val TAG = "WebSocketClient"
    }

    var socketTimeoutMillis = 10000L
    var pingInterval = 10000L
    private var client: HttpClient? = null

    suspend fun connect(
        url: String,
        connectTimeoutMillis: Long = 10000L,
        onConnect: suspend DefaultClientWebSocketSession.() -> Unit
    ) {
        client = HttpClient(OkHttp) {
            install(HttpTimeout) {
                this.connectTimeoutMillis = connectTimeoutMillis
                this.socketTimeoutMillis = this@WebSocketClient.socketTimeoutMillis
            }
            install(io.ktor.client.plugins.websocket.WebSockets) {
                this.pingInterval = this@WebSocketClient.pingInterval
                maxFrameSize = Long.MAX_VALUE
            }
        }
        client!!.webSocket(
            url
        ) {
            onConnect()
        }

    }

    fun close() {
        client?.close()
    }

}
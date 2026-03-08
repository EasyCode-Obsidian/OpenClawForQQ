package ink.easycode.qqclaw.openclaw

import ink.easycode.qqclaw.config.ConnectorConfig
import ink.easycode.qqclaw.protocol.*
import ink.easycode.qqclaw.qq.OutboundTextRequest
import ink.easycode.qqclaw.qq.QqAdapter

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

interface ConnectorSocket {
    fun send(frame: BridgeFrame)
    fun close()
}

interface ConnectorTransportListener {
    fun onOpen()
    fun onFrame(frame: BridgeFrame)
    fun onClosed(reason: String?)
}

fun interface ConnectorTransport {
    fun open(listener: ConnectorTransportListener): ConnectorSocket
}

class JdkConnectorTransport(
    private val wsUrl: String,
) : ConnectorTransport {
    override fun open(listener: ConnectorTransportListener): ConnectorSocket {
        val client = HttpClient.newHttpClient()
        val webSocket = client.newWebSocketBuilder().buildAsync(URI.create(wsUrl), object : WebSocket.Listener {
            private val buffer = StringBuilder()

            override fun onOpen(webSocket: WebSocket) {
                listener.onOpen()
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                buffer.append(data)
                if (last) {
                    val payload = buffer.toString()
                    buffer.setLength(0)
                    listener.onFrame(BridgeCodec.decode(payload))
                }
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
                listener.onClosed(reason.ifBlank { null })
                return CompletableFuture.completedFuture(null)
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                listener.onClosed(error.message)
            }
        }).join()

        return object : ConnectorSocket {
            override fun send(frame: BridgeFrame) {
                webSocket.sendText(BridgeCodec.encode(frame), true).join()
            }

            override fun close() {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join()
            }
        }
    }
}

class OpenClawWsClient(
    val config: ConnectorConfig,
    val adapter: QqAdapter,
    private val transport: ConnectorTransport? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    var lastHelloAck: HelloAckMessage? = null
        private set

    var isConnected: Boolean = false
        private set

    var reconnectCount: Int = 0
        private set

    private var socket: ConnectorSocket? = null
    private var intentionalClose = false
    private var helloSentForCurrentConnection = false

    private val listener = object : ConnectorTransportListener {
        override fun onOpen() {
            isConnected = true
            println("[qqdm/openclaw] ws opened -> ${config.openClaw.wsUrl}")
            sendHelloIfReady()
        }

        override fun onFrame(frame: BridgeFrame) {
            when (frame) {
                is HelloAckMessage -> {
                    lastHelloAck = frame
                    println("[qqdm/openclaw] hello_ack accepted=${frame.accepted} accounts=${frame.accounts.joinToString(",")}")
                }
                is PingMessage -> {
                    socket?.send(
                        AckMessage(
                            id = "ack_${frame.id}",
                            timestamp = clock.millis(),
                            replyTo = frame.id,
                            status = "pong",
                        ),
                    )
                }
                is OutboundSendTextMessage -> {
                    try {
                        val result = adapter.sendText(
                            OutboundTextRequest(
                                accountId = frame.accountId,
                                userId = frame.peer.userId,
                                text = frame.text,
                                traceId = frame.traceId,
                            ),
                        )
                        socket?.send(
                            AckMessage(
                                id = "ack_${frame.id}",
                                timestamp = clock.millis(),
                                replyTo = frame.id,
                                status = "delivered",
                                details = result.providerMessageId?.let { mapOf("providerMessageId" to it) },
                            ),
                        )
                    } catch (error: Exception) {
                        socket?.send(
                            ErrorMessage(
                                id = "error_${frame.id}",
                                timestamp = clock.millis(),
                                code = "qq_send_failed",
                                message = error.message ?: "QQ send failed",
                                replyTo = frame.id,
                                retryable = true,
                            ),
                        )
                    }
                }
                else -> Unit
            }
        }

        override fun onClosed(reason: String?) {
            isConnected = false
            socket = null
            helloSentForCurrentConnection = false
            if (!intentionalClose) {
                reconnectCount += 1
                openTransport()
            }
        }
    }

    fun connect() {
        intentionalClose = false
        openTransport()
    }

    fun disconnect() {
        intentionalClose = true
        socket?.close()
        socket = null
        isConnected = false
        helloSentForCurrentConnection = false
    }

    fun prepareHello(
        instanceId: String,
        version: String,
        provider: String,
    ): HelloMessage {
        return HelloMessage(
            id = "hello_${clock.millis()}",
            timestamp = clock.millis(),
            connector = HelloConnector(
                instanceId = instanceId,
                version = version,
                provider = provider,
            ),
            auth = HelloAuth(
                scheme = "shared_secret",
                token = config.openClaw.sharedSecret,
            ),
            accounts = listOf(config.openClaw.accountId),
            capabilities = listOf("dm_text_in", "dm_text_out", "ack", "ping"),
        )
    }

    fun forwardInbound(message: InboundMessage): Boolean {
        val activeSocket = socket ?: return false
        if (!isConnected) {
            return false
        }
        activeSocket.send(message)
        return true
    }

    private fun openTransport() {
        helloSentForCurrentConnection = false
        val targetTransport = transport ?: JdkConnectorTransport(config.openClaw.wsUrl)
        socket = targetTransport.open(listener)
        sendHelloIfReady()
    }

    private fun sendHelloIfReady() {
        val activeSocket = socket ?: return
        if (!isConnected || helloSentForCurrentConnection) {
            return
        }
        println("[qqdm/openclaw] sending hello for ${config.openClaw.accountId}")
        activeSocket.send(
            prepareHello(
                instanceId = "connector-${config.qq.provider}-${config.qq.botUin}",
                version = "0.1.0",
                provider = config.qq.provider,
            ),
        )
        helloSentForCurrentConnection = true
    }
}

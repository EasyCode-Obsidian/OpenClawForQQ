package ink.easycode.qqclaw

import ink.easycode.qqclaw.config.ConnectorConfig
import ink.easycode.qqclaw.openclaw.*
import ink.easycode.qqclaw.protocol.*
import ink.easycode.qqclaw.qq.*

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class NapCatBridgeSocket : ConnectorSocket {
    val sentFrames = mutableListOf<BridgeFrame>()

    override fun send(frame: BridgeFrame) {
        sentFrames += frame
    }

    override fun close() = Unit
}

private class NapCatBridgeTransport : ConnectorTransport {
    val listeners = mutableListOf<ConnectorTransportListener>()
    val sockets = mutableListOf<NapCatBridgeSocket>()

    override fun open(listener: ConnectorTransportListener): ConnectorSocket {
        listeners += listener
        return NapCatBridgeSocket().also { sockets += it }
    }
}

private class NapCatProviderSocket(
    private val onSend: (String) -> Unit = {},
) : TextSocket {
    val sentPayloads = mutableListOf<String>()

    override fun sendText(payload: String) {
        sentPayloads += payload
        onSend(payload)
    }

    override fun close() = Unit
}

private class NapCatProviderTransport(
    private val socketFactory: (TextTransportListener) -> NapCatProviderSocket = { NapCatProviderSocket() },
) : TextTransport {
    val listeners = mutableListOf<TextTransportListener>()
    val sockets = mutableListOf<NapCatProviderSocket>()

    override fun open(listener: TextTransportListener): TextSocket {
        listeners += listener
        return socketFactory(listener).also { sockets += it }
    }
}

private class FlakyNapCatProviderTransport : TextTransport {
    var openAttempts = 0
    val listeners = mutableListOf<TextTransportListener>()

    override fun open(listener: TextTransportListener): TextSocket {
        listeners += listener
        openAttempts += 1
        if (openAttempts == 1) {
            throw IllegalStateException("napcat unavailable")
        }
        listener.onOpen()
        return NapCatProviderSocket()
    }
}

private fun napcatConfig(): ConnectorConfig {
    val properties = Properties().apply {
        setProperty("qq.provider", "napcat")
        setProperty("qq.botUin", "123456789")
        setProperty("qq.wsUrl", "ws://127.0.0.1:3001/")
        setProperty("openclaw.wsUrl", "ws://127.0.0.1:19190/ws")
        setProperty("openclaw.sharedSecret", "change-me")
        setProperty("openclaw.accountId", "qqbot:123456789")
    }
    return ConnectorConfig.fromProperties(properties)
}

class NapCatOneBotRuntimeTest {
    @Test
    fun `forwards private message events into openclaw inbound frames`() {
        val bridgeTransport = NapCatBridgeTransport()
        val config = napcatConfig()
        lateinit var client: OpenClawWsClient
        val adapter = QqClawAdapter(
            accountId = config.openClaw.accountId,
            inboundSink = { frame -> client.forwardInbound(frame) },
        )
        client = OpenClawWsClient(config, adapter, bridgeTransport)
        client.connect()
        bridgeTransport.listeners.single().onOpen()
        bridgeTransport.sockets.single().sentFrames.clear()

        val providerTransport = NapCatProviderTransport()
        val runtime = NapCatOneBotRuntime(config.qq, adapter, providerTransport)

        runtime.connect()
        providerTransport.listeners.single().onOpen()
        providerTransport.listeners.single().onText(
            """
            {"post_type":"message","message_type":"private","sub_type":"friend","user_id":987654321,"message_id":99,"time":1772900400,"raw_message":"你好，OpenClaw","message":[{"type":"text","data":{"text":"你好，OpenClaw"}}]}
            """.trimIndent(),
        )

        val frame = bridgeTransport.sockets.single().sentFrames.single() as InboundMessage
        assertEquals("987654321", frame.peer.userId)
        assertEquals("99", frame.message.id)
        assertEquals("你好，OpenClaw", frame.message.text)
        assertEquals(1_772_900_400_000, frame.timestamp)
    }

    @Test
    fun `falls back to raw string private messages when onebot message is a string`() {
        val bridgeTransport = NapCatBridgeTransport()
        val config = napcatConfig()
        lateinit var client: OpenClawWsClient
        val adapter = QqClawAdapter(
            accountId = config.openClaw.accountId,
            inboundSink = { frame -> client.forwardInbound(frame) },
        )
        client = OpenClawWsClient(config, adapter, bridgeTransport)
        client.connect()
        bridgeTransport.listeners.single().onOpen()
        bridgeTransport.sockets.single().sentFrames.clear()

        val providerTransport = NapCatProviderTransport()
        val runtime = NapCatOneBotRuntime(config.qq, adapter, providerTransport)

        runtime.connect()
        providerTransport.listeners.single().onOpen()
        providerTransport.listeners.single().onText(
            """
            {"post_type":"message","message_type":"private","user_id":987654321,"message_id":101,"time":1772900450,"raw_message":"  padded text  ","message":"  padded text  "}
            """.trimIndent(),
        )

        val frame = bridgeTransport.sockets.single().sentFrames.single() as InboundMessage
        assertEquals("  padded text  ", frame.message.text)
    }

    @Test
    fun `ignores mixed content private message events`() {
        val bridgeTransport = NapCatBridgeTransport()
        val config = napcatConfig()
        lateinit var client: OpenClawWsClient
        val adapter = QqClawAdapter(
            accountId = config.openClaw.accountId,
            inboundSink = { frame -> client.forwardInbound(frame) },
        )
        client = OpenClawWsClient(config, adapter, bridgeTransport)
        client.connect()
        bridgeTransport.listeners.single().onOpen()
        bridgeTransport.sockets.single().sentFrames.clear()

        val providerTransport = NapCatProviderTransport()
        val runtime = NapCatOneBotRuntime(config.qq, adapter, providerTransport)

        runtime.connect()
        providerTransport.listeners.single().onOpen()
        providerTransport.listeners.single().onText(
            """
            {"post_type":"message","message_type":"private","sub_type":"friend","user_id":987654321,"message_id":100,"time":1772900500,"raw_message":"hi [image]","message":[{"type":"text","data":{"text":"hi"}},{"type":"image","data":{"file":"abc.png"}}]}
            """.trimIndent(),
        )

        assertTrue(bridgeTransport.sockets.single().sentFrames.isEmpty())
    }

    @Test
    fun `sends private messages through napcat and returns the provider message id`() {
        val config = napcatConfig()
        val adapter = QqClawAdapter(config.openClaw.accountId, inboundSink = { false })
        val providerTransport = NapCatProviderTransport { listener ->
            NapCatProviderSocket { payload ->
                val echo = NapCatOneBotRuntime.extractEcho(payload)
                listener.onText("{\"status\":\"ok\",\"retcode\":0,\"data\":{\"message_id\":445566},\"echo\":\"$echo\"}")
            }
        }
        val runtime = NapCatOneBotRuntime(config.qq, adapter, providerTransport)

        runtime.connect()
        providerTransport.listeners.single().onOpen()
        val result = runtime.sendText(
            OutboundTextRequest(
                accountId = config.openClaw.accountId,
                userId = "987654321",
                text = "reply from lobster",
            ),
        )

        val payload = providerTransport.sockets.single().sentPayloads.single()
        assertTrue(payload.contains("\"action\":\"send_private_msg\""))
        assertTrue(payload.contains("\"user_id\":987654321"))
        assertTrue(payload.contains("\"message\":\"reply from lobster\""))
        assertEquals("445566", result.providerMessageId)
    }

    @Test
    fun `retries when napcat is unavailable during startup`() {
        val transport = FlakyNapCatProviderTransport()
        val runtime = NapCatOneBotRuntime(
            napcatConfig().qq,
            QqClawAdapter(napcatConfig().openClaw.accountId, inboundSink = { false }),
            transport,
            reconnectDelayMs = 10L,
        )

        runtime.connect()
        Thread.sleep(50)

        assertTrue(transport.openAttempts >= 2)
        assertTrue(runtime.isConnected)
    }

    @Test
    fun `ignores malformed provider payloads`() {
        val bridgeTransport = NapCatBridgeTransport()
        val config = napcatConfig()
        lateinit var client: OpenClawWsClient
        val adapter = QqClawAdapter(
            accountId = config.openClaw.accountId,
            inboundSink = { frame -> client.forwardInbound(frame) },
        )
        client = OpenClawWsClient(config, adapter, bridgeTransport)
        client.connect()
        bridgeTransport.listeners.single().onOpen()
        bridgeTransport.sockets.single().sentFrames.clear()

        val providerTransport = NapCatProviderTransport()
        val runtime = NapCatOneBotRuntime(config.qq, adapter, providerTransport)

        runtime.connect()
        providerTransport.listeners.single().onOpen()
        providerTransport.listeners.single().onText("not-json")

        assertFalse(runtime.isConnected.not())
        assertTrue(bridgeTransport.sockets.single().sentFrames.isEmpty())
    }
    @Test
    fun `cleans up pending replies when sending to napcat fails`() {
        val config = napcatConfig()
        val adapter = QqClawAdapter(config.openClaw.accountId, inboundSink = { false })
        val providerTransport = NapCatProviderTransport {
            NapCatProviderSocket { _ ->
                throw IllegalStateException("socket write failed")
            }
        }
        val runtime = NapCatOneBotRuntime(config.qq, adapter, providerTransport)

        runtime.connect()
        providerTransport.listeners.single().onOpen()

        try {
            runtime.sendText(
                OutboundTextRequest(
                    accountId = config.openClaw.accountId,
                    userId = "987654321",
                    text = "reply from lobster",
                ),
            )
        } catch (_: IllegalStateException) {
            // expected
        }

        val field = runtime.javaClass.getDeclaredField("pendingResponses")
        field.isAccessible = true
        val pending = field.get(runtime) as Map<*, *>
        assertTrue(pending.isEmpty())
    }
    @Test
    fun `fails outstanding sends when the websocket closes unexpectedly`() {
        val config = napcatConfig().copy(qq = napcatConfig().qq.copy(requestTimeoutMs = 5_000L))
        val providerTransport = NapCatProviderTransport()
        val runtime = NapCatOneBotRuntime(
            config.qq,
            QqClawAdapter(config.openClaw.accountId, inboundSink = { false }),
            providerTransport,
        )

        runtime.connect()
        providerTransport.listeners.single().onOpen()

        val future = java.util.concurrent.CompletableFuture<Throwable?>()
        Thread {
            try {
                runtime.sendText(
                    OutboundTextRequest(
                        accountId = config.openClaw.accountId,
                        userId = "987654321",
                        text = "reply from lobster",
                    ),
                )
                future.complete(null)
            } catch (error: Throwable) {
                future.complete(error)
            }
        }.start()

        Thread.sleep(50)
        providerTransport.listeners.single().onClosed("provider dropped")

        val failure = future.get(1, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(failure is java.util.concurrent.ExecutionException)
        assertTrue(failure.cause is IllegalStateException)
    }
}



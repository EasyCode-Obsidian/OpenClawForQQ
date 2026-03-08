package ink.easycode.qqclaw

import ink.easycode.qqclaw.config.ConnectorConfig
import ink.easycode.qqclaw.openclaw.*
import ink.easycode.qqclaw.protocol.*
import ink.easycode.qqclaw.qq.*

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class RecordingSocket : ConnectorSocket {
    val sentFrames = mutableListOf<BridgeFrame>()
    var closed = false

    override fun send(frame: BridgeFrame) {
        sentFrames += frame
    }

    override fun close() {
        closed = true
    }
}

private class RecordingTransport : ConnectorTransport {
    val sockets = mutableListOf<RecordingSocket>()
    val listeners = mutableListOf<ConnectorTransportListener>()

    override fun open(listener: ConnectorTransportListener): ConnectorSocket {
        listeners += listener
        return RecordingSocket().also { sockets += it }
    }
}


private class EagerOpenTransport : ConnectorTransport {
    val sockets = mutableListOf<RecordingSocket>()
    val listeners = mutableListOf<ConnectorTransportListener>()

    override fun open(listener: ConnectorTransportListener): ConnectorSocket {
        listeners += listener
        val socket = RecordingSocket()
        sockets += socket
        listener.onOpen()
        return socket
    }
}
private class RecordingAdapter : QqAdapter {
    val sentTexts = mutableListOf<OutboundTextRequest>()

    override fun sendText(request: OutboundTextRequest): OutboundTextResult {
        sentTexts += request
        return OutboundTextResult(providerMessageId = "provider-msg-1")
    }
}

private fun testConfig(): ConnectorConfig {
    val properties = Properties().apply {
        setProperty("qq.provider", "overflow")
        setProperty("qq.botUin", "123456789")
        setProperty("qq.wsUrl", "ws://127.0.0.1:8080/all?verifyKey=test-key&qq=123456789")
        setProperty("openclaw.wsUrl", "ws://127.0.0.1:19190/ws")
        setProperty("openclaw.sharedSecret", "change-me")
        setProperty("openclaw.accountId", "qqbot:123456789")
    }
    return ConnectorConfig.fromProperties(properties)
}

class OpenClawWsLifecycleTest {
    @Test
    fun `sends hello on connect`() {
        val transport = RecordingTransport()
        val client = OpenClawWsClient(testConfig(), RecordingAdapter(), transport)

        client.connect()
        transport.listeners.single().onOpen()

        val hello = transport.sockets.single().sentFrames.single() as HelloMessage
        assertEquals("hello", hello.type)
        assertEquals("qqbot:123456789", hello.accounts.single())
    }

    @Test
    fun `parses hello_ack and stores the negotiated state`() {
        val transport = RecordingTransport()
        val client = OpenClawWsClient(testConfig(), RecordingAdapter(), transport)

        client.connect()
        transport.listeners.single().onOpen()
        transport.listeners.single().onFrame(
            HelloAckMessage(
                id = "ack-1",
                replyTo = "hello-1",
                timestamp = 1L,
                accepted = true,
                channel = HelloAckChannel(id = "qqdm", version = "0.1.0", defaultAgentId = "main"),
                accounts = listOf("qqbot:123456789"),
                capabilities = listOf("dm_text_in", "dm_text_out"),
            ),
        )

        assertNotNull(client.lastHelloAck)
        assertEquals("qqdm", client.lastHelloAck?.channel?.id)
    }

    @Test
    fun `answers ping with pong ack`() {
        val transport = RecordingTransport()
        val client = OpenClawWsClient(testConfig(), RecordingAdapter(), transport)

        client.connect()
        transport.listeners.single().onOpen()
        transport.sockets.single().sentFrames.clear()

        transport.listeners.single().onFrame(PingMessage(id = "ping-1", timestamp = 2L))

        val ack = transport.sockets.single().sentFrames.single() as AckMessage
        assertEquals("ping-1", ack.replyTo)
        assertEquals("pong", ack.status)
    }

    @Test
    fun `reconnects after disconnect`() {
        val transport = RecordingTransport()
        val client = OpenClawWsClient(testConfig(), RecordingAdapter(), transport)

        client.connect()
        transport.listeners.single().onOpen()
        transport.listeners.single().onClosed(null)

        assertEquals(2, transport.listeners.size)
        assertEquals(1, client.reconnectCount)
        assertFalse(client.isConnected)
    }

    @Test
    fun `dispatches outbound send text to the qq adapter and acknowledges delivery`() {
        val transport = RecordingTransport()
        val adapter = RecordingAdapter()
        val client = OpenClawWsClient(testConfig(), adapter, transport)

        client.connect()
        transport.listeners.single().onOpen()
        transport.sockets.single().sentFrames.clear()

        transport.listeners.single().onFrame(
            OutboundSendTextMessage(
                id = "send-1",
                timestamp = 3L,
                accountId = "qqbot:123456789",
                peer = Peer(type = "dm", userId = "987654321"),
                text = "hello from openclaw",
                traceId = "trace-1",
            ),
        )

        assertEquals(1, adapter.sentTexts.size)
        assertEquals("987654321", adapter.sentTexts.single().userId)
        val ack = transport.sockets.single().sentFrames.single() as AckMessage
        assertEquals("send-1", ack.replyTo)
        assertEquals("delivered", ack.status)
    }
    @Test
    fun `sends hello even when transport opens before socket assignment`() {
        val transport = EagerOpenTransport()
        val client = OpenClawWsClient(testConfig(), RecordingAdapter(), transport)

        client.connect()

        val hello = transport.sockets.single().sentFrames.single() as HelloMessage
        assertEquals("hello", hello.type)
        assertEquals("qqbot:123456789", hello.accounts.single())
    }
}

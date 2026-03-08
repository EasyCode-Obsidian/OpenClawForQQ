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

private class Task8RecordingSocket : ConnectorSocket {
    val sentFrames = mutableListOf<BridgeFrame>()
    var closed = false

    override fun send(frame: BridgeFrame) {
        sentFrames += frame
    }

    override fun close() {
        closed = true
    }
}

private class Task8RecordingTransport : ConnectorTransport {
    val sockets = mutableListOf<Task8RecordingSocket>()
    val listeners = mutableListOf<ConnectorTransportListener>()

    override fun open(listener: ConnectorTransportListener): ConnectorSocket {
        listeners += listener
        return Task8RecordingSocket().also { sockets += it }
    }
}

private fun task8Config(): ConnectorConfig {
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

class QqAdapterMappingTest {
    @Test
    fun `converts a QQ DM text event into inbound message and forwards it`() {
        val transport = Task8RecordingTransport()
        val client = OpenClawWsClient(task8Config(), object : QqAdapter {
            override fun sendText(request: OutboundTextRequest) = OutboundTextResult()
        }, transport)
        client.connect()
        transport.listeners.single().onOpen()
        transport.sockets.single().sentFrames.clear()

        val adapter = QqClawAdapter(client, task8Config().openClaw.accountId)
        val forwarded = adapter.forwardPrivateMessage(
            QqPrivateMessageEvent(
                senderId = "987654321",
                rawMessageId = "msg-1",
                timestamp = 1_772_900_100_000,
                segments = listOf(QqMessageSegment.Text("你好，龙虾")),
                traceId = "trace-1",
            ),
        )

        assertTrue(forwarded)
        val frame = transport.sockets.single().sentFrames.single() as InboundMessage
        assertEquals("inbound.message", frame.type)
        assertEquals("qqbot:123456789", frame.accountId)
        assertEquals("987654321", frame.peer.userId)
        assertEquals("msg-1", frame.message.id)
        assertEquals("你好，龙虾", frame.message.text)
        assertEquals("trace-1", frame.traceId)
    }

    @Test
    fun `extracts a stable message id when the provider does not supply one`() {
        val transport = Task8RecordingTransport()
        val client = OpenClawWsClient(task8Config(), object : QqAdapter {
            override fun sendText(request: OutboundTextRequest) = OutboundTextResult()
        }, transport)
        client.connect()
        transport.listeners.single().onOpen()

        val adapter = QqClawAdapter(client, task8Config().openClaw.accountId)
        val event = QqPrivateMessageEvent(
            senderId = "987654321",
            internalId = 42L,
            timestamp = 1_772_900_200_000,
            segments = listOf(QqMessageSegment.Text("same id")),
        )

        val first = adapter.toInboundMessage(event)
        val second = adapter.toInboundMessage(event)

        assertEquals(first?.message?.id, second?.message?.id)
        assertEquals("qqdm-987654321-42", first?.message?.id)
    }

    @Test
    fun `ignores unsupported non-text inputs in MVP`() {
        val transport = Task8RecordingTransport()
        val client = OpenClawWsClient(task8Config(), object : QqAdapter {
            override fun sendText(request: OutboundTextRequest) = OutboundTextResult()
        }, transport)
        client.connect()
        transport.listeners.single().onOpen()
        transport.sockets.single().sentFrames.clear()

        val adapter = QqClawAdapter(client, task8Config().openClaw.accountId)
        val forwarded = adapter.forwardPrivateMessage(
            QqPrivateMessageEvent(
                senderId = "987654321",
                rawMessageId = "img-1",
                timestamp = 1_772_900_300_000,
                segments = listOf(QqMessageSegment.Image("image-key")),
            ),
        )

        assertFalse(forwarded)
        assertTrue(transport.sockets.single().sentFrames.isEmpty())
    }
    @Test
    fun `ignores mixed text and image private messages in MVP`() {
        val transport = Task8RecordingTransport()
        val client = OpenClawWsClient(task8Config(), object : QqAdapter {
            override fun sendText(request: OutboundTextRequest) = OutboundTextResult()
        }, transport)
        client.connect()
        transport.listeners.single().onOpen()
        transport.sockets.single().sentFrames.clear()

        val adapter = QqClawAdapter(client, task8Config().openClaw.accountId)
        val forwarded = adapter.forwardPrivateMessage(
            QqPrivateMessageEvent(
                senderId = "987654321",
                rawMessageId = "mix-1",
                timestamp = 1_772_900_350_000,
                segments = listOf(
                    QqMessageSegment.Text("hello"),
                    QqMessageSegment.Image("image-key"),
                ),
            ),
        )

        assertFalse(forwarded)
        assertTrue(transport.sockets.single().sentFrames.isEmpty())
    }
    @Test
    fun `preserves leading and trailing whitespace in text messages`() {
        val transport = Task8RecordingTransport()
        val client = OpenClawWsClient(task8Config(), object : QqAdapter {
            override fun sendText(request: OutboundTextRequest) = OutboundTextResult()
        }, transport)
        client.connect()
        transport.listeners.single().onOpen()
        transport.sockets.single().sentFrames.clear()

        val adapter = QqClawAdapter(client, task8Config().openClaw.accountId)
        val forwarded = adapter.forwardPrivateMessage(
            QqPrivateMessageEvent(
                senderId = "987654321",
                rawMessageId = "space-1",
                timestamp = 1_772_900_360_000,
                segments = listOf(QqMessageSegment.Text("  hello  ")),
            ),
        )

        assertTrue(forwarded)
        val frame = transport.sockets.single().sentFrames.single() as InboundMessage
        assertEquals("  hello  ", frame.message.text)
    }
}

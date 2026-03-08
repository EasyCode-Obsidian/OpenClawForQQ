package ink.easycode.qqclaw

import ink.easycode.qqclaw.config.ConnectorConfig
import ink.easycode.qqclaw.openclaw.*
import ink.easycode.qqclaw.protocol.*
import ink.easycode.qqclaw.qq.*

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Task9RecordingSocket : ConnectorSocket {
    val sentFrames = mutableListOf<BridgeFrame>()

    override fun send(frame: BridgeFrame) {
        sentFrames += frame
    }

    override fun close() = Unit
}

private class Task9RecordingTransport : ConnectorTransport {
    val sockets = mutableListOf<Task9RecordingSocket>()
    val listeners = mutableListOf<ConnectorTransportListener>()

    override fun open(listener: ConnectorTransportListener): ConnectorSocket {
        listeners += listener
        return Task9RecordingSocket().also { sockets += it }
    }
}

private class SuccessfulTask9Adapter : QqAdapter {
    val sent = mutableListOf<OutboundTextRequest>()

    override fun sendText(request: OutboundTextRequest): OutboundTextResult {
        sent += request
        return OutboundTextResult(providerMessageId = "provider-msg-9")
    }
}

private class FailingTask9Adapter : QqAdapter {
    override fun sendText(request: OutboundTextRequest): OutboundTextResult {
        throw IllegalStateException("qq send exploded")
    }
}

private fun task9Config(): ConnectorConfig {
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

class OutboundSendTest {
    @Test
    fun `executes outbound send text and returns ack with provider message id`() {
        val transport = Task9RecordingTransport()
        val adapter = SuccessfulTask9Adapter()
        val client = OpenClawWsClient(task9Config(), adapter, transport)

        client.connect()
        transport.listeners.single().onOpen()
        transport.sockets.single().sentFrames.clear()

        transport.listeners.single().onFrame(
            OutboundSendTextMessage(
                id = "send-9",
                timestamp = 9L,
                accountId = "qqbot:123456789",
                peer = Peer(type = "dm", userId = "987654321"),
                text = "hello from task 9",
                traceId = "trace-9",
            ),
        )

        assertEquals(1, adapter.sent.size)
        val ack = transport.sockets.single().sentFrames.single() as AckMessage
        assertEquals("send-9", ack.replyTo)
        assertEquals("delivered", ack.status)
        assertEquals("provider-msg-9", ack.details?.get("providerMessageId"))
    }

    @Test
    fun `returns error when qq delivery fails`() {
        val transport = Task9RecordingTransport()
        val client = OpenClawWsClient(task9Config(), FailingTask9Adapter(), transport)

        client.connect()
        transport.listeners.single().onOpen()
        transport.sockets.single().sentFrames.clear()

        transport.listeners.single().onFrame(
            OutboundSendTextMessage(
                id = "send-10",
                timestamp = 10L,
                accountId = "qqbot:123456789",
                peer = Peer(type = "dm", userId = "987654321"),
                text = "hello fail",
            ),
        )

        val error = transport.sockets.single().sentFrames.single() as ErrorMessage
        assertEquals("send-10", error.replyTo)
        assertEquals("qq_send_failed", error.code)
        assertTrue(error.retryable)
        assertEquals("qq send exploded", error.message)
    }
}


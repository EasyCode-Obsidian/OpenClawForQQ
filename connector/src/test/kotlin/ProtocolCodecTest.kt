package ink.easycode.qqclaw

import ink.easycode.qqclaw.config.ConnectorConfig
import ink.easycode.qqclaw.openclaw.*
import ink.easycode.qqclaw.protocol.*
import ink.easycode.qqclaw.qq.*

import kotlin.test.Test
import kotlin.test.assertEquals

class ProtocolCodecTest {
    @Test
    fun `outbound send text messages keep their protocol type`() {
        val frame = OutboundSendTextMessage(
            id = "send-1",
            timestamp = 1L,
            accountId = "qqbot:123456789",
            peer = Peer(type = "dm", userId = "987654321"),
            text = "hello",
        )

        assertEquals("outbound.send_text", frame.type)
    }
}

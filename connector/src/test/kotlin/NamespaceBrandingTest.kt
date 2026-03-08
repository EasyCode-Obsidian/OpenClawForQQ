package ink.easycode.qqclaw

import ink.easycode.qqclaw.config.ConnectorConfig
import ink.easycode.qqclaw.qq.OutboundTextRequest
import ink.easycode.qqclaw.qq.OutboundTextResult
import ink.easycode.qqclaw.qq.QqAdapter
import ink.easycode.qqclaw.qq.QqClawAdapter
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class NamespaceBrandingTest {
    @Test
    fun `uses the ink easycode qqclaw namespace and branded adapter names`() {
        val properties = Properties().apply {
            setProperty("qq.provider", "napcat")
            setProperty("qq.botUin", "123456789")
            setProperty("qq.wsUrl", "ws://127.0.0.1:3001/")
            setProperty("openclaw.wsUrl", "ws://127.0.0.1:19190/ws")
            setProperty("openclaw.sharedSecret", "change-me")
            setProperty("openclaw.accountId", "qqbot:123456789")
        }
        val config = ConnectorConfig.fromProperties(properties)
        val adapter = QqClawAdapter(
            accountId = config.openClaw.accountId,
            inboundSink = { true },
            sendTextHandler = { request ->
                OutboundTextResult(providerMessageId = request.userId)
            },
        )

        val result = adapter.sendText(
            OutboundTextRequest(
                accountId = config.openClaw.accountId,
                userId = "2315611636",
                text = "hello",
            ),
        )

        assertEquals("2315611636", result.providerMessageId)
    }
}

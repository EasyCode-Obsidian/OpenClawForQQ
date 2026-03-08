package ink.easycode.qqclaw

import ink.easycode.qqclaw.config.ConnectorConfig
import ink.easycode.qqclaw.openclaw.*
import ink.easycode.qqclaw.protocol.*
import ink.easycode.qqclaw.qq.*

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenClawWsClientTest {
    @Test
    fun `loads config builds ws client and prepares hello payload`() {
        val properties = Properties().apply {
            setProperty("qq.provider", "overflow")
            setProperty("qq.botUin", "123456789")
            setProperty("qq.wsUrl", "ws://127.0.0.1:8080/all?verifyKey=test-key&qq=123456789")
            setProperty("openclaw.wsUrl", "ws://127.0.0.1:19190/ws")
            setProperty("openclaw.sharedSecret", "change-me")
            setProperty("openclaw.accountId", "qqbot:123456789")
        }

        val config = ConnectorConfig.fromProperties(properties)
        val adapter = object : QqAdapter {
            override fun sendText(request: OutboundTextRequest): OutboundTextResult {
                return OutboundTextResult()
            }
        }
        val client = OpenClawWsClient(config, adapter)
        val hello = client.prepareHello(
            instanceId = "connector-qqclaw-01",
            version = "0.1.0",
            provider = "overflow",
        )

        assertEquals("ws://127.0.0.1:19190/ws", config.openClaw.wsUrl)
        assertEquals("ws://127.0.0.1:8080/all?verifyKey=test-key&qq=123456789", config.qq.wsUrl)
        assertEquals(adapter, client.adapter)
        assertEquals("hello", hello.type)
        assertEquals("qqbot:123456789", hello.accounts.single())
        assertEquals("shared_secret", hello.auth.scheme)
        assertEquals("change-me", hello.auth.token)
    }
}


package ink.easycode.qqclaw.qq

import ink.easycode.qqclaw.openclaw.OpenClawWsClient
import ink.easycode.qqclaw.protocol.InboundMessage
import ink.easycode.qqclaw.protocol.InboundPayload
import ink.easycode.qqclaw.protocol.InboundSource
import ink.easycode.qqclaw.protocol.Peer

data class OutboundTextRequest(
    val accountId: String,
    val userId: String,
    val text: String,
    val traceId: String? = null,
)

data class OutboundTextResult(
    val providerMessageId: String? = null,
)

interface QqAdapter {
    fun sendText(request: OutboundTextRequest): OutboundTextResult
}

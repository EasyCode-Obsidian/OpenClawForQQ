package ink.easycode.qqclaw.qq

import ink.easycode.qqclaw.openclaw.OpenClawWsClient
import ink.easycode.qqclaw.protocol.InboundMessage
import ink.easycode.qqclaw.protocol.InboundPayload
import ink.easycode.qqclaw.protocol.InboundSource
import ink.easycode.qqclaw.protocol.Peer

sealed interface QqMessageSegment {
    data class Text(val text: String) : QqMessageSegment
    data class Image(val imageKey: String) : QqMessageSegment
}

data class QqPrivateMessageEvent(
    val senderId: String,
    val timestamp: Long,
    val rawMessageId: String? = null,
    val internalId: Long? = null,
    val traceId: String? = null,
    val segments: List<QqMessageSegment>,
)

class QqClawAdapter(
    private val accountId: String,
    private val inboundSink: (InboundMessage) -> Boolean,
    private val sendTextHandler: (OutboundTextRequest) -> OutboundTextResult = {
        throw IllegalStateException("QQ provider runtime is not wired yet for ${it.accountId}")
    },
    private val sourceProvider: String = "napcat",
) : QqAdapter {
    constructor(
        client: OpenClawWsClient,
        accountId: String,
        sendTextHandler: (OutboundTextRequest) -> OutboundTextResult = { OutboundTextResult() },
    ) : this(accountId, { frame -> client.forwardInbound(frame) }, sendTextHandler)

    override fun sendText(request: OutboundTextRequest): OutboundTextResult {
        return sendTextHandler(request)
    }

    fun toInboundMessage(event: QqPrivateMessageEvent): InboundMessage? {
        val textSegments = event.segments.filterIsInstance<QqMessageSegment.Text>()
        if (textSegments.isEmpty() || textSegments.size != event.segments.size) {
            return null
        }

        val text = textSegments.joinToString(separator = "") { it.text }
        if (text.isBlank()) {
            return null
        }

        return InboundMessage(
            id = stableEventId(event),
            timestamp = event.timestamp,
            traceId = event.traceId,
            accountId = accountId,
            peer = Peer(type = "dm", userId = event.senderId),
            message = InboundPayload(
                id = stableMessageId(event),
                text = text,
            ),
            source = InboundSource(
                provider = sourceProvider,
                eventType = "private_message",
            ),
        )
    }

    fun forwardPrivateMessage(event: QqPrivateMessageEvent): Boolean {
        val frame = toInboundMessage(event) ?: return false
        return inboundSink(frame)
    }

    private fun stableEventId(event: QqPrivateMessageEvent): String {
        return event.rawMessageId ?: stableMessageId(event)
    }

    private fun stableMessageId(event: QqPrivateMessageEvent): String {
        return event.rawMessageId ?: "qqdm-${event.senderId}-${event.internalId ?: event.timestamp}"
    }
}

package ink.easycode.qqclaw.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
sealed interface BridgeFrame {
    val type: String
    val id: String
    val timestamp: Long
}

@Serializable
data class HelloConnector(
    val instanceId: String,
    val version: String,
    val provider: String,
)

@Serializable
data class HelloAuth(
    val scheme: String,
    val token: String,
)

@Serializable
@SerialName("hello")
data class HelloMessage(
    override val type: String = "hello",
    override val id: String,
    override val timestamp: Long,
    val connector: HelloConnector,
    val auth: HelloAuth,
    val accounts: List<String>,
    val capabilities: List<String>,
) : BridgeFrame

@Serializable
data class HelloAckChannel(
    val id: String,
    val version: String,
    val defaultAgentId: String,
)

@Serializable
@SerialName("hello_ack")
data class HelloAckMessage(
    override val type: String = "hello_ack",
    override val id: String,
    override val timestamp: Long,
    val replyTo: String,
    val accepted: Boolean,
    val channel: HelloAckChannel,
    val accounts: List<String>,
    val capabilities: List<String>,
) : BridgeFrame

@Serializable
@SerialName("ping")
data class PingMessage(
    override val type: String = "ping",
    override val id: String,
    override val timestamp: Long,
) : BridgeFrame

@Serializable
data class Peer(
    val type: String,
    val userId: String,
)

@Serializable
data class InboundPayload(
    val id: String,
    val text: String,
)

@Serializable
data class InboundSource(
    val provider: String,
    val eventType: String,
)

@Serializable
@SerialName("inbound.message")
data class InboundMessage(
    override val type: String = "inbound.message",
    override val id: String,
    override val timestamp: Long,
    val accountId: String,
    val peer: Peer,
    val message: InboundPayload,
    val source: InboundSource,
    val traceId: String? = null,
) : BridgeFrame

@Serializable
@SerialName("outbound.send_text")
data class OutboundSendTextMessage(
    override val type: String = "outbound.send_text",
    override val id: String,
    override val timestamp: Long,
    val accountId: String,
    val peer: Peer,
    val text: String,
    val traceId: String? = null,
) : BridgeFrame

@Serializable
@SerialName("ack")
data class AckMessage(
    override val type: String = "ack",
    override val id: String,
    override val timestamp: Long,
    val replyTo: String,
    val status: String,
    val details: Map<String, String>? = null,
) : BridgeFrame

@Serializable
@SerialName("error")
data class ErrorMessage(
    override val type: String = "error",
    override val id: String,
    override val timestamp: Long,
    val code: String,
    val message: String,
    val replyTo: String? = null,
    val retryable: Boolean = false,
) : BridgeFrame

object BridgeCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(frame: BridgeFrame): String {
        return when (frame) {
            is HelloMessage -> json.encodeToString(HelloMessage.serializer(), frame)
            is HelloAckMessage -> json.encodeToString(HelloAckMessage.serializer(), frame)
            is PingMessage -> json.encodeToString(PingMessage.serializer(), frame)
            is InboundMessage -> json.encodeToString(InboundMessage.serializer(), frame)
            is OutboundSendTextMessage -> json.encodeToString(OutboundSendTextMessage.serializer(), frame)
            is AckMessage -> json.encodeToString(AckMessage.serializer(), frame)
            is ErrorMessage -> json.encodeToString(ErrorMessage.serializer(), frame)
        }
    }

    fun decode(payload: String): BridgeFrame {
        val root = json.parseToJsonElement(payload).jsonObject
        return when (root["type"]?.jsonPrimitive?.content) {
            "hello" -> json.decodeFromJsonElement(HelloMessage.serializer(), root)
            "hello_ack" -> json.decodeFromJsonElement(HelloAckMessage.serializer(), root)
            "ping" -> json.decodeFromJsonElement(PingMessage.serializer(), root)
            "inbound.message" -> json.decodeFromJsonElement(InboundMessage.serializer(), root)
            "outbound.send_text" -> json.decodeFromJsonElement(OutboundSendTextMessage.serializer(), root)
            "ack" -> json.decodeFromJsonElement(AckMessage.serializer(), root)
            "error" -> json.decodeFromJsonElement(ErrorMessage.serializer(), root)
            else -> throw IllegalArgumentException("Unsupported bridge frame type: ${root["type"]}")
        }
    }
}

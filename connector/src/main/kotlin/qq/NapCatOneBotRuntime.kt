package ink.easycode.qqclaw.qq

import ink.easycode.qqclaw.config.QqConfig

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NapCatOneBotRuntime(
    private val qqConfig: QqConfig,
    private val adapter: QqClawAdapter,
    private val transport: TextTransport? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val reconnectDelayMs: Long = 5_000L,
) {
    @Volatile
    var isConnected: Boolean = false
        private set

    private val pendingResponses = ConcurrentHashMap<String, CompletableFuture<OutboundTextResult>>()
    private val echoCounter = AtomicInteger(0)
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "napcat-reconnect").apply { isDaemon = true }
    }

    @Volatile
    private var socket: TextSocket? = null

    @Volatile
    private var intentionalClose = false

    private val listener = object : TextTransportListener {
        override fun onOpen() {
            isConnected = true
            println("[qqdm/napcat] ws opened -> ${qqConfig.wsUrl}")
        }

        override fun onText(payload: String) {
            try {
                handlePayload(payload)
            } catch (_: Exception) {
                // Ignore malformed provider frames; they should not kill the bridge loop.
            }
        }

        override fun onClosed(reason: String?) {
            isConnected = false
            socket = null
            if (!intentionalClose) {
                failPendingResponses(reason ?: "NapCat websocket closed unexpectedly")
                scheduleReconnect()
            }
        }
    }

    fun connect() {
        intentionalClose = false
        openTransportSafely()
    }

    fun disconnect() {
        intentionalClose = true
        socket?.close()
        socket = null
        isConnected = false
        reconnectExecutor.shutdownNow()
        failPendingResponses("NapCat websocket closed")
    }

    fun sendText(request: OutboundTextRequest): OutboundTextResult {
        val activeSocket = socket ?: error("NapCat websocket is not connected")
        if (!isConnected) {
            error("NapCat websocket is not connected")
        }

        val echo = "qqdm-${echoCounter.incrementAndGet()}"
        val pending = CompletableFuture<OutboundTextResult>()
        pendingResponses[echo] = pending
        return try {
            activeSocket.sendText(
                json.encodeToString(
                    JsonObject.serializer(),
                    buildSendPrivateMessage(echo, request),
                ),
            )
            pending.get(qqConfig.requestTimeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            pendingResponses.remove(echo)
        }
    }

    private fun handlePayload(payload: String) {
        val root = json.parseToJsonElement(payload).jsonObject
        val postType = root["post_type"]?.jsonPrimitive?.contentOrNull
        if (postType == "message") {
            val event = parsePrivateMessage(root)
            if (event != null) {
                val forwarded = adapter.forwardPrivateMessage(event)
                println("[qqdm/napcat] inbound private message sender=${event.senderId} forwarded=${forwarded} segments=${event.segments.size}")
            }
            return
        }

        val echo = root["echo"]?.jsonPrimitive?.contentOrNull ?: return
        val pending = pendingResponses[echo] ?: return
        val retcode = root["retcode"]?.jsonPrimitive?.intOrNull ?: 0
        val status = root["status"]?.jsonPrimitive?.contentOrNull ?: "ok"
        if (retcode != 0 || status != "ok") {
            pending.completeExceptionally(
                IllegalStateException(root["message"]?.jsonPrimitive?.contentOrNull ?: "send_private_msg failed"),
            )
            return
        }

        val messageId = root["data"]
            ?.jsonObject
            ?.get("message_id")
            ?.jsonPrimitive
            ?.contentOrNull
        println("[qqdm/napcat] send_private_msg ack messageId=${messageId ?: "null"}")
        pending.complete(OutboundTextResult(providerMessageId = messageId))
    }

    private fun parsePrivateMessage(root: JsonObject): QqPrivateMessageEvent? {
        if (root["message_type"]?.jsonPrimitive?.contentOrNull != "private") {
            return null
        }

        val userId = root["user_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val messageId = root["message_id"]?.jsonPrimitive?.contentOrNull
        val timestamp = normalizeEpochMillis(root["time"]?.jsonPrimitive?.longOrNull)
        val segments = parseSegments(root["message"], root["raw_message"]?.jsonPrimitive?.contentOrNull) ?: return null

        return QqPrivateMessageEvent(
            senderId = userId,
            timestamp = timestamp,
            rawMessageId = messageId,
            internalId = root["message_id"]?.jsonPrimitive?.longOrNull,
            segments = segments,
        )
    }

    private fun parseSegments(message: JsonElement?, rawMessage: String?): List<QqMessageSegment>? {
        return when (message) {
            is JsonPrimitive -> {
                val primitiveText = message.contentOrNull ?: return null
                listOf(QqMessageSegment.Text(primitiveText))
            }
            is JsonArray -> {
                val segments = mutableListOf<QqMessageSegment>()
                for (element in message) {
                    val item = element.jsonObject
                    when (item["type"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> {
                            val text = item["data"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return null
                            segments += QqMessageSegment.Text(text)
                        }
                        "image" -> {
                            val imageKey = item["data"]?.jsonObject?.get("file")?.jsonPrimitive?.contentOrNull
                                ?: item["data"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                                ?: return null
                            segments += QqMessageSegment.Image(imageKey)
                        }
                        else -> return null
                    }
                }
                segments
            }
            else -> rawMessage?.let { listOf(QqMessageSegment.Text(it)) }
        }
    }

    private fun normalizeEpochMillis(rawTime: Long?): Long {
        if (rawTime == null) {
            return clock.millis()
        }
        return if (rawTime < 1_000_000_000_000L) rawTime * 1000 else rawTime
    }

    private fun buildSendPrivateMessage(echo: String, request: OutboundTextRequest): JsonObject {
        val userId = request.userId.toLongOrNull() ?: error("QQ userId must be numeric for send_private_msg")
        return buildJsonObject {
            put("action", "send_private_msg")
            put("echo", echo)
            putJsonObject("params") {
                put("user_id", userId)
                put("message", request.text)
            }
        }
    }

    private fun openTransportSafely() {
        try {
            openTransport()
        } catch (_: Exception) {
            isConnected = false
            socket = null
            if (!intentionalClose) {
                scheduleReconnect()
            }
        }
    }

    private fun failPendingResponses(message: String) {
        val failure = IllegalStateException(message)
        pendingResponses.values.forEach { it.completeExceptionally(failure) }
        pendingResponses.clear()
    }

    private fun scheduleReconnect() {
        reconnectExecutor.schedule({
            if (!intentionalClose && socket == null) {
                openTransportSafely()
            }
        }, reconnectDelayMs, TimeUnit.MILLISECONDS)
    }

    private fun openTransport() {
        val targetTransport = transport ?: JdkTextWebSocketTransport(qqConfig.wsUrl)
        socket = targetTransport.open(listener)
    }

    companion object {
        private val helperJson = Json { ignoreUnknownKeys = true }

        fun extractEcho(payload: String): String {
            return helperJson.parseToJsonElement(payload)
                .jsonObject["echo"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?: error("Missing echo")
        }
    }
}

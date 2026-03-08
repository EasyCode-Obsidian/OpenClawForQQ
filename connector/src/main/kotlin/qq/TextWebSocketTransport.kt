package ink.easycode.qqclaw.qq

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

interface TextSocket {
    fun sendText(payload: String)
    fun close()
}

interface TextTransportListener {
    fun onOpen()
    fun onText(payload: String)
    fun onClosed(reason: String?)
}

fun interface TextTransport {
    fun open(listener: TextTransportListener): TextSocket
}

class JdkTextWebSocketTransport(
    private val wsUrl: String,
) : TextTransport {
    override fun open(listener: TextTransportListener): TextSocket {
        val client = HttpClient.newHttpClient()
        val webSocket = client.newWebSocketBuilder().buildAsync(URI.create(wsUrl), object : WebSocket.Listener {
            private val buffer = StringBuilder()

            override fun onOpen(webSocket: WebSocket) {
                listener.onOpen()
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                buffer.append(data)
                if (last) {
                    val payload = buffer.toString()
                    buffer.setLength(0)
                    listener.onText(payload)
                }
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
                listener.onClosed(reason.ifBlank { null })
                return CompletableFuture.completedFuture(null)
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                listener.onClosed(error.message)
            }
        }).join()

        return object : TextSocket {
            override fun sendText(payload: String) {
                webSocket.sendText(payload, true).join()
            }

            override fun close() {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join()
            }
        }
    }
}

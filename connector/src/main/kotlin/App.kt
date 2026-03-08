package ink.easycode.qqclaw

import ink.easycode.qqclaw.config.ConnectorConfig
import ink.easycode.qqclaw.openclaw.OpenClawWsClient
import ink.easycode.qqclaw.qq.NapCatOneBotRuntime
import ink.easycode.qqclaw.qq.QqClawAdapter

fun main() {
    val config = ConnectorConfig.fromProperties(System.getProperties())

    lateinit var providerRuntime: NapCatOneBotRuntime
    lateinit var openClawClient: OpenClawWsClient

    val adapter = QqClawAdapter(
        accountId = config.openClaw.accountId,
        inboundSink = { message -> openClawClient.forwardInbound(message) },
        sendTextHandler = { request -> providerRuntime.sendText(request) },
    )

    openClawClient = OpenClawWsClient(config, adapter)
    providerRuntime = when (config.qq.provider.lowercase()) {
        "napcat" -> NapCatOneBotRuntime(config.qq, adapter)
        else -> error("Unsupported QQ provider: ${config.qq.provider}")
    }

    openClawClient.connect()
    providerRuntime.connect()
    println("ink.easycode.qqclaw connector bridge started for ${config.openClaw.accountId} -> ${config.openClaw.wsUrl} (provider=${config.qq.provider}, qqWs=${config.qq.wsUrl}, author=Obisidian, organization=easycode)")

    while (true) {
        Thread.sleep(30_000)
    }
}

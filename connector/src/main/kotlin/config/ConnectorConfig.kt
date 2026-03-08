package ink.easycode.qqclaw.config

import java.util.Properties

data class QqConfig(
    val provider: String,
    val botUin: String,
    val wsUrl: String,
    val requestTimeoutMs: Long,
)

data class OpenClawConfig(
    val wsUrl: String,
    val sharedSecret: String,
    val accountId: String,
)

data class ConnectorConfig(
    val qq: QqConfig,
    val openClaw: OpenClawConfig,
) {
    companion object {
        fun fromProperties(properties: Properties): ConnectorConfig {
            return ConnectorConfig(
                qq = QqConfig(
                    provider = properties.require("qq.provider"),
                    botUin = properties.require("qq.botUin"),
                    wsUrl = properties.require("qq.wsUrl"),
                    requestTimeoutMs = properties.getProperty("qq.requestTimeoutMs")?.toLongOrNull() ?: 10_000L,
                ),
                openClaw = OpenClawConfig(
                    wsUrl = properties.require("openclaw.wsUrl"),
                    sharedSecret = properties.require("openclaw.sharedSecret"),
                    accountId = properties.require("openclaw.accountId"),
                ),
            )
        }

        private fun Properties.require(key: String): String {
            return getProperty(key) ?: error("Missing required property: $key")
        }
    }
}

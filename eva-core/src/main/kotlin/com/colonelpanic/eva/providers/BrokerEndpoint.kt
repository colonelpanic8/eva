package com.colonelpanic.eva.providers

import java.net.URI

class BrokerEndpoint private constructor(
    val socketUrl: String,
    val accessCode: String,
) {
    companion object {
        fun parse(link: String): BrokerEndpoint {
            val uri =
                try {
                    URI(link.trim())
                } catch (_: java.net.URISyntaxException) {
                    throw IllegalArgumentException("Invalid broker link.")
                }
            require(uri.scheme in setOf("http", "https", "ws", "wss")) { "Enter the broker connection link." }
            require(uri.userInfo == null && uri.query == null && uri.path in listOf("", "/", "/device")) { "Invalid broker link." }
            require(uri.host != null && uri.port in 1024..65535) { "The link needs a host and port." }
            val secure = uri.scheme == "https" || uri.scheme == "wss"
            require(
                secure || uri.host in setOf("127.0.0.1", "localhost"),
            ) { "Unencrypted connections are allowed only through localhost forwarding." }
            val token = uri.fragment.orEmpty()
            require(token.matches(Regex("[a-fA-F0-9]{48}"))) { "The link must include its broker access code." }
            val url = URI(if (secure) "wss" else "ws", null, uri.host, uri.port, "/device", null, null).toString()
            return BrokerEndpoint(url, token)
        }
    }
}

package com.colonelpanic.eva.call

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A Twilio API key: its SID names the key and the secret signs access tokens. Neither is portable configuration. */
class TwilioApiKey(
    val sid: String,
    val secret: String,
) {
    init {
        require(SID.matches(sid)) { "A Twilio API key SID is SK followed by 32 hexadecimal characters." }
        require(secret.isNotBlank() && secret.none(Char::isWhitespace)) { "The Twilio API key secret is empty or has spaces." }
    }

    override fun toString() = "TwilioApiKey($sid)"

    companion object {
        val SID = Regex("SK[0-9a-fA-F]{32}")
    }
}

/**
 * Mints the access token the Twilio Voice SDK presents when it connects: an HS256 JWT, as Twilio's
 * server libraries build it, signed with an API key on the phone so no token server is needed. Its
 * only grant is outgoing calls through one TwiML application; the token cannot receive calls.
 */
object TwilioAccessToken {
    /**
     * Twilio checks the token when the SDK connects, not for the rest of the call, so an hour only
     * bounds how long a leaked token could start calls. A fresh token is minted for every call.
     */
    const val DEFAULT_TTL_SECONDS = 3_600L

    fun mint(
        accountSid: String,
        key: TwilioApiKey,
        applicationSid: String,
        identity: String,
        nowSeconds: Long,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    ): String {
        // Field order matches Twilio's Python library, so a token is byte-identical to the one it mints.
        val header =
            buildJsonObject {
                put("alg", "HS256")
                put("cty", "twilio-fpa;v=1")
                put("typ", "JWT")
            }
        val payload =
            buildJsonObject {
                put("jti", "${key.sid}-$nowSeconds")
                putJsonObject("grants") {
                    putJsonObject("voice") {
                        putJsonObject("outgoing") { put("application_sid", applicationSid) }
                    }
                    put("identity", identity)
                }
                put("iss", key.sid)
                put("exp", nowSeconds + ttlSeconds)
                put("nbf", nowSeconds)
                put("sub", accountSid)
            }
        val signingInput = "${encode(header.toString().toByteArray())}.${encode(payload.toString().toByteArray())}"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.secret.toByteArray(), "HmacSHA256")) }
        return "$signingInput.${encode(mac.doFinal(signingInput.toByteArray()))}"
    }

    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

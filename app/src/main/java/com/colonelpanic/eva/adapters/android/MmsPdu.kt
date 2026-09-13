package com.colonelpanic.eva.adapters.android

import java.io.ByteArrayOutputStream

/**
 * Encodes the one MMS protocol data unit EVA needs: a text-only `M-Send.req` addressed to several
 * recipients. A group text has to be an MMS, because sending the same SMS to each member separately
 * produces one-to-one threads that neither the user nor the recipients see as a single conversation.
 *
 * The byte layout is OMA MMS encapsulation 1.2 carried over WSP binary headers.
 */
object MmsPdu {
    const val PLMN = "/TYPE=PLMN"
    private const val MESSAGE_TYPE = 0x8C
    private const val TRANSACTION_ID = 0x98
    private const val MMS_VERSION = 0x8D
    private const val FROM = 0x89
    private const val TO = 0x97
    private const val CONTENT_TYPE = 0x84
    private const val M_SEND_REQ = 0x80
    private const val VERSION_1_2 = 0x92

    /** The sender is left to the MMSC, which knows the SIM's own number. */
    private const val INSERT_ADDRESS_TOKEN = 0x81
    private const val MULTIPART_MIXED = 0xA3
    private const val TEXT_PLAIN = 0x83
    private const val CHARSET_PARAMETER = 0x81
    private const val UTF_8 = 0xEA
    private const val CONTINUE = 0x80
    private const val SEVEN_BITS = 0x7F
    private const val QUOTE = 0x7F

    fun sendRequest(
        recipients: List<String>,
        message: String,
        transactionId: String,
    ): ByteArray {
        require(recipients.isNotEmpty()) { "An MMS needs at least one recipient" }
        val out = ByteArrayOutputStream()
        out.write(MESSAGE_TYPE)
        out.write(M_SEND_REQ)
        out.write(TRANSACTION_ID)
        out.writeText(transactionId)
        out.write(MMS_VERSION)
        out.write(VERSION_1_2)
        out.write(FROM)
        out.write(1)
        out.write(INSERT_ADDRESS_TOKEN)
        recipients.forEach { recipient ->
            out.write(TO)
            out.writeText(MessageRecipients.normalize(recipient) + PLMN)
        }
        // Content-Type is the last header; everything after it is the multipart body.
        out.write(CONTENT_TYPE)
        out.write(MULTIPART_MIXED)
        out.write(body(message))
        return out.toByteArray()
    }

    private fun body(message: String): ByteArray {
        val text = message.toByteArray(Charsets.UTF_8)
        val contentType = byteArrayOf(3, TEXT_PLAIN.toByte(), CHARSET_PARAMETER.toByte(), UTF_8.toByte())
        val out = ByteArrayOutputStream()
        out.writeUintvar(1)
        out.writeUintvar(contentType.size.toLong())
        out.writeUintvar(text.size.toLong())
        out.write(contentType)
        out.write(text)
        return out.toByteArray()
    }

    /** A null-terminated string, quoted when its first byte would otherwise read as a binary token. */
    private fun ByteArrayOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty() && bytes[0].toInt() and CONTINUE != 0) write(QUOTE)
        write(bytes)
        write(0)
    }

    /** WSP's variable-length unsigned integer: seven bits per byte, high bit set on all but the last. */
    private fun ByteArrayOutputStream.writeUintvar(value: Long) {
        require(value >= 0) { "uintvar cannot be negative" }
        val groups = ArrayDeque<Int>()
        var remaining = value
        do {
            groups.addFirst((remaining and SEVEN_BITS.toLong()).toInt())
            remaining = remaining shr 7
        } while (remaining > 0)
        groups.forEachIndexed { index, group -> write(if (index == groups.size - 1) group else group or CONTINUE) }
    }
}

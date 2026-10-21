package com.colonelpanic.eva.call

import com.colonelpanic.eva.capability.SecretVault
import java.math.RoundingMode

/**
 * One step of a call agent's keypad sequence. The tool takes an array of strings, one step each:
 * keypad keys (`1`, `4417#`), a wait (`wait:1500ms`, `wait:2s`) or a private credential reference
 * (`credential:bank/pin`, or a held secret handle `secret:<hex>` with or without that prefix). Each
 * step is its own item, so no character is both a key and a pause.
 */
sealed interface DtmfStep {
    /** Shown in the transcript and receipts; never contains a credential's value. */
    val label: String

    data class Keys(
        val keys: String,
    ) : DtmfStep {
        override val label get() = keys
    }

    data class Wait(
        val millis: Long,
    ) : DtmfStep {
        override val label get() = "wait ${if (millis % 1_000 == 0L) "${millis / 1_000} s" else "$millis ms"}"
    }

    data class Credential(
        val reference: String,
    ) : DtmfStep {
        override val label get() = "[credential $reference]"
    }

    companion object {
        const val KEYS = "0123456789*#ABCD"
        const val LETTER_KEYS = "ABCD"
        private val WAIT = Regex("""wait:\s*(\d+(?:\.\d+)?)\s*(ms|s)""", RegexOption.IGNORE_CASE)
        private const val CREDENTIAL = "credential:"

        /** Parses every step before anything is sent; a bad step rejects the whole sequence. */
        fun parse(steps: List<String>): List<DtmfStep> {
            require(steps.isNotEmpty()) { "The sequence is empty." }
            return steps.mapIndexed { index, raw ->
                val step = raw.trim()
                val wait = WAIT.matchEntire(step)
                when {
                    wait != null -> {
                        val (amount, unit) = wait.destructured
                        val millis = amount.toBigDecimal().multiply(if (unit.lowercase() == "s") 1_000.toBigDecimal() else 1.toBigDecimal())
                        require(millis.signum() > 0) { "Step ${index + 1} waits for no time." }
                        val rounded = millis.setScale(0, RoundingMode.HALF_UP)
                        require(rounded <= Long.MAX_VALUE.toBigDecimal()) { "Step ${index + 1} waits longer than a phone can count." }
                        Wait(rounded.toLong())
                    }

                    step.startsWith(SecretVault.PREFIX, ignoreCase = true) -> {
                        require(step.length > SecretVault.PREFIX.length) { "Step ${index + 1} names no secret." }
                        Credential(SecretVault.PREFIX + step.substring(SecretVault.PREFIX.length))
                    }

                    step.startsWith(CREDENTIAL, ignoreCase = true) -> {
                        val reference = step.substring(CREDENTIAL.length).trim()
                        require(reference.isNotEmpty()) { "Step ${index + 1} names no credential." }
                        Credential(reference)
                    }

                    step.isNotEmpty() && step.uppercase().all { it in KEYS } -> {
                        Keys(step.uppercase())
                    }

                    else -> {
                        throw IllegalArgumentException(
                            "Step ${index + 1} (\"$raw\") is not keypad keys (0-9 * # A-D), wait:<number>ms or wait:<number>s, " +
                                "credential:<reference>, or secret:<handle>.",
                        )
                    }
                }
            }
        }

        fun describe(steps: List<DtmfStep>): String = steps.joinToString(", ") { it.label }
    }
}

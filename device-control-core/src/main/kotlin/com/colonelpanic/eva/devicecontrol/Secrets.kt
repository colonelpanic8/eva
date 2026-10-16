package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.devicecontrol.proto.Observation

/** A value a model may have typed by handle but never sees. */
class Secret(
    val value: String,
    val label: String,
) {
    override fun toString() = "Secret(label=$label)"
}

/** Opaque secret handles a task can type; values stay in memory and are resolved only for input. */
interface SecretValues {
    fun resolve(handle: String): Secret?

    /** [text] with every held value replaced by [PLACEHOLDER]. */
    fun mask(text: String): String

    companion object {
        const val PLACEHOLDER = "<secret>"

        val NONE =
            object : SecretValues {
                override fun resolve(handle: String): Secret? = null

                override fun mask(text: String) = text
            }
    }
}

/** A secret typed into a plain field shows on screen; observations given to a model never repeat it. */
fun Observation.masked(secrets: SecretValues): Observation {
    val elements =
        elements.map {
            it.copy(text = it.text?.let(secrets::mask), contentDescription = it.contentDescription?.let(secrets::mask))
        }
    return if (elements == this.elements) this else copy(elements = elements)
}

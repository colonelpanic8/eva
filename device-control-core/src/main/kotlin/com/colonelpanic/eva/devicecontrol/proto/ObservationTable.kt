package com.colonelpanic.eva.devicecontrol.proto

import kotlinx.serialization.json.JsonPrimitive

/** Text-first projection matching the Python protocol renderer, without policy instructions. */
fun Observation.renderTable(
    maxElements: Int = 200,
    maxText: Int = 80,
): String {
    require(maxElements >= 0 && maxText >= 1)
    val foreground = (packageName ?: "unknown") + (activity?.let { "/$it" } ?: "")
    val header =
        buildString {
            append("observation $observationId · $foreground · ${screen.width}x${screen.height} ${screen.orientation.name.lowercase()} · ")
            append(if (keyboardShown) "keyboard shown" else "keyboard hidden")
            if (locked) append(" · locked")
            if (!screenOn) append(" · screen off")
        }
    val lines =
        mutableListOf(
            header,
            "flags: c=clickable L=long-clickable e=editable s=scrollable k=checkable x=checked f=focused d=disabled v=selected p=password",
        )
    if (contentUnavailable) {
        val reason = unavailableReason?.let { " (reason: ${it.name.lowercase()})" }.orEmpty()
        lines += "CONTENT UNAVAILABLE: this screen is protected or exposes no accessibility content; it is not empty.$reason"
    }
    if (elements.isEmpty()) lines += "(no elements)"

    fun quote(text: String) =
        JsonPrimitive(
            if (text.codePointCount(0, text.length) <= maxText) {
                text
            } else {
                text.substring(0, text.offsetByCodePoints(0, maxText - 1)) + "…"
            },
        ).toString()
    elements.take(maxElements).forEach { element ->
        val parts = mutableListOf(" ".repeat(element.depth.coerceIn(0, 12)) + "[${element.index}]", element.role.name.lowercase())
        if (element.password) parts += "<password>" else element.text?.takeIf { it.isNotEmpty() }?.let { parts += quote(it) }
        element.contentDescription?.takeIf { it.isNotEmpty() && it != element.text }?.let { parts += "desc=" + quote(it) }
        element.resourceId?.takeIf { it.isNotEmpty() }?.let { parts += "id=" + quote(it.substringAfterLast(":id/")) }
        with(element.bounds) { parts += "@$left,$top,$right,$bottom" }
        element.flagLetters().takeIf { it.isNotEmpty() }?.let { parts += it }
        lines += parts.joinToString(" ")
    }
    if (elements.size > maxElements) lines += "… ${elements.size - maxElements} more elements not shown"
    return lines.joinToString("\n")
}

fun Element.flagLetters(): String =
    buildString {
        if (clickable) append('c')
        if (longClickable) append('L')
        if (editable) append('e')
        if (scrollable) append('s')
        if (checkable) append('k')
        if (checked) append('x')
        if (focused) append('f')
        if (!enabled) append('d')
        if (selected) append('v')
        if (password) append('p')
    }

package com.colonelpanic.eva.devicecontrol.proto

import kotlinx.serialization.json.JsonPrimitive

/** Text-first projection matching the Python protocol renderer, without policy instructions. */
fun Observation.renderTable(
    maxElements: Int = 200,
    maxText: Int = 80,
    contentNotice: String? = null,
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
    contentNotice?.let { lines.add(1, it) }
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
    if (elementsCapped) lines += CAPPED_NOTICE
    return lines.joinToString("\n")
}

const val CAPPED_NOTICE = "… the screen had more elements than EVA reads at once; scroll to reach the rest."

fun omittedNotice(count: Int) = "… $count more ${if (count == 1) "element was" else "elements were"} not shown; scroll to reach them."

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

/**
 * A short projection for a conversation model that acts directly: only elements worth addressing,
 * keeping their original indices, within a character budget.
 */
fun Observation.renderCompact(
    reference: String,
    maxElements: Int = 60,
    maxChars: Int = 3_500,
    maxText: Int = 80,
): String {
    require(maxElements >= 0 && maxChars > 0 && maxText >= 1)
    val header =
        buildString {
            append("screen ${packageName ?: "unknown"} ${screen.width}x${screen.height} (observation $reference)")
            if (keyboardShown) append(" · keyboard shown")
            if (locked) append(" · locked")
            if (!screenOn) append(" · screen off")
        }
    if (contentUnavailable) return "$header\nThis screen is protected or exposes no readable content; it is not empty."
    val addressable = elements.filter { it.isAddressable() }
    if (addressable.isEmpty()) return "$header\nNo labelled or interactive elements were readable."

    fun quote(text: String) =
        JsonPrimitive(
            if (text.codePointCount(0, text.length) <= maxText) {
                text
            } else {
                text.substring(0, text.offsetByCodePoints(0, maxText - 1)) + "…"
            },
        ).toString()
    val capped = if (elementsCapped) "\n" + CAPPED_NOTICE else ""
    // Room for the omission line at its longest, so it always fits once the count is known.
    val reserve = "\n".length + omittedNotice(addressable.size).length + capped.length
    val body = StringBuilder(header)
    var shown = 0
    for (element in addressable) {
        if (shown >= maxElements) break
        val parts = mutableListOf("[${element.index}]", element.role.name.lowercase())
        if (element.password) {
            parts += "<password>"
        } else {
            element.label()?.let { parts += quote(it) }
        }
        buildList {
            if (element.editable) add("editable")
            if (element.clickable) add("clickable")
            if (element.longClickable) add("long-clickable")
            if (element.scrollable) add("scrollable")
            if (element.checkable) add(if (element.checked) "checked" else "unchecked")
            if (element.selected) add("selected")
            if (element.focused) add("focused")
            if (!element.enabled) add("disabled")
        }.let(parts::addAll)
        with(element.bounds) { parts += "at ($left,$top)-($right,$bottom)" }
        val line = "\n" + parts.joinToString(" ")
        if (body.length + line.length > maxChars - reserve) break
        body.append(line)
        shown++
    }
    if (shown < addressable.size) body.append("\n").append(omittedNotice(addressable.size - shown))
    body.append(capped)
    return body.toString()
}

private fun Element.label(): String? = text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }

private fun Element.isAddressable() = clickable || longClickable || editable || scrollable || checkable || label() != null

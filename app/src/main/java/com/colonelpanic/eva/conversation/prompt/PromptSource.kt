package com.colonelpanic.eva.conversation.prompt

/**
 * Brings a prompt up to date with the source it follows, keeping what the user made their own.
 * [baseline] is what the source said when this installation last took from it. A component
 * still equal to its baseline, apart from its switch, takes the source's new wording; one the
 * user edited is kept as written. Components the user added stay where they were, one the user
 * deleted is not brought back, and one the source dropped goes unless the user edited it.
 * Switches are always the user's, and a new component never turns on beside an enabled slot
 * member.
 */
fun followSource(
    current: PromptConfig,
    baseline: PromptConfig,
    remote: PromptConfig,
): PromptConfig {
    val mine = current.components.associateBy { it.id }
    val before = baseline.components.associateBy { it.id }
    val theirs = remote.components.associateBy { it.id }

    fun untouched(component: PromptComponent): Boolean = before[component.id]?.let { component.copy(enabled = it.enabled) == it } == true

    val followed =
        remote.components.mapNotNull { update ->
            val held = mine[update.id]
            when {
                held == null -> update.takeIf { update.id !in before }
                untouched(held) -> update.copy(enabled = held.enabled)
                else -> held
            }
        }
    val enabledSlots =
        current.components
            .filter { it.enabled && it.slot != null }
            .map { it.slot }
            .toSet()
    val result =
        followed
            .map { component ->
                val arrived = component.id !in mine
                if (arrived && component.enabled && component.slot in enabledSlots) component.copy(enabled = false) else component
            }.toMutableList()
    // What only this installation has: the user's own components, and edits the source dropped.
    current.components.forEachIndexed { index, component ->
        if (component.id in theirs || (component.id in before && untouched(component))) return@forEachIndexed
        val after = current.components.subList(0, index).lastOrNull { previous -> result.any { it.id == previous.id } }
        val position = after?.let { previous -> result.indexOfFirst { it.id == previous.id } + 1 } ?: 0
        result.add(position, component)
    }
    return remote.copy(components = result)
}

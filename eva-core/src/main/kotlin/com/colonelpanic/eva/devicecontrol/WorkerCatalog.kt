package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.conversation.prompt.PromptConfig
import com.colonelpanic.eva.conversation.prompt.PromptContext
import com.colonelpanic.eva.conversation.prompt.PromptDefaults
import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.devicecontrol.worker.WorkerSchemas
import com.colonelpanic.eva.devicecontrol.worker.WorkerTool
import com.colonelpanic.eva.devicecontrol.worker.WorkerWording

fun workerWording(
    wording: Wording,
    prompt: PromptConfig = PromptConfig(),
    variables: Map<String, String> = emptyMap(),
): WorkerWording =
    WorkerWording(
        listOf(
            wording.message("device-worker.system"),
            prompt
                .copy(components = prompt.components.filter { it.id == PromptDefaults.PREAPPROVE_ACTIONS_ID })
                .assemble(PromptContext(voice = false, variables))
                .instructions,
        ).filter(String::isNotBlank).joinToString("\n\n"),
        (Wording.bundled.messages + wording.messages)
            .filterKeys {
                it.startsWith("device-worker.")
            }.mapKeys { it.key.removePrefix("device-worker.") },
        WorkerSchemas.schemas.map { (name, schema) ->
            val text = wording.tools["device-worker.$name"] ?: Wording.bundled.tools.getValue("device-worker.$name")
            WorkerTool(name, text.description.orEmpty(), Wording.withParameters(schema, text.parameters))
        },
    )

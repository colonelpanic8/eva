package com.colonelpanic.eva.devicecontrol

import com.colonelpanic.eva.conversation.prompt.Wording
import com.colonelpanic.eva.devicecontrol.worker.WorkerSchemas
import com.colonelpanic.eva.devicecontrol.worker.WorkerTool
import com.colonelpanic.eva.devicecontrol.worker.WorkerWording

fun workerWording(wording: Wording): WorkerWording =
    WorkerWording(
        wording.message("device-worker.system"),
        (Wording.bundled.messages + wording.messages)
            .filterKeys {
                it.startsWith("device-worker.")
            }.mapKeys { it.key.removePrefix("device-worker.") },
        WorkerSchemas.schemas.map { (name, schema) ->
            val text = wording.tools["device-worker.$name"] ?: Wording.bundled.tools.getValue("device-worker.$name")
            WorkerTool(name, text.description.orEmpty(), Wording.withParameters(schema, text.parameters))
        },
    )

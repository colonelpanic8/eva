package com.colonelpanic.eva.capability

class CapabilityRegistry(
    backends: Map<String, ExecutionBackend>,
    definitions: List<CapabilityDefinition> = BundledCapabilities.definitions,
) {
    private val bindings = backends.toMap()
    val catalog = definitions.filter { it.id in bindings }.toList()
    private val descriptions = catalog.associateBy { it.id }

    init {
        require(descriptions.size == catalog.size) { "Duplicate capability IDs" }
        require(bindings.keys == descriptions.keys) { "Every backend needs a capability definition" }
        catalog.forEach { ToolSchema.check(it.inputSchema) }
    }

    fun resolve(proposal: ToolProposal): ExecutionBackend? =
        if (proposal.catalogRevision == REVISION) bindings[proposal.capabilityId] else null

    fun validationError(proposal: ToolProposal): String? {
        if (resolve(proposal) == null) return "This action is unavailable. No app was opened."
        val definition = descriptions.getValue(proposal.capabilityId)
        return ToolSchema.error(definition.inputSchema, ToolSchema.coerce(definition.inputSchema, proposal.arguments))
            ?: definition.validateOperation(proposal.arguments)
    }

    companion object {
        const val MAP_SEARCH = "eva.android.maps.search"
        const val NAVIGATE = "eva.android.maps.navigate"
        const val SMS_COMPOSE = "eva.android.messages.compose"
        const val SMS_SEND = "eva.android.messages.send"
        const val SET_ALARM = "eva.android.alarm.set"
        const val SET_TIMER = "eva.android.timer.set"
        const val DIAL = "eva.android.phone.dial"
        const val WEB_SEARCH = "eva.android.web.search"
        const val OPEN_URL = "eva.android.web.open"
        const val EMAIL_COMPOSE = "eva.android.email.compose"
        const val CALENDAR_EVENT = "eva.android.calendar.event"
        const val OPEN_APP = "eva.android.app.open"
        const val OPEN_SETTINGS = "eva.android.settings.open"
        const val CONTACTS_SEARCH = "eva.android.contacts.search"
        const val CONVERSATIONS_SEARCH = "eva.android.messages.conversations"
        const val CONVERSATION_READ = "eva.android.messages.history"
        const val DEVICE_STATE_GET = "eva.android.device.state.get"
        const val DEVICE_STATE_SET = "eva.android.device.state.set"
        const val DEVICE_STATE_METADATA = "eva.android.device.state.metadata"
        const val MEDIA_CONTROL = "eva.android.media.control"
        const val MEDIA_NOW_PLAYING = "eva.android.media.nowplaying"
        const val MEDIA_PLAY = "eva.android.media.play"
        const val MEDIA_VOLUME = "eva.android.media.volume"
        const val UI_OBSERVE = "eva.device.observe"
        const val UI_TAP = "eva.device.tap"
        const val UI_SET_TEXT = "eva.device.set_text"

        /** Reading and driving another app's screen, which the user can withhold as a group. */
        val SCREEN_CONTROL = setOf(UI_OBSERVE, UI_TAP, UI_SET_TEXT)
        const val REVISION = 9
        const val MAX_DESTINATION_LENGTH = 500
    }
}

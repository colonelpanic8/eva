package com.colonelpanic.eva.conversation.prompt

/**
 * EVA's stock prompt is data, not code: [config] is the shipped copy of the instruction
 * catalog, `eva-prompt.yaml` in `colonelpanic8/eva-instructions`, kept byte-identical at
 * `app/src/main/resources/eva-prompt.yaml`. A fresh install writes it, reset restores it, and
 * it is the baseline an installation follows its source from until the first refresh.
 */
object PromptDefaults {
    const val END_CONVERSATION_ID = "eva.session.end"
    const val ONE_REQUEST_ID = "one-request"
    const val OPEN_CONVERSATION_ID = "open-conversation"
    private const val RESOURCE = "/eva-prompt.yaml"

    /** The variables every component may reference. */
    val VARIABLES = setOf("clock", "lookup_retries")

    val config: PromptConfig by lazy {
        val text =
            checkNotNull(
                PromptDefaults::class.java.getResourceAsStream(RESOURCE),
            ) { "The stock prompt is missing." }.use { it.readBytes() }
        PromptYaml.decode(text.toString(Charsets.UTF_8)).validated(VARIABLES)
    }

    // Stock call wording that files written before the prompt followed its source may still hold.
    // Frozen: later wording changes arrive from the catalog, not from here.
    private val oneRequestInstructionV1 =
        """
        This call is for one request. Once you have finished it, because the result is reported, the
        question is answered, or you have said what you could not do, say a short closing line and
        end the conversation with its tool. Do not ask whether there is anything else. Stay on only
        while something is genuinely unfinished: an action is still running, or you asked the user a
        question and are waiting for the answer. If the user asks you to stay on the line or starts
        another request, keep going and treat that as the request to finish.
        """.trimIndent()
    private val oneRequestInstructionV2 =
        """
        This call is for one request. Once you have finished it, because the result is reported, the
        question is answered, or you have said what you could not do, say a short closing line and
        call the end-conversation tool in the same response. Saying goodbye or otherwise sounding
        finished does not end the call by itself; every closing line must be accompanied by that tool
        call. Do not ask whether there is anything else. Stay on only while something is genuinely
        unfinished: an action is still running, or you asked the user a question and are waiting for
        the answer. If the user asks you to stay on the line or starts another request, keep going and
        treat that as the request to finish.
        """.trimIndent()
    private val oneRequestDescriptionV1 =
        """
        Hang up this voice conversation; your goodbye finishes playing before the call ends.
        Call it as soon as the user's request is complete and nothing is outstanding, after a
        short spoken closing line. Do not call it while an action is unfinished, while you
        are waiting for the user to answer a question, or after the user has asked you to
        stay on the line.
        """.trimIndent()
    private val oneRequestDescriptionV2 =
        """
        Hang up this voice conversation. In the same response, say a short closing line and call
        this tool. A spoken goodbye without this tool leaves the call open, so never give a closing
        line without calling it. Call it as soon as the user's request is complete and nothing is
        outstanding. Do not call it while an action is unfinished, while you are waiting for the
        user to answer a question, or after the user has asked you to stay on the line.
        """.trimIndent()
    private val openConversationInstructionV1 =
        """
        This call stays open. Finishing a request is not a reason to hang up: say what happened and
        wait for the user. End the conversation with its tool only when the user says goodbye, says
        that is all, or asks you to hang up, and say a brief goodbye first.
        """.trimIndent()
    private val openConversationInstructionV2 =
        """
        This call stays open. Finishing a request is not a reason to hang up: say what happened and
        wait for the user. Only when the user says goodbye, says that is all, or asks you to hang up,
        say a brief goodbye and call the end-conversation tool in the same response. Saying goodbye
        without the tool does not end the call.
        """.trimIndent()
    private val openConversationDescriptionV1 =
        """
        Hang up this voice conversation; your goodbye finishes playing before the call ends.
        Call it when the user says goodbye, says they are done, or asks you to hang up, after
        a brief spoken goodbye. A finished request is not a reason to call it; the user
        decides when the call ends.
        """.trimIndent()
    private val openConversationDescriptionV2 =
        """
        Hang up this voice conversation. When the user says goodbye, says they are done, or asks
        you to hang up, say a brief goodbye and call this tool in the same response. A spoken
        goodbye without this tool leaves the call open. A finished request is not by itself a
        reason to call it; the user decides when the call ends.
        """.trimIndent()

    /** Earlier stock wording and its replacement. */
    private class StockWording(
        val previous: List<String>,
        val current: String,
    ) {
        fun upgrade(text: String) = if (text.trimEnd() in previous) current else text
    }

    private fun stock(id: String) = config.components.first { it.id == id }

    private val callWording by lazy {
        mapOf(
            ONE_REQUEST_ID to
                (
                    StockWording(listOf(oneRequestInstructionV1, oneRequestInstructionV2), stock(ONE_REQUEST_ID).instruction) to
                        StockWording(
                            listOf(oneRequestDescriptionV1, oneRequestDescriptionV2),
                            stock(ONE_REQUEST_ID).describe.getValue(END_CONVERSATION_ID),
                        )
                ),
            OPEN_CONVERSATION_ID to
                (
                    StockWording(
                        listOf(openConversationInstructionV1, openConversationInstructionV2),
                        stock(OPEN_CONVERSATION_ID).instruction,
                    ) to
                        StockWording(
                            listOf(openConversationDescriptionV1, openConversationDescriptionV2),
                            stock(OPEN_CONVERSATION_ID).describe.getValue(END_CONVERSATION_ID),
                        )
                ),
        )
    }

    /** Brings unedited call wording from before the catalog was followed up to the stock copy. */
    internal fun upgradeStockCallWording(config: PromptConfig): PromptConfig =
        config.copy(
            components =
                config.components.map { component ->
                    val (instruction, description) = callWording[component.id] ?: return@map component
                    component.copy(
                        instruction = instruction.upgrade(component.instruction),
                        describe =
                            component.describe.mapValues { (id, text) ->
                                if (id ==
                                    END_CONVERSATION_ID
                                ) {
                                    description.upgrade(text)
                                } else {
                                    text
                                }
                            },
                    )
                },
        )
}

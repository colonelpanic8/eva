package com.colonelpanic.eva.web

import kotlinx.serialization.Serializable

@Serializable
data class WebResearchConfiguration(
    val enabled: Boolean = true,
    val model: String = "gpt-6-sol",
    val effort: String = DEFAULT_EFFORT,
    val timeoutSeconds: Int = 45,
) {
    init {
        require(model.matches(Regex("[A-Za-z0-9._:-]{1,120}"))) { "Invalid web research model." }
        require(effort in EFFORTS) { "Invalid web research reasoning effort." }
        require(timeoutSeconds in 5..60) { "Web research timeout must be 5–60 seconds." }
    }

    companion object {
        const val DEFAULT_EFFORT = "low"
        val EFFORTS = listOf("none", "low", "medium", "high", "xhigh")

        fun normalizedEffort(value: String) = value.takeIf { it in EFFORTS } ?: DEFAULT_EFFORT
    }
}

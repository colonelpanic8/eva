package com.colonelpanic.eva.data

import com.colonelpanic.eva.adapters.declarative.RepositoryHttpClient
import com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
import com.colonelpanic.eva.skills.SkillSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SkillImportState(
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

/** Adds skills from pasted files or a web address and reports the outcome for the skills screen. */
class SkillImporter(
    private val skills: SkillSettings,
    private val scope: CoroutineScope,
    private val http: RepositoryHttpClient = RepositoryHttpClient(),
) {
    private val mutable = MutableStateFlow(SkillImportState())
    val state = mutable.asStateFlow()

    fun paste(
        skill: String,
        openai: String,
    ): Boolean =
        try {
            val installed = skills.install(skill, openai, null)
            mutable.value = SkillImportState(message = "Installed ${installed.name}.")
            true
        } catch (failure: IllegalArgumentException) {
            mutable.value = SkillImportState(message = failure.message ?: "That skill could not be read.", isError = true)
            false
        }

    fun fetch(address: String) {
        if (mutable.value.busy) return
        mutable.value = SkillImportState(busy = true)
        scope.launch(Dispatchers.IO) {
            mutable.value =
                try {
                    val source = SkillSource.of(address)
                    val skill = text(source.skill)
                    val openai = runCatching { text(source.openai) }.getOrNull()
                    val installed = skills.install(skill, openai, source.skill)
                    SkillImportState(
                        message =
                            "Installed ${installed.name}" +
                                if (openai == null) " from SKILL.md alone; no agents/openai.yaml was found beside it." else ".",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    SkillImportState(message = failure.message ?: "The skill could not be fetched.", isError = true)
                }
        }
    }

    private fun text(url: String) = http.fetch(url, EvaConfigurationCodec.MAX_FILE_BYTES).toString(Charsets.UTF_8)
}

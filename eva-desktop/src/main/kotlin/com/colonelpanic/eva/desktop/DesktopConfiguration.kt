package com.colonelpanic.eva.desktop

import com.colonelpanic.eva.data.configuration.AppearancePatch
import com.colonelpanic.eva.data.configuration.CapabilitiesPatch
import com.colonelpanic.eva.data.configuration.ConfigurationReader
import com.colonelpanic.eva.data.configuration.CredentialsPatch
import com.colonelpanic.eva.data.configuration.DevicePatch
import com.colonelpanic.eva.data.configuration.EvaConfiguration
import com.colonelpanic.eva.data.configuration.EvaConfigurationCodec
import com.colonelpanic.eva.data.configuration.EvaConfigurationDocument
import com.colonelpanic.eva.data.configuration.ExtensionsPatch
import com.colonelpanic.eva.data.configuration.MessagingPatch
import com.colonelpanic.eva.data.configuration.ModelsPatch
import com.colonelpanic.eva.data.configuration.PackagesPatch
import com.colonelpanic.eva.data.configuration.PromptPatch
import com.colonelpanic.eva.data.configuration.RememberedPatch
import com.colonelpanic.eva.data.configuration.ResolvedConfiguration
import com.colonelpanic.eva.data.configuration.VoicePatch
import com.colonelpanic.eva.providers.openai.OpenAiModels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Uses the phone's configuration codec; desktop controls change only their root overrides. */
class DesktopConfiguration(
    val file: File,
) {
    data class State(
        val resolved: ResolvedConfiguration,
        val error: String? = null,
        val text: String = resolved.rootText,
    ) {
        val configuration: EvaConfiguration get() = resolved.configuration
    }

    private val defaults =
        EvaConfigurationDocument(
            models =
                ModelsPatch(
                    OpenAiModels.TEXT,
                    OpenAiModels.REALTIME,
                    OpenAiModels.TEXT_REASONING_EFFORT,
                    OpenAiModels.VOICE_REASONING_EFFORT,
                ),
            voice = VoicePatch(lookupRetries = 2),
            appearance = AppearancePatch(dynamicColor = false),
            capabilities = CapabilitiesPatch(screenControl = false),
            messaging = MessagingPatch(enabled = false, replies = emptyList()),
            prompt =
                PromptPatch(
                    source = "https://raw.githubusercontent.com/colonelpanic8/eva-instructions/main/eva-desktop-prompt.yaml",
                    components = DesktopHost.prompt.components,
                ),
            packages =
                PackagesPatch(
                    repositories = listOf("https://raw.githubusercontent.com/colonelpanic8/eva-extensions/main/catalog.yaml"),
                    installed = emptyList(),
                    waitMillis = emptyMap(),
                ),
            extensions = ExtensionsPatch(grants = emptyList()),
            credentials = CredentialsPatch(required = emptyList()),
            remembered = RememberedPatch(chosenNumbers = emptyMap()),
            device = DevicePatch(authorizations = emptyList()),
        )
    private val emptyText = EvaConfigurationCodec.encode(EvaConfigurationDocument())
    private val mutable = MutableStateFlow(State(resolve(emptyText)))
    val state = mutable.asStateFlow()

    init {
        reload()
    }

    @Synchronized fun reload() {
        var text = mutable.value.text
        try {
            text = read()
            mutable.value = State(resolve(text))
        } catch (failure: IllegalArgumentException) {
            mutable.value = mutable.value.copy(error = failure.message ?: "The configuration could not be loaded.", text = text)
        } catch (failure: java.io.IOException) {
            mutable.value = mutable.value.copy(error = failure.message ?: "The configuration could not be read.")
        }
    }

    @Synchronized fun saveModels(
        model: String,
        effort: String,
    ) {
        val current = resolve(read())
        val root = current.root
        save(
            EvaConfigurationCodec.encode(
                root.copy(models = (root.models ?: ModelsPatch()).copy(text = model.trim(), reasoningEffort = effort)),
            ),
        )
    }

    @Synchronized fun saveText(
        text: String,
        expected: String,
    ) {
        check(read() == expected) { "eva.yaml changed outside EVA. Reload it before saving." }
        save(text)
    }

    fun enabledSkills(): List<com.colonelpanic.eva.skills.Skill> {
        val configured = state.value.configuration.skills
        return (
            configured.installed.map {
                com.colonelpanic.eva.skills.Skill
                    .parse(it.skill, it.openai)
            } +
                configured.repository.map {
                    com.colonelpanic.eva.skills.Skill
                        .parse(it.skill, it.openai)
                }
        ).filterNot { it.name in configured.disabled }
    }

    private fun save(text: String) {
        val resolved = resolve(text)
        writePrivately(file.canonicalFile, text)
        mutable.value = State(resolved)
    }

    private fun read() = if (file.isFile) file.readText() else emptyText

    private fun resolve(text: String): ResolvedConfiguration {
        val directory = file.canonicalFile.parentFile
        return EvaConfigurationCodec.resolve(
            reader =
                object : ConfigurationReader {
                    override fun read(path: String): String? =
                        if (path ==
                            EvaConfigurationCodec.FILE_NAME
                        ) {
                            text
                        } else {
                            File(directory, path).takeIf { it.isFile }?.readText()
                        }

                    override fun directories(path: String): List<String> =
                        File(directory, path)
                            .listFiles()
                            ?.filter {
                                it.isDirectory
                            }?.map { it.name }
                            .orEmpty()
                },
            defaults = defaults,
        )
    }
}

package com.colonelpanic.eva.conversation.prompt

import com.charleskorn.kaml.MultiLineStringStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException

/**
 * The file format. YAML because a paragraph of prose has to survive a hand edit and a diff:
 * instructions are written as literal blocks, defaults are left out so a file says only what
 * was chosen, and an unknown key is an error rather than something silently ignored, which is
 * what catches a misspelled field in an editor.
 */
object PromptYaml {
    const val FILE_NAME = "eva-prompt.yaml"

    private val yaml =
        Yaml(
            configuration =
                YamlConfiguration(
                    encodeDefaults = false,
                    multiLineStringStyle = MultiLineStringStyle.Literal,
                    singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
                    breakScalarsAt = 100,
                ),
        )

    /** Ends with exactly one newline, so the file is POSIX-clean and a diff has no odd last hunk. */
    fun encode(config: PromptConfig): String = yaml.encodeToString(PromptConfig.serializer(), config).trimEnd() + "\n"

    /** Throws [PromptConfigException] with the parser's own location for anything unreadable. */
    fun decode(text: String): PromptConfig =
        try {
            yaml.decodeFromString(PromptConfig.serializer(), text)
        } catch (error: YamlException) {
            throw PromptConfigException("Line ${error.line}, column ${error.column}: ${error.message}", error)
        }
}

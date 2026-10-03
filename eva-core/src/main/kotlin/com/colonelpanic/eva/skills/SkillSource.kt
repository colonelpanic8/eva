package com.colonelpanic.eva.skills

import java.net.URI

/** Where to fetch a skill's `SKILL.md` and optional `agents/openai.yaml` from an address the user pasted. */
data class SkillSource(
    val skill: String,
    val openai: String,
) {
    companion object {
        private val GITHUB_PAGE = Regex("https://github\\.com/([^/]+)/([^/]+)/(?:blob|tree)/(.+)")

        /**
         * Accepts a raw `SKILL.md` address, the skill's directory, or the same as a GitHub page,
         * which is read from raw.githubusercontent.com.
         */
        fun of(address: String): SkillSource {
            val trimmed = address.trim().trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
            require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) { "Enter an HTTPS address of a SKILL.md file or its folder." }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Skill addresses cannot contain credentials, a query, or a fragment."
            }
            val page = GITHUB_PAGE.matchEntire(trimmed)?.groupValues
            val raw = page?.let { (_, owner, repository, path) -> "https://raw.githubusercontent.com/$owner/$repository/$path" } ?: trimmed
            val directory = if (raw.endsWith("/SKILL.md")) raw.removeSuffix("/SKILL.md") else raw
            return SkillSource("$directory/SKILL.md", "$directory/agents/openai.yaml")
        }
    }
}

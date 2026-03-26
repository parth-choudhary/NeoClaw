package com.parth.mobileclaw.models

/**
 * An OpenClaw-compatible skill loaded from a SKILL.md file.
 */
data class Skill(
    val name: String,
    val description: String,
    val instructions: String,
    val source: SkillSource,
    val metadata: SkillMetadata? = null,
    var isActive: Boolean = true,
    var isEnabled: Boolean = true
) {
    val id: String get() = name
}

data class SkillMetadata(
    val homepage: String? = null,
    val requires: SkillRequirements? = null,
    val executionTier: Int? = null
)

data class SkillRequirements(
    val bins: List<String>? = null,
    val env: List<String>? = null
)

sealed class SkillSource {
    data object BuiltIn : SkillSource()
    data class Local(val path: String) : SkillSource()
    data class ClawdHub(val slug: String) : SkillSource()
    data class GitRepo(val url: String) : SkillSource()
}

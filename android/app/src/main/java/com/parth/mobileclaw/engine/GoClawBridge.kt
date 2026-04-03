package com.parth.mobileclaw.engine

import android.content.Context
import com.parth.mobileclaw.data.UserMemory
import com.parth.mobileclaw.models.Skill
import com.parth.mobileclaw.models.SkillMetadata
import com.parth.mobileclaw.models.SkillRequirements
import com.parth.mobileclaw.models.SkillSource
import java.io.File
import kotlinx.coroutines.flow.asStateFlow

/**
 * Skill loader and system prompt constructor.
 * Loads skills from assets/ (built-in) and files/skills/ (user-installed).
 * Constructs the full system prompt from MD files + active skill instructions.
 */
class GoClawBridge(
    private val context: Context,
    private val userMemory: UserMemory
) {
    private val _loadedSkills = kotlinx.coroutines.flow.MutableStateFlow<List<Skill>>(emptyList())
    val loadedSkills: kotlinx.coroutines.flow.StateFlow<List<Skill>> = _loadedSkills.asStateFlow()

    private var cachedBasePrompt: String? = null

    private val promptFiles = listOf(
        "IDENTITY",   // Who the agent is
        "SOUL",       // Character and values
        "AGENTS",     // How to operate the workspace
        "USER",       // About the human
        "SYSTEM",     // Core system instructions
        "TOOLS",      // Available tools
        "SAFETY",     // Safety rules
        "MEMORY",     // Long-term memory
        "HEARTBEAT"   // Periodic task reminders
    )

    fun initialize() {
        _loadedSkills.value = loadLocalSkills()
    }

    fun reloadSkills() {
        _loadedSkills.value = loadLocalSkills()
    }

    fun invalidateCache() {
        cachedBasePrompt = null
    }

    /**
     * Build the complete system prompt: base MD files + active skills + user memory.
     */
    fun buildSystemPrompt(): String {
        var prompt = loadBasePrompt()

        // Check interaction mode
        val prefs = context.getSharedPreferences("mobileclaw_prefs", android.content.Context.MODE_PRIVATE)
        val isBrowserMode = prefs.getString("agent_interaction_mode", "accessibility") == "browser"

        // Inject active skill instructions (filter by mode)
        val activeSkills = loadedSkills.value.filter { it.isActive && it.isEnabled }
            .filter { skill ->
                when {
                    isBrowserMode && skill.name == "android-accessibility" -> false
                    !isBrowserMode && skill.name == "background-browser" -> false
                    else -> true
                }
            }
        if (activeSkills.isNotEmpty()) {
            prompt += "\n\n---\n\n## Active Skills\n\n"
            for (skill in activeSkills) {
                prompt += "### ${skill.name}\n${skill.instructions}\n\n"
            }
        }

        // If browser mode, inject browser-specific context
        if (isBrowserMode) {
            prompt += "\n\n---\n\n## Agent Mode: Background Browser\n\n"
            prompt += "You are operating in **Background Browser Mode**. You browse the web silently using a headless WebView.\n"
            prompt += "- Do NOT use accessibility tools (read_screen, tap_element, etc.) — they are disabled.\n"
            prompt += "- Use `browser_open`, `browser_read`, `browser_click`, `browser_type`, `browser_scroll`, `browser_get_url`, `browser_execute_js` instead.\n"
            prompt += "- The user has logged into websites via the Browser Login screen. Their cookies/sessions are shared with your browser.\n"
            prompt += "- You can browse any website without disrupting the user's current app.\n\n"
        }

        // Inject persistent memory
        prompt += userMemory.toPromptSection()

        return prompt
    }

    // MARK: - Prompt Loading

    private fun loadBasePrompt(): String {
        cachedBasePrompt?.let { return it }

        val sections = promptFiles.mapNotNull { name ->
            try {
                val content = context.assets.open("prompts/$name.md")
                    .bufferedReader().readText()
                stripFrontmatter(content)
            } catch (_: Exception) { null }
        }

        val prompt = sections.joinToString("\n\n---\n\n")
        cachedBasePrompt = prompt
        return prompt
    }

    private fun stripFrontmatter(content: String): String {
        val parts = content.split("---")
        return if (parts.size >= 3) {
            parts.drop(2).joinToString("---").trim()
        } else {
            content
        }
    }

    // MARK: - Skill Loading

    private fun loadLocalSkills(): List<Skill> {
        val skills = mutableListOf<Skill>()

        // 1. Built-in skills from assets/skills/
        try {
            val builtInDirs = context.assets.list("skills") ?: emptyArray()
            for (dir in builtInDirs) {
                try {
                    val content = context.assets.open("skills/$dir/SKILL.md")
                        .bufferedReader().readText()
                    parseSkillMD(content, SkillSource.BuiltIn)?.let { skills.add(it) }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // 2. User-installed skills from files/skills/
        val userSkillsDir = File(context.filesDir, "skills")
        if (userSkillsDir.exists()) {
            userSkillsDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    val skillMD = File(dir, "SKILL.md")
                    if (skillMD.exists()) {
                        val content = skillMD.readText()
                        parseSkillMD(content, SkillSource.Local(dir.absolutePath))?.let {
                            skills.add(it)
                        }
                    }
                }
            }
        }

        return skills
    }

    /**
     * Install a skill from a directory into the user's skills folder.
     */
    fun installSkill(source: File) {
        val skillsDir = File(context.filesDir, "skills")
        skillsDir.mkdirs()
        val dest = File(skillsDir, source.name)
        if (dest.exists()) dest.deleteRecursively()
        source.copyRecursively(dest)
        reloadSkills()
    }

    private fun parseSkillMD(content: String, source: SkillSource): Skill? {
        val parts = content.split("---")
        if (parts.size < 3) return null

        val yamlBlock = parts[1].trim()
        val body = parts.drop(2).joinToString("---").trim()

        var name = ""
        var description = ""
        var requiredBins: List<String>? = null
        var executionTier: Int? = null

        for (line in yamlBlock.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("name:") ->
                    name = trimmed.removePrefix("name:").trim().trim('"', '\'')
                trimmed.startsWith("description:") ->
                    description = trimmed.removePrefix("description:").trim().trim('"', '\'')
                trimmed.startsWith("bins:") ->
                    requiredBins = parseBinsArray(trimmed.removePrefix("bins:").trim())
                trimmed.startsWith("execution_tier:") ->
                    executionTier = trimmed.removePrefix("execution_tier:").trim().toIntOrNull()
            }
        }

        if (name.isEmpty()) return null

        return Skill(
            name = name,
            description = description,
            instructions = body,
            source = source,
            metadata = SkillMetadata(
                requires = requiredBins?.let { SkillRequirements(bins = it) },
                executionTier = executionTier
            )
        )
    }

    private fun parseBinsArray(str: String): List<String> {
        return str.replace("[", "").replace("]", "")
            .replace("\"", "").replace("'", "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}

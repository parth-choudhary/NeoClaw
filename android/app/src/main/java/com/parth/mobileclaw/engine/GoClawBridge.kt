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

        // Check which modes are enabled
        val prefs = context.getSharedPreferences("mobileclaw_prefs", android.content.Context.MODE_PRIVATE)
        val accessibilityEnabled = prefs.getBoolean("accessibility_mode_enabled", true)
        val browserEnabled = prefs.getBoolean("browser_mode_enabled", false)

        // Inject active skill instructions (include all active skills regardless of mode)
        val activeSkills = loadedSkills.value.filter { it.isActive && it.isEnabled }
            .filter { skill ->
                when {
                    !accessibilityEnabled && skill.name == "android-accessibility" -> false
                    !browserEnabled && skill.name == "background-browser" -> false
                    else -> true
                }
            }
        if (activeSkills.isNotEmpty()) {
            prompt += "\n\n---\n\n## Active Skills\n\n"
            for (skill in activeSkills) {
                prompt += "### ${skill.name}\n${skill.instructions}\n\n"
            }
        }

        // Dual-mode guidance
        if (accessibilityEnabled && browserEnabled) {
            prompt += "\n\n---\n\n## Agent Mode: Dual Mode (Accessibility + Browser)\n\n"
            prompt += "You have BOTH accessibility tools (control native apps) and browser tools (browse web silently in background).\n\n"
            prompt += "### Method Selection Priority\n"
            prompt += "1. **User preference** — If the user has previously told you how they prefer a task done (e.g. 'use the app' or 'do it in the browser'), use `save_memory` to remember that preference and always follow it.\n"
            prompt += "2. **Browser mode** — If a website for the task is logged in (user added it via Login Sessions), prefer browser tools. The browser works silently without interrupting the user.\n"
            prompt += "3. **Accessibility mode** — Use as last resort for app-only features. Requires the user's screen and interrupts their phone use.\n\n"
            prompt += "### When to use Browser tools\n"
            prompt += "- User says 'in background', 'without interrupting', or 'silently'\n"
            prompt += "- Task is web-based: search, scraping, reading articles, checking websites\n"
            prompt += "- The website is logged in via Manage Login Sessions\n"
            prompt += "- Accessibility service is not available (permission not granted)\n\n"
            prompt += "### When to use Accessibility tools\n"
            prompt += "- Task requires a native app (WhatsApp, camera, phone settings, etc.) with no web equivalent\n"
            prompt += "- User explicitly says 'open the app' or 'use the app'\n"
            prompt += "- Task needs app-only features not available on the website\n\n"
            prompt += "### Fallback Logic\n"
            prompt += "- If accessibility fails (service not enabled), try the browser equivalent.\n"
            prompt += "- If browser fails (site not logged in), suggest the user log in via Settings → Manage Login Sessions.\n\n"
            prompt += "### Remember User Preferences\n"
            prompt += "When the user tells you to use a specific method for a task, save it:\n"
            prompt += "`save_memory(key: \"pref_twitter_method\", value: \"browser\")` — so next time you do Twitter tasks via browser.\n\n"
        } else if (browserEnabled) {
            prompt += "\n\n---\n\n## Agent Mode: Background Browser\n\n"
            prompt += "You are operating in **Browser Mode**. You browse the web silently using a headless WebView.\n"
            prompt += "- Use `browser_open`, `browser_read`, `browser_click`, `browser_type`, `browser_scroll`, `browser_get_url`, `browser_execute_js` to interact.\n"
            prompt += "- The user has logged into websites via the Browser Login screen. Their cookies/sessions are shared with your browser.\n"
            prompt += "- You can browse any website without disrupting the user's current app.\n\n"
        }
        // If only accessibility is enabled, the android-accessibility skill already provides all the guidance needed

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

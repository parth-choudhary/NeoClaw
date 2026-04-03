package com.parth.neoclaw.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent key-value memory for the agent.
 * OpenClaw's "Persistent Memory" — the agent remembers facts across sessions.
 */
class UserMemory(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("neoclaw_memory", Context.MODE_PRIVATE)

    fun save(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun recall(key: String): String? = prefs.getString(key, null)

    fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun listAll(): Map<String, String> {
        return prefs.all.mapNotNull { (k, v) ->
            (v as? String)?.let { k to it }
        }.toMap()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Format all memories as a string for the system prompt. */
    fun toPromptSection(): String {
        val memories = listAll()
        if (memories.isEmpty()) return ""
        val lines = memories.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
        return "\n\n## Remembered Context\n$lines"
    }
}

package com.parth.mobileclaw.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * A single message in the agent conversation.
 * Stored in Room DB for persistence across app restarts.
 */
@Entity(tableName = "messages")
@TypeConverters(MessageConverters::class)
data class Message(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val role: Role,
    var content: String,
    val toolCalls: List<ToolCall>? = null,
    val toolResults: List<ToolResult>? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val conversationId: String = "default"
) {
    enum class Role { user, assistant, tool, system }
}

/**
 * A tool call requested by the LLM.
 */
data class ToolCall(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
    var status: Status = Status.pending
) {
    enum class Status { pending, running, completed, failed }
}

/**
 * Result of executing a tool.
 */
data class ToolResult(
    val id: String,
    val output: String,
    val exitCode: Int = 0,
    val isError: Boolean = false
)

/**
 * Room type converters for complex Message fields.
 */
class MessageConverters {
    private val gson = Gson()

    @TypeConverter
    fun fromRole(role: Message.Role): String = role.name

    @TypeConverter
    fun toRole(value: String): Message.Role = Message.Role.valueOf(value)

    @TypeConverter
    fun fromToolCalls(toolCalls: List<ToolCall>?): String? =
        toolCalls?.let { gson.toJson(it) }

    @TypeConverter
    fun toToolCalls(value: String?): List<ToolCall>? =
        value?.let {
            val type = object : TypeToken<List<ToolCall>>() {}.type
            gson.fromJson(it, type)
        }

    @TypeConverter
    fun fromToolResults(results: List<ToolResult>?): String? =
        results?.let { gson.toJson(it) }

    @TypeConverter
    fun toToolResults(value: String?): List<ToolResult>? =
        value?.let {
            val type = object : TypeToken<List<ToolResult>>() {}.type
            gson.fromJson(it, type)
        }
}

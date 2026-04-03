package com.parth.neoclaw.llm

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.parth.neoclaw.models.Message
import com.parth.neoclaw.models.ToolCall
import com.parth.neoclaw.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LLM API client supporting Anthropic Claude, OpenAI, and OpenRouter.
 * Handles streaming responses and tool-call parsing for all providers.
 */
class LLMService(private val secureStorage: SecureStorage) {

    private val client = OkHttpClient.Builder()
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    var activeProvider: Provider = Provider.ANTHROPIC
    var activeModel: String = Provider.ANTHROPIC.defaultModels.first()

    enum class Provider(
        val displayName: String,
        val baseURL: String,
        val keychainKey: SecureStorage.Key,
        val defaultModels: List<String>
    ) {
        ANTHROPIC(
            "Anthropic (Claude)",
            "https://api.anthropic.com/v1/messages",
            SecureStorage.Key.ANTHROPIC_API_KEY,
            listOf(
                "claude-sonnet-4-20250514",
                "claude-3-7-sonnet-20250219",
                "claude-3-5-sonnet-20241022",
                "claude-3-5-haiku-20241022",
                "claude-3-opus-20240229",
                "claude-3-haiku-20240307"
            )
        ),
        OPENAI(
            "OpenAI",
            "https://api.openai.com/v1/chat/completions",
            SecureStorage.Key.OPENAI_API_KEY,
            listOf(
                "gpt-5.4",
                "gpt-5.4-mini",
                "gpt-5.4-nano",
                "gpt-5.3",
                "gpt-5.3-mini",
                "gpt-5.3-nano",
                "gpt-5.2",
                "gpt-5.2-mini",
                "gpt-5.2-nano",
                "gpt-5.1",
                "gpt-5.1-mini",
                "gpt-5.1-nano",
                "gpt-5o",
                "gpt-5o-mini",
                "gpt-4.1",
                "gpt-4.1-mini",
                "gpt-4.1-nano",
                "gpt-4o",
                "gpt-4o-mini",
                "o4-mini",
                "o3",
                "o3-mini",
                "o1",
                "o1-mini",
                "gpt-4-turbo",
                "gpt-4",
                "gpt-3.5-turbo"
            )
        ),
        OPENROUTER(
            "OpenRouter",
            "https://openrouter.ai/api/v1/chat/completions",
            SecureStorage.Key.OPENROUTER_API_KEY,
            listOf(
                "anthropic/claude-sonnet-4-20250514",
                "anthropic/claude-3.7-sonnet",
                "anthropic/claude-3.5-sonnet",
                "anthropic/claude-3.5-haiku",
                "anthropic/claude-3-opus",
                "openai/gpt-5.4",
                "openai/gpt-5.4-mini",
                "openai/gpt-5.4-nano",
                "openai/gpt-5.3",
                "openai/gpt-5.3-mini",
                "openai/gpt-5.3-nano",
                "openai/gpt-5.2",
                "openai/gpt-5.2-mini",
                "openai/gpt-5.2-nano",
                "openai/gpt-5.1",
                "openai/gpt-5.1-mini",
                "openai/gpt-5.1-nano",
                "openai/gpt-5o",
                "openai/gpt-5o-mini",
                "openai/gpt-4.1",
                "openai/gpt-4.1-mini",
                "openai/gpt-4o",
                "openai/gpt-4o-mini",
                "openai/o4-mini",
                "openai/o3",
                "openai/o3-mini",
                "openai/o1",
                "google/gemini-2.5-pro-preview",
                "google/gemini-2.5-flash-preview",
                "google/gemini-2.0-flash",
                "google/gemini-2.0-flash-lite",
                "deepseek/deepseek-r1",
                "deepseek/deepseek-chat-v3",
                "meta-llama/llama-4-maverick",
                "meta-llama/llama-4-scout",
                "meta-llama/llama-3.3-70b-instruct",
                "mistralai/mistral-large-2411",
                "mistralai/mistral-small-2501",
                "x-ai/grok-3-beta",
                "x-ai/grok-3-mini-beta",
                "qwen/qwen-2.5-72b-instruct",
                "cohere/command-r-plus"
            )
        ),
        DEEPSEEK(
            "DeepSeek",
            "https://api.deepseek.com/chat/completions",
            SecureStorage.Key.DEEPSEEK_API_KEY,
            listOf("deepseek-chat", "deepseek-coder", "deepseek-reasoner")
        ),
        ALIYUN(
            "Aliyun (Qwen)",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            SecureStorage.Key.ALIYUN_API_KEY,
            listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen2.5-72b-instruct")
        ),
        ZHIPUAI(
            "Zhipu (ChatGLM)",
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            SecureStorage.Key.ZHIPU_API_KEY,
            listOf("glm-4-plus", "glm-4", "glm-4-flash")
        ),
        SILICONFLOW(
            "SiliconFlow",
            "https://api.siliconflow.cn/v1/chat/completions",
            SecureStorage.Key.SILICONFLOW_API_KEY,
            listOf("deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-72B-Instruct", "THUDM/glm-4-9b-chat")
        ),
        GROQ(
            "Groq",
            "https://api.groq.com/openai/v1/chat/completions",
            SecureStorage.Key.GROQ_API_KEY,
            listOf("llama-3.3-70b-versatile", "mixtral-8x7b-32768")
        ),
        TOGETHER(
            "Together AI",
            "https://api.together.xyz/v1/chat/completions",
            SecureStorage.Key.TOGETHER_API_KEY,
            listOf("meta-llama/Llama-2-70b-chat-hf", "meta-llama/Llama-3-70b-chat-hf")
        ),
        GOOGLE(
            "Google (Gemini)",
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            SecureStorage.Key.GOOGLE_API_KEY,
            listOf("gemini-2.0-flash", "gemini-2.5-pro", "gemini-1.5-pro-latest")
        ),
        CUSTOM(
            "Custom Provider",
            "",
            SecureStorage.Key.CUSTOM_API_KEY,
            listOf("gpt-4o", "llama-3")
        );
    }

    var customBaseURL: String? = null
    var customDisplayName: String? = null

    val baseURL: String get() = if (activeProvider == Provider.CUSTOM) {
        val url = customBaseURL ?: ""
        if (url.isNotEmpty() && !url.contains("/chat/completions")) {
            url.trimEnd('/') + "/v1/chat/completions"
        } else {
            url
        }
    } else activeProvider.baseURL
    val displayName: String get() = if (activeProvider == Provider.CUSTOM) customDisplayName ?: activeProvider.displayName else activeProvider.displayName

    val apiKey: String? get() = secureStorage.read(activeProvider.keychainKey)
    val hasAPIKey: Boolean get() = apiKey != null

    /**
     * Send a conversation to the LLM and stream the response.
     */
    suspend fun send(
        messages: List<Message>,
        systemPrompt: String,
        tools: List<Map<String, Any>>,
        onToken: (String) -> Unit,
        onToolCalls: (List<ToolCall>) -> Unit
    ): Message = withContext(Dispatchers.IO) {
        val key = apiKey ?: if (activeProvider == Provider.CUSTOM) "" else throw LLMException("No API key configured. Go to Settings to add one.")

        when (activeProvider) {
            Provider.ANTHROPIC -> sendAnthropic(messages, systemPrompt, tools, key, onToken, onToolCalls)
            else -> sendOpenAI(messages, systemPrompt, tools, key, onToken, onToolCalls)
        }
    }

    // MARK: - Anthropic API

    private suspend fun sendAnthropic(
        messages: List<Message>,
        systemPrompt: String,
        tools: List<Map<String, Any>>,
        apiKey: String,
        onToken: (String) -> Unit,
        onToolCalls: (List<ToolCall>) -> Unit
    ): Message {
        val body = mapOf(
            "model" to activeModel,
            "max_tokens" to 4096,
            "system" to systemPrompt,
            "messages" to messages.map { formatForAnthropic(it) },
            "tools" to tools,
            "stream" to true
        )

        val request = Request.Builder()
            .url(baseURL)
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .build()

        return streamSSE(request) { lines ->
            var fullContent = ""
            val toolCalls = mutableListOf<ToolCall>()
            var currentToolId = ""
            var currentToolName = ""
            var currentToolArgs = ""

            for (line in lines) {
                if (!line.startsWith("data: ")) continue
                val jsonStr = line.removePrefix("data: ")
                if (jsonStr == "[DONE]") continue

                val event = try {
                    gson.fromJson<Map<String, Any>>(jsonStr, object : TypeToken<Map<String, Any>>() {}.type)
                } catch (_: Exception) { continue }

                val eventType = event["type"] as? String

                if (eventType == "content_block_start") {
                    val block = event["content_block"] as? Map<*, *>
                    if (block?.get("type") == "tool_use") {
                        currentToolId = block["id"] as? String ?: UUID.randomUUID().toString()
                        currentToolName = block["name"] as? String ?: ""
                        currentToolArgs = ""
                    }
                }

                if (eventType == "content_block_delta") {
                    val delta = event["delta"] as? Map<*, *>
                    val text = delta?.get("text") as? String
                    if (text != null) {
                        fullContent += text
                        onToken(text)
                    }
                    val partialJson = delta?.get("partial_json") as? String
                    if (partialJson != null) {
                        currentToolArgs += partialJson
                    }
                }

                if (eventType == "content_block_stop" && currentToolName.isNotEmpty()) {
                    val parsedArgs: Map<String, Any?> = try {
                        gson.fromJson(currentToolArgs, object : TypeToken<Map<String, Any?>>() {}.type) ?: emptyMap()
                    } catch (_: Exception) { emptyMap() }

                    toolCalls.add(ToolCall(id = currentToolId, name = currentToolName, arguments = parsedArgs))
                    currentToolName = ""
                }

                if (eventType == "message_delta") {
                    val delta = event["delta"] as? Map<*, *>
                    if (delta?.get("stop_reason") == "tool_use" && toolCalls.isNotEmpty()) {
                        onToolCalls(toolCalls)
                    }
                }
            }

            Message(
                role = Message.Role.assistant,
                content = fullContent,
                toolCalls = toolCalls.ifEmpty { null }
            )
        }
    }

    // MARK: - OpenAI-compatible API

    private suspend fun sendOpenAI(
        messages: List<Message>,
        systemPrompt: String,
        tools: List<Map<String, Any>>,
        apiKey: String,
        onToken: (String) -> Unit,
        onToolCalls: (List<ToolCall>) -> Unit
    ): Message {
        val allMessages = mutableListOf<Map<String, Any?>>()
        allMessages.add(mapOf("role" to "system", "content" to systemPrompt))
        allMessages.addAll(messages.flatMap { formatForOpenAI(it) })

        val openAITools = tools.map { mapOf("type" to "function", "function" to it) }

        val body = mapOf(
            "model" to activeModel,
            "messages" to allMessages,
            "tools" to openAITools,
            "stream" to true
        )

        val requestBuilder = Request.Builder()
            .url(baseURL)
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .addHeader("content-type", "application/json")

        if (apiKey.isNotEmpty()) {
            requestBuilder.addHeader("authorization", "Bearer $apiKey")
        }

        val request = requestBuilder.build()

        return streamSSE(request) { lines ->
            var fullContent = ""
            val toolCalls = mutableListOf<ToolCall>()
            val accumulators = mutableMapOf<Int, Triple<String, String, String>>() // id, name, args

            for (line in lines) {
                if (!line.startsWith("data: ")) continue
                val jsonStr = line.removePrefix("data: ")
                if (jsonStr == "[DONE]") continue

                val chunk = try {
                    gson.fromJson<Map<String, Any>>(jsonStr, object : TypeToken<Map<String, Any>>() {}.type)
                } catch (_: Exception) { continue }

                val choices = (chunk["choices"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: continue
                val delta = choices["delta"] as? Map<*, *> ?: continue

                val content = delta["content"] as? String
                if (content != null) {
                    fullContent += content
                    onToken(content)
                }

                val deltaToolCalls = delta["tool_calls"] as? List<*>
                deltaToolCalls?.forEach { tc ->
                    val tcMap = tc as? Map<*, *> ?: return@forEach
                    val index = (tcMap["index"] as? Number)?.toInt() ?: return@forEach
                    val current = accumulators.getOrDefault(index, Triple("", "", ""))
                    val id = tcMap["id"] as? String ?: current.first
                    val fn = tcMap["function"] as? Map<*, *>
                    val name = fn?.get("name") as? String ?: current.second
                    val args = current.third + (fn?.get("arguments") as? String ?: "")
                    accumulators[index] = Triple(id, name, args)
                }

                val finishReason = choices["finish_reason"] as? String
                if (finishReason == "tool_calls" && accumulators.isNotEmpty()) {
                    for (key in accumulators.keys.sorted()) {
                        val (id, name, args) = accumulators[key]!!
                        val parsedArgs: Map<String, Any?> = try {
                            gson.fromJson(args, object : TypeToken<Map<String, Any?>>() {}.type) ?: emptyMap()
                        } catch (_: Exception) { emptyMap() }
                        toolCalls.add(ToolCall(
                            id = id.ifEmpty { UUID.randomUUID().toString() },
                            name = name,
                            arguments = parsedArgs
                        ))
                    }
                    if (toolCalls.isNotEmpty()) onToolCalls(toolCalls)
                }
            }

            Message(
                role = Message.Role.assistant,
                content = fullContent,
                toolCalls = toolCalls.ifEmpty { null }
            )
        }
    }

    // MARK: - SSE Streaming

    private suspend fun <T> streamSSE(request: Request, parser: (Sequence<String>) -> T): T {
        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    cont.resumeWithException(LLMException("Network error: ${e.message}"))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            val errorBody = response.body?.string() ?: ""
                            cont.resumeWithException(LLMException("API error (HTTP ${response.code}): $errorBody"))
                            return
                        }
                        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                        val result = parser(reader.lineSequence())
                        cont.resume(result)
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
            })
        }
    }

    // MARK: - Message Formatting

    private fun formatForAnthropic(msg: Message): Map<String, Any?> {
        if (msg.role == Message.Role.tool) {
            val contentBlocks = msg.toolResults?.map { result ->
                buildMap<String, Any> {
                    put("type", "tool_result")
                    put("tool_use_id", result.id)
                    put("content", result.output)
                    if (result.isError) put("is_error", true)
                }
            } ?: emptyList()
            return mapOf("role" to "user", "content" to contentBlocks)
        }

        if (msg.role == Message.Role.assistant && !msg.toolCalls.isNullOrEmpty()) {
            val contentBlocks = mutableListOf<Map<String, Any?>>()
            if (msg.content.isNotEmpty()) {
                contentBlocks.add(mapOf("type" to "text", "text" to msg.content))
            }
            msg.toolCalls!!.forEach { tc ->
                contentBlocks.add(mapOf(
                    "type" to "tool_use",
                    "id" to tc.id,
                    "name" to tc.name,
                    "input" to tc.arguments
                ))
            }
            return mapOf("role" to "assistant", "content" to contentBlocks)
        }

        return mapOf("role" to msg.role.name, "content" to msg.content)
    }

    private fun formatForOpenAI(msg: Message): List<Map<String, Any?>> {
        if (msg.role == Message.Role.tool) {
            if (msg.toolResults.isNullOrEmpty()) {
                return listOf(mapOf(
                    "role" to "tool",
                    "content" to msg.content,
                    "tool_call_id" to ""
                ))
            }
            return msg.toolResults.map { result ->
                mapOf(
                    "role" to "tool",
                    "content" to result.output,
                    "tool_call_id" to result.id
                )
            }
        }

        if (msg.role == Message.Role.assistant && !msg.toolCalls.isNullOrEmpty()) {
            val tcFormat = msg.toolCalls!!.map { tc ->
                mapOf(
                    "id" to tc.id,
                    "type" to "function",
                    "function" to mapOf(
                        "name" to tc.name,
                        "arguments" to gson.toJson(tc.arguments)
                    )
                )
            }
            return listOf(buildMap {
                put("role", "assistant")
                if (msg.content.isNotEmpty()) put("content", msg.content)
                put("tool_calls", tcFormat)
            })
        }

        return listOf(mapOf(
            "role" to if (msg.role == Message.Role.tool) "tool" else msg.role.name,
            "content" to msg.content
        ))
    }

    class LLMException(message: String) : Exception(message)
}

package com.parth.mobileclaw.engine

import android.app.Application
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.parth.mobileclaw.MainActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.parth.mobileclaw.accessibility.MobileClawAccessibilityService
import com.parth.mobileclaw.bridge.DeviceBridge
import com.parth.mobileclaw.background.AgentForegroundService
import com.parth.mobileclaw.data.AppDatabase
import com.parth.mobileclaw.data.UserMemory
import com.parth.mobileclaw.llm.LLMService
import com.parth.mobileclaw.models.Message
import com.parth.mobileclaw.models.ScheduledTask
import com.parth.mobileclaw.models.ToolCall
import com.parth.mobileclaw.models.ToolResult
import com.parth.mobileclaw.scheduling.TaskScheduler
import com.parth.mobileclaw.speech.SpeechService
import com.parth.mobileclaw.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

/**
 * Main agent orchestrator — runs the agentic tool-call loop.
 *
 * Flow:
 * 1. User sends a message
 * 2. Build conversation + system prompt (with skill instructions + memory)
 * 3. Send to LLM with tool definitions
 * 4. If LLM returns tool_calls → execute via Linux/device → feed results back
 * 5. Repeat until LLM returns a text response
 *
 * All commands execute natively via proot + Alpine Linux (no VM needed).
 */
class AgentOrchestrator(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    val secureStorage = SecureStorage(context)
    val llmService = LLMService(secureStorage)
    val linuxExecutor = LinuxExecutor(context)
    val deviceBridge = DeviceBridge(context)
    val userMemory = UserMemory(context)
    val goClawBridge = GoClawBridge(context, userMemory)
    val taskScheduler = TaskScheduler(context)
    val speechService = SpeechService(context, secureStorage)

    private val db = AppDatabase.getDatabase(context)
    private val messageDao = db.messageDao()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    val isChatVisible = MutableStateFlow(false)

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _currentToolName = MutableStateFlow<String?>(null)
    val currentToolName: StateFlow<String?> = _currentToolName

    private var currentJob: Job? = null

    private val maxIterations = 500

    private val documentsDir: File get() =
        File(context.getExternalFilesDir(null), "Documents").also { it.mkdirs() }

    init {
        // Start foreground service immediately to keep app alive in background
        AgentForegroundService.start(context, "Agent Ready")

        // Initialize components
        viewModelScope.launch {
            // Load persisted messages
            launch {
                messageDao.getMessages().collect { dbMessages ->
                    if (!_isProcessing.value) {
                        _messages.value = dbMessages
                    }
                }
            }

            // Initialize Linux environment
            linuxExecutor.initialize()

            // Load skills
            goClawBridge.initialize()

            // Wire up task scheduler
            taskScheduler.executePrompt = { prompt -> executeScheduledPrompt(prompt) }

            // Start browser service if browser mode is enabled
            val prefs = context.getSharedPreferences("mobileclaw_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("browser_mode_enabled", false)) {
                com.parth.mobileclaw.browser.AgentBrowserService.start(context)
            }

            if (_messages.value.isEmpty() && prefs.getBoolean("onboarding_complete", false)) {
                triggerBootstrapSequence()
            }
        }

        // Load saved provider/model
        val prefs = context.getSharedPreferences("mobileclaw_prefs", Context.MODE_PRIVATE)
        prefs.getString("activeProvider", null)?.let { providerName ->
            try {
                llmService.activeProvider = LLMService.Provider.valueOf(providerName)
            } catch (_: Exception) { }
        }
        prefs.getString("activeModel", null)?.let { llmService.activeModel = it }
        llmService.customBaseURL = prefs.getString("customBaseURL", null)
        llmService.customDisplayName = prefs.getString("customDisplayName", null)
        
        speechService.setMode(prefs.getString("voice_input_mode", "native") ?: "native")
    }

    // MARK: - Tool Definitions

    val toolDefinitions: List<Map<String, Any>>
        get() {
            val prefs = context.getSharedPreferences("mobileclaw_prefs", Context.MODE_PRIVATE)
            val accessibilityEnabled = prefs.getBoolean("accessibility_mode_enabled", true)
            val browserEnabled = prefs.getBoolean("browser_mode_enabled", false)

            val baseTools = listOf(
                toolDef("run_command",
                    "Execute a shell command in the Linux environment. Supports all Alpine Linux commands. Install tools with `apk add <package>`.",
                mapOf("command" to propStr("The shell command to execute")),
                listOf("command")),
            toolDef("read_file",
                "Read a file from the app's Documents directory.",
                mapOf("path" to propStr("Relative path from Documents/")),
                listOf("path")),
            toolDef("write_file",
                "Write content to a file in the app's Documents directory.",
                mapOf("path" to propStr("Relative path from Documents/"),
                      "content" to propStr("File content to write")),
                listOf("path", "content")),
            toolDef("list_files",
                "List files and directories in the Documents directory.",
                mapOf("path" to propStr("Relative path. Defaults to root.")),
                emptyList()),
            toolDef("install_package",
                "Install a Linux package. Examples: git, ffmpeg, python3, nodejs, gcc, imagemagick.",
                mapOf("package" to propStr("Package name")),
                listOf("package")),
            toolDef("get_clipboard",
                "Read the current clipboard contents.",
                emptyMap(), emptyList()),
            toolDef("set_clipboard",
                "Copy text to the clipboard.",
                mapOf("text" to propStr("Text to copy")),
                listOf("text")),
            toolDef("get_device_info",
                "Get device info: battery, storage, model, OS version.",
                emptyMap(), emptyList()),
            toolDef("get_location",
                "Get current GPS coordinates (requires location permission).",
                emptyMap(), emptyList()),
            toolDef("open_url",
                "Open a URL in the default browser.",
                mapOf("url" to propStr("URL to open")),
                listOf("url")),
            toolDef("launch_app",
                "Launch an installed app by package name.",
                mapOf("package_name" to propStr("App package name (e.g. com.whatsapp)")),
                listOf("package_name")),
            toolDef("share_file",
                "Share a file via the Android share sheet.",
                mapOf("path" to propStr("File path to share")),
                listOf("path")),
            toolDef("send_notification",
                "Send a local notification to the user.",
                mapOf("title" to propStr("Notification title"),
                      "body" to propStr("Notification body")),
                listOf("title", "body")),
            toolDef("save_memory",
                "Save a piece of information to persistent memory. The agent will remember this across conversations.",
                mapOf("key" to propStr("Memory key (e.g. 'user_name', 'preferred_language')"),
                      "value" to propStr("Value to remember")),
                listOf("key", "value")),
            toolDef("recall_memory",
                "Recall a previously saved memory by key, or list all memories.",
                mapOf("key" to propStr("Memory key to recall. Use '*' to list all.")),
                listOf("key")),
            toolDef("schedule_task",
                "Schedule a recurring task with automatic notification delivery.",
                mapOf("prompt" to propStr("The prompt to execute each time"),
                      "schedule" to mapOf<String, Any>(
                          "type" to "object",
                          "description" to "Schedule config. type='daily' with hour+minute, type='weekly' with weekday+hour+minute, type='every_n_hours' with n, type='every_n_minutes' with n, type='one_time' with delay_minutes",
                          "properties" to mapOf(
                              "type" to mapOf("type" to "string", "enum" to listOf("daily", "weekly", "every_n_hours", "every_n_minutes", "one_time")),
                              "hour" to mapOf("type" to "integer"),
                              "minute" to mapOf("type" to "integer"),
                              "weekday" to mapOf("type" to "integer"),
                              "n" to mapOf("type" to "integer"),
                              "delay_minutes" to mapOf("type" to "integer")
                          ),
                          "required" to listOf("type")
                      )),
                listOf("prompt", "schedule")),
            toolDef("list_tasks",
                "List all scheduled recurring tasks.",
                emptyMap(), emptyList()),
            toolDef("cancel_task",
                "Cancel a scheduled task by ID.",
                mapOf("task_id" to propStr("The task ID to cancel")),
                listOf("task_id")),

            // Hardware control tools
            toolDef("toggle_flashlight",
                "Turn the device flashlight (torch) on or off.",
                mapOf("turn_on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off")),
                listOf("turn_on")),
            toolDef("create_contact",
                "Create a new contact in the device's address book.",
                mapOf("name" to propStr("Full name of the contact"),
                      "phone" to propStr("Phone number (optional)"),
                      "email" to propStr("Email address (optional)")),
                listOf("name")),
            toolDef("create_calendar_event",
                "Create a calendar event on the device.",
                mapOf("title" to propStr("Event title"),
                      "description" to propStr("Event description (optional)"),
                      "location" to propStr("Event location (optional)"),
                      "start_time" to propStr("Start time as ISO 8601 string, e.g. '2025-01-15T14:30:00'. If omitted, defaults to 1 hour from now."),
                      "end_time" to propStr("End time as ISO 8601 string. If omitted, defaults to 1 hour after start."),
                      "all_day" to mapOf("type" to "boolean", "description" to "Whether this is an all-day event")),
                listOf("title")),
            toolDef("set_alarm",
                "Set an alarm on the device clock app.",
                mapOf("hour" to mapOf("type" to "integer", "description" to "Hour in 24-hour format (0-23)"),
                      "minute" to mapOf("type" to "integer", "description" to "Minute (0-59)"),
                      "label" to propStr("Optional label for the alarm")),
                listOf("hour", "minute")),
            toolDef("set_timer",
                "Set a countdown timer on the device.",
                mapOf("seconds" to mapOf("type" to "integer", "description" to "Timer duration in seconds"),
                      "label" to propStr("Optional label for the timer")),
                listOf("seconds")),
            toolDef("send_sms",
                "Send an SMS text message to a phone number.",
                mapOf("phone_number" to propStr("Recipient phone number"),
                      "message" to propStr("Text message to send")),
                listOf("phone_number", "message")),
            toolDef("make_call",
                "Make a phone call to a number.",
                mapOf("phone_number" to propStr("Phone number to call")),
                listOf("phone_number")),
            toolDef("take_photo",
                "Open the camera to take a photo. The photo will be saved to the app's Photos directory.",
                emptyMap(), emptyList()),
            toolDef("open_wifi_settings",
                "Open the WiFi settings panel so the user can toggle WiFi.",
                emptyMap(), emptyList()),
            toolDef("open_bluetooth_settings",
                "Open the Bluetooth settings so the user can manage connections.",
                emptyMap(), emptyList()),
            toolDef("open_map",
                "Open a location or address in the maps app.",
                mapOf("query" to propStr("Location name, address, or search query")),
                listOf("query"))
            )

            // Accessibility tools — control other apps on screen
            val accessibilityTools = listOf(
            toolDef("read_screen",
                "Read the currently visible screen of any app. Returns all visible UI elements with their text, types, and properties. Use this to understand what's on screen before interacting.",
                emptyMap(), emptyList()),
            toolDef("tap_element",
                "Tap a UI element by its visible text or content description.",
                mapOf("text" to propStr("Text or description of the element to tap")),
                listOf("text")),
            toolDef("tap_coordinates",
                "Tap at specific screen coordinates (x, y in pixels).",
                mapOf("x" to mapOf("type" to "number", "description" to "X coordinate"),
                      "y" to mapOf("type" to "number", "description" to "Y coordinate")),
                listOf("x", "y")),
            toolDef("type_text",
                "Type text into the currently focused text field, or find one by hint text.",
                mapOf("text" to propStr("Text to type"),
                      "field_hint" to propStr("Optional: hint text of the target field")),
                listOf("text")),
            toolDef("scroll_screen",
                "Scroll the screen up or down.",
                mapOf("direction" to propStr("'up' or 'down'")),
                listOf("direction")),
            toolDef("swipe",
                "Perform a swipe gesture between two points.",
                mapOf("start_x" to mapOf("type" to "number", "description" to "Start X"),
                      "start_y" to mapOf("type" to "number", "description" to "Start Y"),
                      "end_x" to mapOf("type" to "number", "description" to "End X"),
                      "end_y" to mapOf("type" to "number", "description" to "End Y")),
                listOf("start_x", "start_y", "end_x", "end_y")),
            toolDef("press_button",
                "Press a system navigation button.",
                mapOf("button" to propStr("One of: 'back', 'home', 'recents', 'notifications', 'screenshot'")),
                listOf("button")),
            toolDef("get_current_app",
                "Get the package name of the currently foreground app.",
                emptyMap(), emptyList())
            )
            
            // Background browser tools — browse the web silently
            val browserTools = listOf(
                toolDef("browser_open",
                    "Open a URL in a background browser tab. Use this to start scraping or interacting with a website.",
                    mapOf("url" to propStr("URL to navigate to"),
                          "session_id" to propStr("Optional: Session ID to reuse. If omitted, uses current.")),
                    listOf("url")),
                toolDef("browser_read",
                    "Read the current page's main content and interactive elements. Returns a simplified markdown representation.",
                    mapOf("session_id" to propStr("Optional: Session ID to read from")),
                    emptyList()),
                toolDef("browser_click",
                    "Click an element on the webpage by its CSS selector or visible text.",
                    mapOf("selector_or_text" to propStr("CSS selector or visible text of the button/link to click"),
                          "session_id" to propStr("Optional: Session ID to click in")),
                    listOf("selector_or_text")),
                toolDef("browser_type",
                    "Type text into a form field on the webpage.",
                    mapOf("selector_or_text" to propStr("CSS selector or placeholder text of the input field"),
                          "text" to propStr("Text to type"),
                          "session_id" to propStr("Optional: Session ID")),
                    listOf("selector_or_text", "text")),
                toolDef("browser_scroll",
                    "Scroll the webpage up or down.",
                    mapOf("direction" to propStr("'up' or 'down'"),
                          "session_id" to propStr("Optional: Session ID")),
                    listOf("direction")),
                toolDef("browser_get_url",
                    "Get the current URL and page title of the browser session.",
                    mapOf("session_id" to propStr("Optional: Session ID")),
                    emptyList()),
                toolDef("browser_execute_js",
                    "Execute arbitrary JavaScript on the current page to extract specific data or perform complex interactions.",
                    mapOf("script" to propStr("JavaScript code to execute"),
                          "session_id" to propStr("Optional: Session ID")),
                    listOf("script")),
                toolDef("browser_press_enter",
                    "Press the Enter key on the currently focused element. Use after browser_type to submit messages in chat apps, search queries, or forms.",
                    mapOf("session_id" to propStr("Optional: Session ID")),
                    emptyList())
            )
            
            // Include tools based on which modes are enabled
            var tools = baseTools
            if (accessibilityEnabled) tools = tools + accessibilityTools
            if (browserEnabled) tools = tools + browserTools
            // If somehow neither is on (shouldn't happen), include both
            if (!accessibilityEnabled && !browserEnabled) tools = baseTools + accessibilityTools + browserTools
            return tools
        }

    // MARK: - Send Message

    fun triggerBootstrapSequence() {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            if (_messages.value.isNotEmpty()) return@launch
            
            val prefs = context.getSharedPreferences("mobileclaw_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("onboarding_complete", false)) return@launch

            _isProcessing.value = true
            AgentForegroundService.start(context, "Processing request...")
            var iteration = 0

            try {
                val bootstrapText = try {
                    context.assets.open("prompts/BOOTSTRAP.md").bufferedReader().readText()
                } catch (e: Exception) { "" }

                val bootstrapPrompt = "SYSTEM INSTRUCTION:\nThis is your first session. Please follow these bootstrap instructions. Introduce yourself to the user.\n\n$bootstrapText"
                val hiddenUserMessage = Message(role = Message.Role.user, content = bootstrapPrompt)

                while (iteration < maxIterations) {
                    iteration++

                    val streamingMessage = Message(role = Message.Role.assistant, content = "")
                    addMessage(streamingMessage)
                    val streamIndex = _messages.value.size - 1

                    val messagesToSend = if (iteration == 1) {
                        listOf(hiddenUserMessage)
                    } else {
                        listOf(hiddenUserMessage) + _messages.value.dropLast(1)
                    }

                    val response = llmService.send(
                        messages = messagesToSend,
                        systemPrompt = goClawBridge.buildSystemPrompt(),
                        tools = toolDefinitions,
                        onToken = { token ->
                            val current = _messages.value.toMutableList()
                            current[streamIndex] = current[streamIndex].copy(
                                content = current[streamIndex].content + token
                            )
                            _messages.value = current
                        },
                        onToolCalls = { }
                    )

                    updateMessage(streamIndex, response)

                    val toolCalls = response.toolCalls
                    if (toolCalls.isNullOrEmpty()) break

                    val results = mutableListOf<ToolResult>()
                    for (toolCall in toolCalls) {
                        _currentToolName.value = toolCall.name
                        AgentForegroundService.start(context, "Executing task...", toolCall.name)
                        val rawResult = executeTool(toolCall)
                        results.add(rawResult.copy(id = toolCall.id))
                    }
                    addMessage(Message(
                        role = Message.Role.tool,
                        content = "",
                        toolResults = results
                    ))
                    _currentToolName.value = null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                addMessage(Message(
                    role = Message.Role.assistant,
                    content = "Error during bootstrap: ${e.message}"
                ))
            } finally {
                _isProcessing.value = false
                _currentToolName.value = null
                AgentForegroundService.start(context, "Agent Ready")
                
                if (!isChatVisible.value) {
                    val lastMsg = _messages.value.lastOrNull()
                    if (lastMsg?.role == Message.Role.assistant && lastMsg.content.isNotBlank()) {
                        showChatNotification(lastMsg.content)
                    }
                }
            }
        }
    }

    fun sendMessage(text: String) {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            AgentForegroundService.start(context, "Processing request...")
            val userMessage = Message(role = Message.Role.user, content = text)
            addMessage(userMessage)
            _isProcessing.value = true

            var iteration = 0

            try {
                while (iteration < maxIterations) {
                    iteration++

                    // Context Pruning: keep only the most recent read_screen result to save tokens and latency
                    val prunedMessages = _messages.value.toMutableList().mapIndexed { idx, msg ->
                        if (msg.role == Message.Role.tool && !msg.toolResults.isNullOrEmpty()) {
                            val isLastReadScreen = _messages.value.indexOfLast { 
                                it.role == Message.Role.tool && it.toolResults?.any { r -> r.output.startsWith("=== Screen:") } == true 
                            } == idx
                            
                            if (!isLastReadScreen) {
                                msg.copy(toolResults = msg.toolResults?.map { res ->
                                    if (res.output.startsWith("=== Screen:")) res.copy(output = "(old screen dump cleared to save latency)")
                                    else res
                                })
                            } else msg
                        } else msg
                    }

                    val streamingMessage = Message(role = Message.Role.assistant, content = "")
                    addMessage(streamingMessage)
                    val streamIndex = _messages.value.size - 1

                    val response = llmService.send(
                        messages = prunedMessages,
                        systemPrompt = goClawBridge.buildSystemPrompt(),
                        tools = toolDefinitions,
                        onToken = { token ->
                            val current = _messages.value.toMutableList()
                            current[streamIndex] = current[streamIndex].copy(
                                content = current[streamIndex].content + token
                            )
                            _messages.value = current
                        },
                        onToolCalls = { }
                    )

                    updateMessage(streamIndex, response)

                    val toolCalls = response.toolCalls
                    if (toolCalls.isNullOrEmpty()) break

                    // Sequential Tool Execution (Allows LLM to batch UI actions)
                    val results = mutableListOf<ToolResult>()
                    for (toolCall in toolCalls) {
                        _currentToolName.value = toolCall.name
                        AgentForegroundService.start(context, "Executing task...", toolCall.name)
                        val rawResult = executeTool(toolCall)
                        results.add(rawResult.copy(id = toolCall.id))
                    }

                    addMessage(Message(
                        role = Message.Role.tool,
                        content = "",
                        toolResults = results
                    ))
                    _currentToolName.value = null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                addMessage(Message(
                    role = Message.Role.assistant,
                    content = "Error: ${e.message}"
                ))
            } finally {
                _isProcessing.value = false
                _currentToolName.value = null
                AgentForegroundService.start(context, "Agent Ready")
                
                if (!isChatVisible.value) {
                    val lastMsg = _messages.value.lastOrNull()
                    if (lastMsg?.role == Message.Role.assistant && lastMsg.content.isNotBlank()) {
                        showChatNotification(lastMsg.content)
                    }
                }
            }
        }
    }

    fun stopProcessing() {
        currentJob?.cancel()
        currentJob = null
        _isProcessing.value = false
        _currentToolName.value = null
        AgentForegroundService.start(context, "Agent Ready")
        
        viewModelScope.launch {
            addMessage(Message(
                role = Message.Role.assistant,
                content = "⚠️ Task stopped by user."
            ))
        }
    }

    // MARK: - Tool Execution

    private fun isAccessibilityModeEnabled(): Boolean {
        val prefs = context.getSharedPreferences("mobileclaw_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("accessibility_mode_enabled", true)
    }

    private fun isBrowserModeEnabled(): Boolean {
        val prefs = context.getSharedPreferences("mobileclaw_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("browser_mode_enabled", false)
    }

    private fun ensureBrowserService() {
        if (com.parth.mobileclaw.browser.AgentBrowserService.instance == null) {
            com.parth.mobileclaw.browser.AgentBrowserService.start(context)
        }
    }

    private suspend fun executeTool(toolCall: ToolCall): ToolResult {
        val args = toolCall.arguments

        val result = when (toolCall.name) {
            "run_command" -> {
                val command = args["command"] as? String ?: ""
                linuxExecutor.run(command)
            }
            "read_file" -> readFile(args["path"] as? String ?: "", toolCall.id)
            "write_file" -> writeFile(
                args["path"] as? String ?: "",
                args["content"] as? String ?: "",
                toolCall.id
            )
            "list_files" -> listFiles(args["path"] as? String ?: "", toolCall.id)
            "install_package" -> linuxExecutor.installPackage(args["package"] as? String ?: "")

            // Device tools
            "get_clipboard" -> deviceBridge.getClipboard()
            "set_clipboard" -> deviceBridge.setClipboard(args["text"] as? String ?: "")
            "get_device_info" -> deviceBridge.getDeviceInfo()
            "get_location" -> deviceBridge.getLocation()
            "open_url" -> deviceBridge.openUrl(args["url"] as? String ?: "")
            "launch_app" -> deviceBridge.launchApp(args["package_name"] as? String ?: "")
            "share_file" -> deviceBridge.shareFile(args["path"] as? String ?: "")

            // Hardware control tools
            "toggle_flashlight" -> {
                val turnOn = args["turn_on"] as? Boolean ?: true
                deviceBridge.toggleFlashlight(turnOn)
            }
            "create_contact" -> {
                val name = args["name"] as? String ?: ""
                val phone = args["phone"] as? String
                val email = args["email"] as? String
                deviceBridge.createContact(name, phone, email)
            }
            "create_calendar_event" -> {
                val title = args["title"] as? String ?: ""
                val description = args["description"] as? String
                val location = args["location"] as? String
                val allDay = args["all_day"] as? Boolean ?: false

                // Parse ISO 8601 time strings to millis
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                val startMs = (args["start_time"] as? String)?.let { try { sdf.parse(it)?.time } catch (_: Exception) { null } }
                val endMs = (args["end_time"] as? String)?.let { try { sdf.parse(it)?.time } catch (_: Exception) { null } }

                deviceBridge.createCalendarEvent(title, description, location, startMs, endMs, allDay)
            }
            "set_alarm" -> {
                val hour = (args["hour"] as? Number)?.toInt() ?: 0
                val minute = (args["minute"] as? Number)?.toInt() ?: 0
                val label = args["label"] as? String
                deviceBridge.setAlarm(hour, minute, label)
            }
            "set_timer" -> {
                val seconds = (args["seconds"] as? Number)?.toInt() ?: 60
                val label = args["label"] as? String
                deviceBridge.setTimer(seconds, label)
            }
            "send_sms" -> {
                val phone = args["phone_number"] as? String ?: ""
                val message = args["message"] as? String ?: ""
                deviceBridge.sendSms(phone, message)
            }
            "make_call" -> {
                val phone = args["phone_number"] as? String ?: ""
                deviceBridge.makeCall(phone)
            }
            "take_photo" -> deviceBridge.takePhoto()
            "open_wifi_settings" -> deviceBridge.openWifiSettings()
            "open_bluetooth_settings" -> deviceBridge.openBluetoothSettings()
            "open_map" -> {
                val query = args["query"] as? String ?: ""
                deviceBridge.openMap(query)
            }

            // Notifications
            "send_notification" -> {
                val title = args["title"] as? String ?: "MobileClaw"
                val body = args["body"] as? String ?: ""
                
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val notification = androidx.core.app.NotificationCompat.Builder(context, "mobileclaw_tasks")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
                nm.notify(toolCall.id.hashCode(), notification)
                
                ToolResult(id = toolCall.id, output = "Notification sent: $title")
            }

            // Memory
            "save_memory" -> {
                val key = args["key"] as? String ?: ""
                val value = args["value"] as? String ?: ""
                userMemory.save(key, value)
                ToolResult(id = toolCall.id, output = "Remembered: $key = $value")
            }
            "recall_memory" -> {
                val key = args["key"] as? String ?: "*"
                if (key == "*") {
                    val all = userMemory.listAll()
                    val output = if (all.isEmpty()) "No memories saved yet."
                    else all.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                    ToolResult(id = toolCall.id, output = output)
                } else {
                    val value = userMemory.recall(key)
                    ToolResult(id = toolCall.id, output = value ?: "No memory found for key: $key")
                }
            }

            // Scheduling
            "schedule_task" -> {
                val prompt = args["prompt"] as? String ?: ""
                @Suppress("UNCHECKED_CAST")
                val scheduleArgs = args["schedule"] as? Map<String, Any?> ?: emptyMap()
                val recurrence = ScheduledTask.Recurrence.from(scheduleArgs)
                    ?: return ToolResult(id = toolCall.id, output = "Invalid schedule", exitCode = 1, isError = true)
                val task = taskScheduler.addTask(prompt, recurrence)
                ToolResult(id = toolCall.id, output = "Scheduled! ID: ${task.id.take(8)}\n${task.scheduleDescription}\nNext run: ${task.nextRunDate}")
            }
            "list_tasks" -> ToolResult(id = toolCall.id, output = taskScheduler.listFormatted())
            "cancel_task" -> {
                val taskId = args["task_id"] as? String ?: ""
                val fullId = taskScheduler.tasks.value.firstOrNull { it.id.startsWith(taskId) }?.id ?: taskId
                val success = taskScheduler.cancelTask(fullId)
                ToolResult(id = toolCall.id, output = if (success) "Task cancelled." else "Task not found: $taskId",
                    exitCode = if (success) 0 else 1, isError = !success)
            }

            // Accessibility tools — control other apps on device
            // Falls back to suggesting browser tools if service not available
            "read_screen" -> {
                val svc = MobileClawAccessibilityService.instance
                if (svc != null) {
                    ToolResult(id = toolCall.id, output = svc.dumpScreen())
                } else {
                    ToolResult(id = toolCall.id, output = "Accessibility service not enabled. Go to Settings → Accessibility → MobileClaw → Enable." +
                        if (isBrowserModeEnabled()) " Alternatively, try using browser_open/browser_read/browser_click to do this via a web browser in the background." else "",
                        exitCode = 1, isError = true)
                }
            }
            "tap_element" -> {
                val svc = MobileClawAccessibilityService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Accessibility service not enabled." +
                        if (isBrowserModeEnabled()) " Try using browser_click instead." else "",
                        exitCode = 1, isError = true)
                val text = args["text"] as? String ?: ""
                val success = svc.tapByText(text) || svc.tapByDescription(text)
                ToolResult(id = toolCall.id, output = if (success) "Tapped: $text" else "Element not found: $text",
                    exitCode = if (success) 0 else 1, isError = !success)
            }
            "tap_coordinates" -> {
                val svc = MobileClawAccessibilityService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Accessibility service not enabled." +
                        if (isBrowserModeEnabled()) " Try using browser_click instead." else "",
                        exitCode = 1, isError = true)
                val x = (args["x"] as? Number)?.toFloat() ?: 0f
                val y = (args["y"] as? Number)?.toFloat() ?: 0f
                svc.tap(x, y)
                ToolResult(id = toolCall.id, output = "Tapped at ($x, $y)")
            }
            "type_text" -> {
                val svc = MobileClawAccessibilityService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Accessibility service not enabled." +
                        if (isBrowserModeEnabled()) " Try using browser_type instead." else "",
                        exitCode = 1, isError = true)
                val text = args["text"] as? String ?: ""
                val hint = args["field_hint"] as? String
                val success = svc.typeText(text, hint)
                ToolResult(id = toolCall.id, output = if (success) "Typed: $text" else "No editable field found",
                    exitCode = if (success) 0 else 1, isError = !success)
            }
            "scroll_screen" -> {
                val svc = MobileClawAccessibilityService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Accessibility service not enabled." +
                        if (isBrowserModeEnabled()) " Try using browser_scroll instead." else "",
                        exitCode = 1, isError = true)
                val dir = args["direction"] as? String ?: "down"
                val success = if (dir == "up") svc.scrollUp() else svc.scrollDown()
                ToolResult(id = toolCall.id, output = if (success) "Scrolled $dir" else "No scrollable element found",
                    exitCode = if (success) 0 else 1, isError = !success)
            }
            "swipe" -> {
                val svc = MobileClawAccessibilityService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Accessibility service not enabled.",
                        exitCode = 1, isError = true)
                val sx = (args["start_x"] as? Number)?.toFloat() ?: 0f
                val sy = (args["start_y"] as? Number)?.toFloat() ?: 0f
                val ex = (args["end_x"] as? Number)?.toFloat() ?: 0f
                val ey = (args["end_y"] as? Number)?.toFloat() ?: 0f
                svc.swipe(sx, sy, ex, ey)
                ToolResult(id = toolCall.id, output = "Swiped from ($sx,$sy) to ($ex,$ey)")
            }
            "press_button" -> {
                val svc = MobileClawAccessibilityService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Accessibility service not enabled.",
                        exitCode = 1, isError = true)
                val button = args["button"] as? String ?: "back"
                val success = when (button) {
                    "back" -> svc.pressBack()
                    "home" -> svc.pressHome()
                    "recents" -> svc.pressRecents()
                    "notifications" -> svc.openNotifications()
                    "screenshot" -> svc.takeScreenshot()
                    else -> false
                }
                ToolResult(id = toolCall.id, output = if (success) "Pressed: $button" else "Failed to press: $button",
                    exitCode = if (success) 0 else 1, isError = !success)
            }
            "get_current_app" -> {
                val svc = MobileClawAccessibilityService.instance
                if (svc != null) {
                    ToolResult(id = toolCall.id, output = svc.getCurrentApp())
                } else {
                    ToolResult(id = toolCall.id, output = "Accessibility service not enabled." +
                        if (isBrowserModeEnabled()) " Try using browser tools instead." else "",
                        exitCode = 1, isError = true)
                }
            }

            // Headless Browser Tools
            "browser_open" -> {
                ensureBrowserService()
                val svc = com.parth.mobileclaw.browser.AgentBrowserService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Browser service not running. Enable Browser mode in Settings.", exitCode = 1, isError = true)
                val url = args["url"] as? String ?: ""
                val sessionId = args["session_id"] as? String
                val session = svc.getSession(sessionId)
                val success = session.loadUrl(url)
                ToolResult(id = toolCall.id, output = if (success) "Opened $url in session ${session.sessionId}" else "Failed to open $url", exitCode = if (success) 0 else 1, isError = !success)
            }
            "browser_read" -> {
                ensureBrowserService()
                val svc = com.parth.mobileclaw.browser.AgentBrowserService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Browser service not running. Enable Browser mode in Settings.", exitCode = 1, isError = true)
                val sessionId = args["session_id"] as? String
                val session = svc.getSession(sessionId)
                val content = session.getPageContent()
                ToolResult(id = toolCall.id, output = content)
            }
            "browser_click" -> {
                ensureBrowserService()
                val svc = com.parth.mobileclaw.browser.AgentBrowserService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Browser service not running. Enable Browser mode in Settings.", exitCode = 1, isError = true)
                val selector = args["selector_or_text"] as? String ?: ""
                val sessionId = args["session_id"] as? String
                val session = svc.getSession(sessionId)
                val success = session.clickElement(selector)
                ToolResult(id = toolCall.id, output = if (success) "Clicked '$selector'" else "Element not found", exitCode = if (success) 0 else 1, isError = !success)
            }
            "browser_type" -> {
                ensureBrowserService()
                val svc = com.parth.mobileclaw.browser.AgentBrowserService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Browser service not running. Enable Browser mode in Settings.", exitCode = 1, isError = true)
                val selector = args["selector_or_text"] as? String ?: ""
                val text = args["text"] as? String ?: ""
                val sessionId = args["session_id"] as? String
                val session = svc.getSession(sessionId)
                val success = session.typeInField(selector, text)
                ToolResult(id = toolCall.id, output = if (success) "Typed into '$selector'" else "Field not found", exitCode = if (success) 0 else 1, isError = !success)
            }
            "browser_scroll" -> {
                ensureBrowserService()
                val svc = com.parth.mobileclaw.browser.AgentBrowserService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Browser service not running. Enable Browser mode in Settings.", exitCode = 1, isError = true)
                val dir = args["direction"] as? String ?: "down"
                val sessionId = args["session_id"] as? String
                val session = svc.getSession(sessionId)
                val success = session.scroll(dir)
                ToolResult(id = toolCall.id, output = if (success) "Scrolled $dir" else "Scroll failed", exitCode = if (success) 0 else 1, isError = !success)
            }
            "browser_get_url" -> {
                ensureBrowserService()
                val svc = com.parth.mobileclaw.browser.AgentBrowserService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Browser service not running. Enable Browser mode in Settings.", exitCode = 1, isError = true)
                val sessionId = args["session_id"] as? String
                val session = svc.getSession(sessionId)
                ToolResult(id = toolCall.id, output = "URL: ${session.currentUrl}\nTitle: ${session.pageTitle}\nSession ID: ${session.sessionId}")
            }
            "browser_execute_js" -> {
                ensureBrowserService()
                val svc = com.parth.mobileclaw.browser.AgentBrowserService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Browser service not running. Enable Browser mode in Settings.", exitCode = 1, isError = true)
                val script = args["script"] as? String ?: ""
                val sessionId = args["session_id"] as? String
                val session = svc.getSession(sessionId)
                val result = session.executeJsSuspend(script)
                ToolResult(id = toolCall.id, output = result ?: "undefined")
            }
            "browser_press_enter" -> {
                ensureBrowserService()
                val svc = com.parth.mobileclaw.browser.AgentBrowserService.instance
                    ?: return ToolResult(id = toolCall.id, output = "Browser service not running. Enable Browser mode in Settings.", exitCode = 1, isError = true)
                val sessionId = args["session_id"] as? String
                val session = svc.getSession(sessionId)
                val success = session.pressEnter()
                ToolResult(id = toolCall.id, output = if (success) "Enter key pressed" else "Failed to press Enter", exitCode = if (success) 0 else 1, isError = !success)
            }

            else -> ToolResult(id = toolCall.id, output = "Unknown tool: ${toolCall.name}", exitCode = 1, isError = true)
        }

        // Add a delay for UI transitions so the LLM can reliably batch multiple UI actions
        val uiTools = setOf("launch_app", "tap_element", "tap_coordinates", "swipe", "press_button", "type_text")
        if (toolCall.name in uiTools) {
            kotlinx.coroutines.delay(1200)
        }

        return result
    }

    // MARK: - File Operations

    private fun readFile(path: String, toolCallId: String): ToolResult {
        val file = File(documentsDir, path)
        return if (file.exists()) {
            ToolResult(id = toolCallId, output = file.readText())
        } else {
            ToolResult(id = toolCallId, output = "File not found: $path", exitCode = 1, isError = true)
        }
    }

    private fun writeFile(path: String, content: String, toolCallId: String): ToolResult {
        return try {
            val file = File(documentsDir, path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolResult(id = toolCallId, output = "Written to $path")
        } catch (e: Exception) {
            ToolResult(id = toolCallId, output = "Write failed: ${e.message}", exitCode = 1, isError = true)
        }
    }

    private fun listFiles(path: String, toolCallId: String): ToolResult {
        val targetDir = if (path.isEmpty()) documentsDir else File(documentsDir, path)
        return try {
            val files = targetDir.listFiles()?.sortedBy { it.name } ?: emptyList()
            if (files.isEmpty()) {
                ToolResult(id = toolCallId, output = "(empty directory)")
            } else {
                val listing = files.joinToString("\n") { f ->
                    val icon = if (f.isDirectory) "📁" else "📄"
                    val suffix = if (f.isDirectory) "/" else ""
                    "$icon ${f.name}$suffix (${f.length()} bytes)"
                }
                ToolResult(id = toolCallId, output = listing)
            }
        } catch (e: Exception) {
            ToolResult(id = toolCallId, output = "List failed: ${e.message}", exitCode = 1, isError = true)
        }
    }

    // MARK: - Persistence

    private suspend fun addMessage(message: Message) {
        val current = _messages.value.toMutableList()
        current.add(message)
        _messages.value = current
        withContext(Dispatchers.IO) { messageDao.insert(message) }
    }

    private suspend fun updateMessage(index: Int, message: Message) {
        val current = _messages.value.toMutableList()
        current[index] = message
        _messages.value = current
        withContext(Dispatchers.IO) { messageDao.insert(message) }
    }

    fun clearMessages() {
        viewModelScope.launch {
            _messages.value = emptyList()
            withContext(Dispatchers.IO) { messageDao.clearAll() }
        }
    }

    // MARK: - Scheduled Task Execution

    suspend fun executeScheduledPrompt(prompt: String): String {
        val userMsg = Message(role = Message.Role.user, content = "⏰ Scheduled Task:\n$prompt")
        addMessage(userMsg)
        
        val conversation = mutableListOf(userMsg)
        var iteration = 0
        val maxIter = 300

        return try {
            while (iteration < maxIter) {
                iteration++
                val response = llmService.send(
                    messages = conversation,
                    systemPrompt = goClawBridge.buildSystemPrompt(),
                    tools = toolDefinitions,
                    onToken = { },
                    onToolCalls = { }
                )
                conversation.add(response)

                val toolCalls = response.toolCalls
                if (toolCalls.isNullOrEmpty()) {
                    val finalResult = response.content
                    addMessage(Message(role = Message.Role.assistant, content = finalResult))
                    return finalResult
                }

                val results = mutableListOf<ToolResult>()
                for (toolCall in toolCalls) {
                    val rawResult = executeTool(toolCall)
                    results.add(rawResult.copy(id = toolCall.id))
                }
                conversation.add(Message(role = Message.Role.tool, content = "", toolResults = results))
            }
            val fallbackResult = conversation.lastOrNull()?.content ?: "Task completed (max iterations reached)."
            addMessage(Message(role = Message.Role.assistant, content = fallbackResult))
            fallbackResult
        } catch (e: Exception) {
            val errorMsg = "Scheduled task error: ${e.message}"
            addMessage(Message(role = Message.Role.assistant, content = errorMsg))
            errorMsg
        }
    }

    // MARK: - Helpers

    private fun toolDef(name: String, description: String, properties: Map<String, Any>, required: List<String>): Map<String, Any> {
        return mapOf(
            "name" to name,
            "description" to description,
            "input_schema" to mapOf(
                "type" to "object",
                "properties" to properties,
                "required" to required
            )
        )
    }

    private fun propStr(desc: String) = mapOf("type" to "string", "description" to desc)

    private fun showChatNotification(messageText: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "mobileclaw_chat",
                "Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "mobileclaw_chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MobileClaw")
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(2002, notification)
    }
}

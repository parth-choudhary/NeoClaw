package com.parth.mobileclaw.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parth.mobileclaw.engine.AgentOrchestrator
import com.parth.mobileclaw.llm.LLMService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    orchestrator: AgentOrchestrator,
    onBack: () -> Unit,
    onOpenSkills: () -> Unit = {},
    onAddSkill: () -> Unit = {},
    onOpenBrowserLogin: () -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    val linuxReady by orchestrator.linuxExecutor.isReady.collectAsState()
    val skills by orchestrator.goClawBridge.loadedSkills.collectAsState()
    var selectedProvider by remember { mutableStateOf(orchestrator.llmService.activeProvider) }
    var selectedModel by remember { mutableStateOf(orchestrator.llmService.activeModel) }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = cs.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Header Layout like Element X details screen
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = cs.secondary,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(Icons.Default.Settings, null, tint = cs.onPrimary, modifier = Modifier.padding(16.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    color = cs.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Manage MobileClaw Preferences",
                    color = cs.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }

            HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp)

            // Engine Status
            SettingsSection("Engine Status") {
                SettingsRow(
                    icon = Icons.Default.Computer,
                    label = "Linux Environment",
                    value = if (linuxReady) "Ready ✅" else "Starting… ⏳"
                )
            }

            // Provider Selection
            SettingsSection("LLM Provider") {
                LLMService.Provider.entries.forEach { provider ->
                    val isSelected = provider == selectedProvider
                    val displayName = if (provider == LLMService.Provider.CUSTOM) {
                        orchestrator.llmService.customDisplayName?.takeIf { it.isNotBlank() } ?: provider.displayName
                    } else {
                        provider.displayName
                    }
                    
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedProvider = provider
                                    orchestrator.llmService.activeProvider = provider
                                    
                                    if (provider == LLMService.Provider.CUSTOM) {
                                        val prefs = orchestrator.getApplication<android.app.Application>()
                                            .getSharedPreferences("mobileclaw_prefs", android.content.Context.MODE_PRIVATE)
                                        selectedModel = prefs.getString("customModel", provider.defaultModels.first()) ?: provider.defaultModels.first()
                                    } else {
                                        selectedModel = provider.defaultModels.first()
                                    }
                                    
                                    orchestrator.llmService.activeModel = selectedModel
                                    saveProviderPrefs(orchestrator, provider, selectedModel)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = cs.secondary)
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(displayName, color = cs.onSurface, fontSize = 16.sp)
                        }

                        if (isSelected && provider == LLMService.Provider.CUSTOM) {
                            var customName by remember { mutableStateOf(orchestrator.llmService.customDisplayName ?: "") }
                            var customUrl by remember { mutableStateOf(orchestrator.llmService.customBaseURL ?: "") }

                            Column(modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)) {
                                OutlinedTextField(
                                    value = customName,
                                    onValueChange = { 
                                        customName = it
                                        orchestrator.llmService.customDisplayName = it
                                        saveCustomProviderPrefs(orchestrator, it, customUrl)
                                    },
                                    label = { Text("Provider Name", fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = cs.surface,
                                        unfocusedContainerColor = cs.surface
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = customUrl,
                                    onValueChange = { 
                                        customUrl = it
                                        orchestrator.llmService.customBaseURL = it
                                        saveCustomProviderPrefs(orchestrator, customName, it)
                                    },
                                    label = { Text("Base URL (OpenAI Compatible)", fontSize = 12.sp) },
                                    placeholder = { Text("https://...", fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Next
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = cs.surface,
                                        unfocusedContainerColor = cs.surface
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = selectedModel,
                                    onValueChange = { 
                                        selectedModel = it
                                        orchestrator.llmService.activeModel = it
                                        saveProviderPrefs(orchestrator, provider, it)
                                    },
                                    label = { Text("Model Name", fontSize = 12.sp) },
                                    placeholder = { Text("e.g. llama3, gpt-4o", fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = cs.surface,
                                        unfocusedContainerColor = cs.surface
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Model Selection
            SettingsSection("Model") {
                var modelSearch by remember { mutableStateOf("") }
                val allModels = selectedProvider.defaultModels
                val filteredModels = remember(modelSearch, allModels) {
                    if (modelSearch.isBlank()) allModels
                    else allModels.filter { it.contains(modelSearch, ignoreCase = true) }
                }

                // Search field
                OutlinedTextField(
                    value = modelSearch,
                    onValueChange = { modelSearch = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Search models…", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (modelSearch.isNotEmpty()) {
                            IconButton(onClick = { modelSearch = "" }) {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = cs.outline,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = cs.surface,
                        unfocusedContainerColor = cs.surface,
                        focusedTextColor = cs.onSurface,
                        unfocusedTextColor = cs.onSurface,
                        cursorColor = cs.secondary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                )

                // Model list (max height to keep it scrollable)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    filteredModels.forEach { model ->
                        val isSelected = model == selectedModel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedModel = model
                                    orchestrator.llmService.activeModel = model
                                    saveProviderPrefs(orchestrator, selectedProvider, model)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                if (isSelected) {
                                    Icon(Icons.Default.Check, null, tint = cs.secondary, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Text(
                                model,
                                color = if (isSelected) cs.secondary else cs.onSurface,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (filteredModels.isEmpty() && modelSearch.isNotBlank()) {
                        // Allow custom model entry
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedModel = modelSearch
                                    orchestrator.llmService.activeModel = modelSearch
                                    saveProviderPrefs(orchestrator, selectedProvider, modelSearch)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, null, tint = cs.secondary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(
                                "Use \"$modelSearch\"",
                                color = cs.secondary,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // API Key
            SettingsSection("API Key") {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    APIKeyField(orchestrator = orchestrator, provider = selectedProvider)
                }
            }

            // Skills
            SettingsSection("Skills (${skills.size} loaded)", actionIcon = Icons.Default.Add, onActionClick = onAddSkill) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSkills() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Extension, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Manage Skills", color = cs.onSurface, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }

            // Agent Interaction Mode
            SettingsSection("Agent Interaction Mode") {
                val prefs = orchestrator.getApplication<android.app.Application>()
                    .getSharedPreferences("mobileclaw_prefs", android.content.Context.MODE_PRIVATE)
                var currentInteractionMode by remember { mutableStateOf(prefs.getString("agent_interaction_mode", "accessibility") ?: "accessibility") }

                val modes = listOf("accessibility" to "Accessibility (Foreground apps)", "browser" to "Browser (Background web)")
                modes.forEach { (modeId, displayName) ->
                    val isSelected = currentInteractionMode == modeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                currentInteractionMode = modeId
                                prefs.edit().putString("agent_interaction_mode", modeId).apply()
                                // Start/stop browser service and invalidate cached prompt
                                if (modeId == "browser") {
                                    com.parth.mobileclaw.browser.AgentBrowserService.start(orchestrator.getApplication())
                                }
                                orchestrator.goClawBridge.invalidateCache()
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = cs.secondary)
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(displayName, color = cs.onSurface, fontSize = 16.sp)
                    }
                }
                
                if (currentInteractionMode == "browser") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenBrowserLogin() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Public, null, tint = cs.secondary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Manage Login Sessions", color = cs.secondary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ChevronRight, null, tint = cs.secondary, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        "ℹ️ The agent will browse the web in the background. Log into websites first so it can use your active sessions.",
                        color = cs.onSurfaceVariant, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                } else {
                    Text(
                        "ℹ️ The agent will read your screen and physically tap on apps. This interrupts your phone use.",
                        color = cs.onSurfaceVariant, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            // Voice Input Settings
            SettingsSection("Voice Input Mode") {
                val prefs = orchestrator.getApplication<android.app.Application>()
                    .getSharedPreferences("mobileclaw_prefs", android.content.Context.MODE_PRIVATE)
                var currentMode by remember { mutableStateOf(prefs.getString("voice_input_mode", "native") ?: "native") }

                val modes = listOf("native" to "Native Android", "whisper" to "OpenAI Whisper")
                modes.forEach { (modeId, displayName) ->
                    val isSelected = currentMode == modeId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                currentMode = modeId
                                prefs.edit().putString("voice_input_mode", modeId).apply()
                                orchestrator.speechService.setMode(modeId)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = cs.secondary)
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(displayName, color = cs.onSurface, fontSize = 16.sp)
                    }
                }
                
                if (currentMode == "whisper" && !orchestrator.secureStorage.hasKey(LLMService.Provider.OPENAI.keychainKey)) {
                    Text(
                        "⚠️ OpenAI Whisper requires an OpenAI API Key. Please add it above.",
                        color = cs.error, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                } else if (currentMode == "whisper") {
                    Text(
                        "ℹ️ Audio will be sent to api.openai.com for transcription.",
                        color = cs.onSurfaceVariant, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            // Accessibility
            SettingsSection("Accessibility") {
                val a11yEnabled by com.parth.mobileclaw.accessibility.MobileClawAccessibilityService.isEnabled.collectAsState()
                SettingsRow(
                    icon = Icons.Default.Accessibility,
                    label = "App Control",
                    value = if (a11yEnabled) "Enabled ✅" else "Disabled"
                )
                if (!a11yEnabled) {
                    Text(
                        "Enable in: Settings → Accessibility → MobileClaw",
                        color = cs.onSurfaceVariant, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            // Data Management
            SettingsSection("Data") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { orchestrator.clearMessages() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Delete, null, tint = cs.error, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Clear Conversation History", color = cs.error, fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun APIKeyField(orchestrator: AgentOrchestrator, provider: LLMService.Provider) {
    val cs = MaterialTheme.colorScheme
    var apiKey by remember(provider) {
        mutableStateOf(orchestrator.secureStorage.read(provider.keychainKey) ?: "")
    }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember(provider) { mutableStateOf(orchestrator.secureStorage.hasKey(provider.keychainKey)) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { 
                val name = if (provider == LLMService.Provider.CUSTOM) {
                    orchestrator.llmService.customDisplayName?.takeIf { it.isNotBlank() } ?: provider.displayName
                } else {
                    provider.displayName
                }
                Text("$name API Key", fontSize = 12.sp) 
            },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                Row {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = {
                        if (apiKey.isNotBlank()) {
                            orchestrator.secureStorage.save(provider.keychainKey, apiKey)
                            saved = true
                        }
                    }) {
                        Icon(Icons.Default.Save, null, tint = cs.secondary, modifier = Modifier.size(18.dp))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = cs.onSurface,
                unfocusedTextColor = cs.onSurface,
                focusedLabelColor = cs.secondary,
                unfocusedLabelColor = cs.onSurfaceVariant,
                cursorColor = cs.onBackground,
                focusedContainerColor = cs.surface,
                unfocusedContainerColor = cs.surface
            ),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        if (saved) {
            Spacer(Modifier.height(4.dp))
            Text(
                "✅ Key saved securely", 
                color = cs.secondary, 
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onActionClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = cs.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (actionIcon != null && onActionClick != null) {
                IconButton(onClick = onActionClick, modifier = Modifier.size(32.dp)) {
                    Icon(actionIcon, contentDescription = null, tint = cs.secondary)
                }
            }
        }
        content()
        HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = cs.onSurface, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(value, color = cs.onSurfaceVariant, fontSize = 14.sp)
    }
}

private fun saveProviderPrefs(orchestrator: AgentOrchestrator, provider: LLMService.Provider, model: String) {
    val editor = orchestrator.getApplication<android.app.Application>()
        .getSharedPreferences("mobileclaw_prefs", android.content.Context.MODE_PRIVATE)
        .edit()
        
    editor.putString("activeProvider", provider.name)
    editor.putString("activeModel", model)
    
    if (provider == LLMService.Provider.CUSTOM) {
        editor.putString("customModel", model)
    }
    
    editor.apply()
}

private fun saveCustomProviderPrefs(orchestrator: AgentOrchestrator, name: String, url: String) {
    orchestrator.getApplication<android.app.Application>()
        .getSharedPreferences("mobileclaw_prefs", android.content.Context.MODE_PRIVATE)
        .edit()
        .putString("customDisplayName", name)
        .putString("customBaseURL", url)
        .apply()
}

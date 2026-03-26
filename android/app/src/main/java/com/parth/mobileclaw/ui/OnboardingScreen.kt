package com.parth.mobileclaw.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parth.mobileclaw.engine.AgentOrchestrator
import com.parth.mobileclaw.llm.LLMService

@Composable
fun OnboardingScreen(
    orchestrator: AgentOrchestrator,
    onComplete: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    var selectedProvider by remember { mutableStateOf(LLMService.Provider.ANTHROPIC) }
    var apiKey by remember { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme

    val isWelcome = step == 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isWelcome) {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFC7ECF0), // colorCyan400
                            Color(0xFFBAD5FC), // colorBlue500
                            cs.background
                        ),
                        startY = 0f,
                        endY = 1500f
                    )
                } else {
                    Brush.verticalGradient(listOf(cs.background, cs.background))
                }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = if (isWelcome) Arrangement.Bottom else Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isWelcome) {
                // Back button for other steps
                Row(modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { step -= 1 }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = cs.onBackground)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            when (step) {
                0 -> WelcomeStep { step = 1 }
                1 -> PermissionStep { step = 2 }
                2 -> ProviderStep(selectedProvider) { provider ->
                    selectedProvider = provider
                    step = 3
                }
                3 -> ApiKeyStep(orchestrator, selectedProvider, apiKey, { apiKey = it }) {
                    orchestrator.llmService.activeProvider = selectedProvider
                    if (selectedProvider != LLMService.Provider.CUSTOM) {
                        orchestrator.llmService.activeModel = selectedProvider.defaultModels.first()
                    }
                    orchestrator.secureStorage.save(selectedProvider.keychainKey, apiKey)

                    val ctx = orchestrator.getApplication<android.app.Application>()
                    val editor = ctx.getSharedPreferences("mobileclaw_prefs", android.content.Context.MODE_PRIVATE).edit()
                    editor.putString("activeProvider", selectedProvider.name)
                    editor.putString("activeModel", orchestrator.llmService.activeModel)
                    if (selectedProvider == LLMService.Provider.CUSTOM) {
                        editor.putString("customModel", orchestrator.llmService.activeModel)
                    }
                    editor.putBoolean("onboarding_complete", true)
                    editor.apply()

                    onComplete()
                }
            }
        }
    }
}

@Composable
fun WelcomeStep(onContinue: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(Modifier.weight(1f))

        // App Icon Mockup (like Element X logo)
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.4f))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color(0xFF129A78)), // Element Green manually since it's logo
                contentAlignment = Alignment.Center
            ) {
                Text("🦀", fontSize = 28.sp)
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "The AI that actually does things",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            color = cs.onBackground,
            textAlign = TextAlign.Start
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Clears your inbox, sends emails, manages your calendar, checks you in for flights. All from the chat app you already use.",
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Start
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = cs.primary)
        ) {
            Icon(Icons.Default.Bolt, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Get Started", color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(Modifier.height(16.dp))


        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun PermissionStep(onContinue: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    
    var hasNotifPermission by remember { 
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        ) 
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> 
        hasNotifPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Permission denied. Please enable in Settings.", Toast.LENGTH_LONG).show()
        }
    }

    // Refresh accessibility state when returning to this screen
    var hasAccessibilityPermission by remember { mutableStateOf(false) }

    val updateAccessibilityState = {
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        hasAccessibilityPermission = enabledServices?.contains(context.packageName) == true
    }

    LaunchedEffect(Unit) {
        updateAccessibilityState()
    }

    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Notifications, null, tint = cs.onBackground, modifier = Modifier.size(32.dp))
        }
        
        Spacer(Modifier.height(24.dp))
        Text(
            "Enable Notifications",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = cs.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Get alerts when long running AI tasks complete.",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        PermissionCard(
            title = "Notifications", 
            icon = Icons.Default.Notifications,
            isGranted = hasNotifPermission
        ) {
            if (hasNotifPermission) return@PermissionCard
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // If it fails silently without proper callback, user needs to go to settings
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        Spacer(Modifier.height(16.dp))

        PermissionCard(
            title = "Accessibility",
            icon = Icons.Default.SettingsAccessibility,
            isGranted = hasAccessibilityPermission
        ) {
            if (hasAccessibilityPermission) return@PermissionCard
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            context.startActivity(intent)
        }

        if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }) {
                Text("Open OS Settings Manually")
            }
        }

        Spacer(Modifier.weight(1f))
        
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = cs.primary)
        ) {
            Text("Continue", color = cs.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Skip for now", color = cs.onBackground, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PermissionCard(title: String, icon: ImageVector, isGranted: Boolean = false, onGrant: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !isGranted) { onGrant() },
        color = cs.surface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (isGranted) cs.primary else cs.onBackground, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, color = cs.onSurface, modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            
            if (isGranted) {
                Icon(Icons.Default.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(20.dp))
            } else {
                Text("Grant", color = cs.onBackground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun ProviderStep(selected: LLMService.Provider, onSelect: (LLMService.Provider) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Psychology, null, tint = cs.onBackground, modifier = Modifier.size(32.dp))
        }
        
        Spacer(Modifier.height(24.dp))
        Text(
            "Select your Provider",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = cs.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "What cloud is the brain hosted on?",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        LLMService.Provider.entries.forEach { provider ->
            val isSelected = provider == selected
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(provider) },
                color = cs.surface,
                shape = RoundedCornerShape(16.dp),
                border = if (isSelected) BorderStroke(2.dp, cs.onBackground) else null
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(provider.displayName, color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(provider.defaultModels.first(), color = cs.onSurfaceVariant, fontSize = 13.sp)
                    }
                    if (isSelected) {
                        Icon(Icons.Default.CheckCircle, null, tint = cs.onBackground)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ApiKeyStep(
    orchestrator: AgentOrchestrator,
    provider: LLMService.Provider,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onComplete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Key, null, tint = cs.onBackground, modifier = Modifier.size(32.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Enter your API Key",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = cs.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your key is securely stored in your Keychain.",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            if (provider == LLMService.Provider.CUSTOM) {
                val ctx = orchestrator.getApplication<android.app.Application>()
                val prefs = ctx.getSharedPreferences("mobileclaw_prefs", android.content.Context.MODE_PRIVATE)
                
                var customName by remember { mutableStateOf(orchestrator.llmService.customDisplayName ?: "") }
                var customUrl by remember { mutableStateOf(orchestrator.llmService.customBaseURL ?: "") }

                Text("Provider Name", style = MaterialTheme.typography.labelSmall, color = cs.onBackground, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customName,
                    onValueChange = { 
                        customName = it
                        orchestrator.llmService.customDisplayName = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("My Local LLM", color = cs.onSurfaceVariant) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = cs.surface,
                        unfocusedContainerColor = cs.surface,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                Spacer(Modifier.height(16.dp))

                Text("Base URL", style = MaterialTheme.typography.labelSmall, color = cs.onBackground, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { 
                        customUrl = it
                        orchestrator.llmService.customBaseURL = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://localhost:8080/v1", color = cs.onSurfaceVariant) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = cs.surface,
                        unfocusedContainerColor = cs.surface,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                Spacer(Modifier.height(16.dp))

                var customModel by remember { mutableStateOf(prefs.getString("customModel", "gpt-4o") ?: "gpt-4o") }
                
                LaunchedEffect(Unit) {
                    orchestrator.llmService.activeModel = customModel
                }

                Text("Model Name", style = MaterialTheme.typography.labelSmall, color = cs.onBackground, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customModel,
                    onValueChange = { 
                        customModel = it
                        orchestrator.llmService.activeModel = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. gpt-4o, llama3", color = cs.onSurfaceVariant) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = cs.surface,
                        unfocusedContainerColor = cs.surface,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                Spacer(Modifier.height(16.dp))
            }

            Text(
                if (provider == LLMService.Provider.CUSTOM) "API Key (Optional)" else "API Key",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onBackground,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("sk-...", color = cs.onSurfaceVariant) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = cs.onSurface,
                    unfocusedTextColor = cs.onSurface,
                    cursorColor = cs.onBackground,
                    focusedContainerColor = cs.surface,
                    unfocusedContainerColor = cs.surface
                )
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (provider == LLMService.Provider.CUSTOM) "Enter a key if required by your provider." else "Enter a valid key.",
                color = cs.onSurfaceVariant, fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(32.dp))
        
        val canContinue = if (provider == LLMService.Provider.CUSTOM) {
            orchestrator.llmService.customBaseURL?.isNotBlank() == true
        } else {
            apiKey.length > 10
        }

        Button(
            onClick = {
                if (provider == LLMService.Provider.CUSTOM) {
                    val ctx = orchestrator.getApplication<android.app.Application>()
                    ctx.getSharedPreferences("mobileclaw_prefs", android.content.Context.MODE_PRIVATE).edit()
                        .putString("customDisplayName", orchestrator.llmService.customDisplayName)
                        .putString("customBaseURL", orchestrator.llmService.customBaseURL)
                        .apply()
                }
                onComplete()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = canContinue,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = cs.primary,
                disabledContainerColor = cs.surfaceVariant
            )
        ) {
            Text("Continue", color = if(canContinue) cs.onPrimary else cs.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
}

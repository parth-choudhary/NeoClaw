package com.parth.mobileclaw.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parth.mobileclaw.engine.AgentOrchestrator
import com.parth.mobileclaw.models.Skill
import com.parth.mobileclaw.models.SkillSource
import com.parth.mobileclaw.skills.SkillInstaller
import com.parth.mobileclaw.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

/**
 * Skill browser — discover, install, and manage skills.
 * Element X / Compound styled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillBrowserScreen(
    orchestrator: AgentOrchestrator,
    onBack: () -> Unit,
    initialShowInstallSheet: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val skills by orchestrator.goClawBridge.loadedSkills.collectAsState()
    var showInstallSheet by remember { mutableStateOf(initialShowInstallSheet) }
    var gitUrl by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var installSuccess by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val activeSkills = skills.filter { it.isActive }
    val inactiveSkills = skills.filter { !it.isActive }

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
                actions = {
                    IconButton(onClick = { orchestrator.goClawBridge.reloadSkills() }) {
                        Icon(Icons.Default.Refresh, "Reload", tint = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = { showInstallSheet = true }) {
                        Icon(Icons.Default.Add, "Install", tint = cs.secondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = cs.secondary,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(Icons.Default.Extension, null, tint = cs.onPrimary, modifier = Modifier.padding(16.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Skills",
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                        color = cs.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${skills.size} skills loaded",
                        color = cs.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
                HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp)
            }
            // Active skills
            if (activeSkills.isNotEmpty()) {
                item {
                    SectionHeader("Active Skills", activeSkills.size)
                }
                items(activeSkills, key = { it.name }) { skill ->
                    SkillCard(skill = skill, onUninstall = {
                        val installer = SkillInstaller(
                            orchestrator.linuxExecutor,
                            File(orchestrator.getApplication<android.app.Application>().filesDir, "skills")
                        )
                        installer.uninstall(skill.name)
                        orchestrator.goClawBridge.reloadSkills()
                    })
                }
            }

            // Inactive skills
            if (inactiveSkills.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader("Unavailable (missing deps)", inactiveSkills.size)
                }
                items(inactiveSkills, key = { it.name }) { skill ->
                    SkillCard(skill = skill, dimmed = true, onUninstall = null)
                }
            }

            // Empty state
            if (skills.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🧩", fontSize = 48.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("No skills loaded", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap + to install from Git", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Install dialog
        if (showInstallSheet) {
            AlertDialog(
                onDismissRequest = { showInstallSheet = false },
                containerColor = cs.surface,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text("Install Skill", color = cs.onSurface, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column {
                        Text(
                            "Enter a Git repository URL containing a SKILL.md file.",
                            color = cs.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))

                        OutlinedTextField(
                            value = gitUrl,
                            onValueChange = { gitUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    "https://github.com/user/skill.git",
                                    color = cs.onSurfaceVariant.copy(alpha = 0.5f),
                                    fontSize = 13.sp
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = cs.secondary,
                                unfocusedBorderColor = cs.outline,
                                focusedTextColor = cs.onSurface,
                                unfocusedTextColor = cs.onSurface,
                                cursorColor = cs.secondary
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                if (gitUrl.isNotBlank() && !isInstalling) {
                                    scope.launch {
                                        installFromGit(orchestrator, gitUrl,
                                            onStart = { isInstalling = true; installError = null },
                                            onSuccess = { name ->
                                                isInstalling = false
                                                installSuccess = name
                                                showInstallSheet = false
                                                gitUrl = ""
                                            },
                                            onError = { err ->
                                                isInstalling = false
                                                installError = err
                                            }
                                        )
                                    }
                                }
                            })
                        )

                        if (isInstalling) {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = cs.secondary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Cloning repository…", color = cs.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }

                        installError?.let { err ->
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                color = cs.error.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, null, tint = cs.error,
                                        modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(err, color = cs.error, fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Skills placed in Documents/skills/ are also loaded automatically.",
                            color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                installFromGit(orchestrator, gitUrl,
                                    onStart = { isInstalling = true; installError = null },
                                    onSuccess = { name ->
                                        isInstalling = false
                                        installSuccess = name
                                        showInstallSheet = false
                                        gitUrl = ""
                                    },
                                    onError = { err ->
                                        isInstalling = false
                                        installError = err
                                    }
                                )
                            }
                        },
                        enabled = gitUrl.isNotBlank() && !isInstalling,
                        colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text("Install", color = cs.onPrimary, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showInstallSheet = false }) {
                        Text("Cancel", color = cs.onSurfaceVariant)
                    }
                }
            )
        }

        // Success snackbar
        installSuccess?.let { name ->
            LaunchedEffect(name) {
                kotlinx.coroutines.delay(2000)
                installSuccess = null
            }
        }
    }
}

// MARK: - Skill Card

@Composable
fun SkillCard(
    skill: Skill,
    dimmed: Boolean = false,
    onUninstall: (() -> Unit)?
) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (dimmed) 0.5f else 1f

    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Name
                Text(
                    skill.name,
                    color = cs.onSurface.copy(alpha = alpha),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )

                // Source badge
                val (sourceLabel, sourceColor) = when (skill.source) {
                    is SkillSource.BuiltIn -> "Built-in" to cs.tertiary
                    is SkillSource.Local -> "Local" to Color(0xFFF97316)
                    is SkillSource.ClawdHub -> "ClawdHub" to cs.secondary
                    is SkillSource.GitRepo -> "Git" to cs.secondary
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = sourceColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        sourceLabel,
                        fontSize = 10.sp,
                        color = sourceColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(Modifier.width(6.dp))

                // Status dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (skill.isActive) cs.secondary else cs.onSurfaceVariant.copy(alpha = 0.3f))
                )

                // Uninstall (only for non-built-in)
                if (onUninstall != null && skill.source !is SkillSource.BuiltIn) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onUninstall,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, tint = cs.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Description
            if (skill.description.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    skill.description,
                    color = cs.onSurfaceVariant.copy(alpha = alpha),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Required binaries
            val bins = skill.metadata?.requires?.bins
            if (!bins.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, tint = cs.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        bins.joinToString(", "),
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp, modifier = Modifier.padding(start = 16.dp))
    }
}

// MARK: - Section Header

@Composable
fun SectionHeader(title: String, count: Int) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = cs.onSurfaceVariant,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$count",
            fontSize = 11.sp,
            color = cs.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

// MARK: - Install Helper

private suspend fun installFromGit(
    orchestrator: AgentOrchestrator,
    url: String,
    onStart: () -> Unit,
    onSuccess: (String) -> Unit,
    onError: (String) -> Unit
) {
    onStart()
    val installer = SkillInstaller(
        orchestrator.linuxExecutor,
        File(orchestrator.getApplication<android.app.Application>().filesDir, "skills")
    )

    val result = installer.installFromGit(url)
    result.fold(
        onSuccess = { skill ->
            orchestrator.goClawBridge.reloadSkills()
            onSuccess(skill.name)
        },
        onFailure = { error ->
            onError(error.message ?: "Unknown error")
        }
    )
}

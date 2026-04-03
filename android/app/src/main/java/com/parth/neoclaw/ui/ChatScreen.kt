package com.parth.neoclaw.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import dev.jeziellago.compose.markdowntext.MarkdownText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parth.neoclaw.engine.AgentOrchestrator
import com.parth.neoclaw.models.Message
import com.parth.neoclaw.ui.theme.*
import kotlinx.coroutines.launch
import android.Manifest
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.parth.neoclaw.ui.utils.Attachment
import com.parth.neoclaw.ui.utils.copyAttachmentToDocuments
import com.parth.neoclaw.ui.utils.fetchAndAppendLocation
import com.parth.neoclaw.ui.utils.processContact
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import java.io.File
import androidx.compose.ui.layout.onSizeChanged
/**
 * Main chat screen — Element X / Compound styled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    orchestrator: AgentOrchestrator,
    onOpenSettings: () -> Unit
) {
    val messages by orchestrator.messages.collectAsState()
    val isProcessing by orchestrator.isProcessing.collectAsState()
    val currentTool by orchestrator.currentToolName.collectAsState()
    val linuxReady by orchestrator.linuxExecutor.isReady.collectAsState()
    val bootLogs by orchestrator.linuxExecutor.bootLogs.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showTerminal by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val cs = MaterialTheme.colorScheme

    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                orchestrator.isChatVisible.value = true
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(2002)
            } else if (event == Lifecycle.Event.ON_STOP) {
                orchestrator.isChatVisible.value = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-scroll to bottom when messages change or keyboard opens
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    LaunchedEffect(messages.size, isProcessing, imeBottom) {
        if (messages.isNotEmpty()) {
            val lastIndex = messages.size + (if (isProcessing) 1 else 0)
            listState.animateScrollToItem(lastIndex)
        }
    }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = cs.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.SmartToy, null, modifier = Modifier.padding(6.dp), tint = cs.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "NeoClaw",
                            fontWeight = FontWeight.ExtraBold,
                            color = cs.onBackground,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusDot(isReady = linuxReady)
                    }
                },
                actions = {
                    IconButton(onClick = { showTerminal = !showTerminal }) {
                        Icon(Icons.Default.Terminal, "Terminal", tint = cs.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings", tint = cs.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Terminal log panel
            AnimatedVisibility(visible = showTerminal) {
                TerminalPanel(logs = bootLogs)
            }

            // Messages
            if (messages.isEmpty()) {
                EmptyState(
                    modifier = Modifier.weight(1f),
                    onSuggestionClick = { suggestion ->
                        inputText = suggestion
                    }
                )
            } else {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            MessageBubble(message = message)
                        }

                        // Typing indicator
                        if (isProcessing) {
                            item {
                                TypingIndicator(currentTool = currentTool)
                            }
                        }

                        // Invisible spacer so we can scroll beyond the absolute bottom of last element
                        item {
                            Spacer(modifier = Modifier.height(1.dp))
                        }
                    }

                    val showScrollToBottom by remember { 
                        derivedStateOf { 
                            if (!listState.canScrollForward) return@derivedStateOf false
                            val layoutInfo = listState.layoutInfo
                            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
                            val totalItems = layoutInfo.totalItemsCount
                            
                            val threshold = 150 // Scroll buffer in pixels
                            if (lastVisible.index >= totalItems - 3) {
                                // Close to bottom, check pixel distance
                                val bottomOffset = lastVisible.offset + lastVisible.size
                                val viewportBottom = layoutInfo.viewportEndOffset
                                (bottomOffset - viewportBottom) > threshold
                            } else {
                                true
                            }
                        } 
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = showScrollToBottom,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 16.dp, end = 16.dp)
                    ) {
                        SmallFloatingActionButton(
                            onClick = {
                                scope.launch {
                                    val count = listState.layoutInfo.totalItemsCount
                                    if (count > 0) {
                                        listState.animateScrollToItem(count - 1)
                                    }
                                }
                            },
                            shape = CircleShape,
                            containerColor = cs.primaryContainer,
                            contentColor = cs.onPrimaryContainer
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll down")
                        }
                    }
                }
            }

            val showSlashCommands = inputText.startsWith("/") && !inputText.contains(" ")
            val slashQuery = if (showSlashCommands) inputText.drop(1) else ""
            val filteredSkills = remember(slashQuery, orchestrator.goClawBridge.loadedSkills) {
                if (!showSlashCommands) emptyList()
                else orchestrator.goClawBridge.loadedSkills.value.filter {
                    it.name.contains(slashQuery, ignoreCase = true) || 
                    it.description.contains(slashQuery, ignoreCase = true)
                }
            }

            AnimatedVisibility(
                visible = showSlashCommands && filteredSkills.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = fadeOut()
            ) {
                SlashCommandPopup(
                    skills = filteredSkills,
                    onSelect = { skill ->
                        inputText = "/${skill.name} "
                    }
                )
            }

            // Input bar
            InputBar(
                orchestrator = orchestrator,
                text = inputText,
                onTextChange = { inputText = it },
                onSend = { finalText ->
                    if (finalText.isNotBlank()) {
                        orchestrator.sendMessage(finalText.trim())
                        inputText = ""
                        focusManager.clearFocus()
                    }
                },
                onStop = { orchestrator.stopProcessing() },
                isProcessing = isProcessing
            )
        }
    }
}

// MARK: - Message Bubble

@Composable
fun MessageBubble(message: Message) {
    if (message.content.isEmpty() && message.toolCalls.isNullOrEmpty() && message.role != Message.Role.tool) {
        return
    }

    val isUser = message.role == Message.Role.user
    val isTool = message.role == Message.Role.tool
    val cs = MaterialTheme.colorScheme

    var showMenu by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var pressOffset by remember { mutableStateOf(androidx.compose.ui.unit.DpOffset.Zero) }
    var itemHeight by remember { mutableIntStateOf(0) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        val bgColor = when {
            isUser -> BubbleMe
            isTool -> BubbleTool
            message.content.startsWith("Error:") -> cs.error.copy(alpha = 0.12f)
            else -> BubbleOther
        }

        val maxWidth = if (isUser) 0.85f else 0.95f

        Box(modifier = Modifier.onSizeChanged { itemHeight = it.height }) {
            Column(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .fillMaxWidth(maxWidth)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(bgColor)
                    .pointerInput(Unit) { 
                        detectTapGestures(onLongPress = { offset ->
                            pressOffset = androidx.compose.ui.unit.DpOffset(
                                with(density) { offset.x.toDp() },
                                with(density) { (offset.y - itemHeight).toDp() }
                            )
                            showMenu = true 
                        }) 
                    }
                    .padding(12.dp)
            ) {
            // Tool label
            if (isTool) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Build, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Tool Result", fontSize = 11.sp, color = cs.secondary, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
            }

            // Tool call labels
            if (message.role == Message.Role.assistant && !message.toolCalls.isNullOrEmpty()) {
                for (tc in message.toolCalls!!) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cs.secondaryContainer,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tc.name, fontSize = 12.sp, color = cs.secondary, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Content
            val content = message.content
            if (content.isNotEmpty()) {
                if (isTool) {
                    Text(
                        text = content,
                        color = cs.secondary.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else if (content.startsWith("Error:")) {
                    Text(
                        text = content,
                        color = cs.error,
                        fontSize = 14.sp
                    )
                } else {
                    MarkdownText(
                        markdown = content,
                        color = cs.onSurface,
                        fontSize = 14.sp
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            offset = pressOffset,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                    clipboardManager.setText(AnnotatedString(message.content))
                    showMenu = false
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
            )
        }
    }
}
}

// MARK: - Code Block

@Composable
fun CodeBlock(language: String, code: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BubbleCode)
    ) {
        if (language.isNotEmpty()) {
            Text(
                text = language,
                fontSize = 10.sp,
                color = cs.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
            HorizontalDivider(color = cs.outline, thickness = 1.dp)
        }
        Text(
            text = code,
            fontSize = 12.sp,
            color = cs.secondary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
}

// MARK: - Typing Indicator

@Composable
fun TypingIndicator(currentTool: String?) {
    val cs = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Row(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BubbleOther)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(cs.secondary.copy(alpha = alpha * (0.5f + i * 0.2f)))
                )
            }
        }
        if (currentTool != null) {
            Spacer(Modifier.width(10.dp))
            Text("$currentTool…", color = cs.onSurfaceVariant, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// MARK: - Terminal Panel

@Composable
fun TerminalPanel(logs: List<String>) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .background(cs.surfaceVariant)
            .padding(10.dp)
    ) {
        Text("TERMINAL", fontSize = 10.sp, color = cs.secondary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        HorizontalDivider(color = cs.outline, modifier = Modifier.padding(vertical = 6.dp))
        val scrollState = rememberScrollState()
        Column(modifier = Modifier.verticalScroll(scrollState)) {
            for (log in logs) {
                Text(log, fontSize = 10.sp, color = cs.secondary.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// MARK: - Empty State

@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    onSuggestionClick: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val suggestions = listOf(
        "What can you do?",
        "Compress a video to under 10MB",
        "Clone a git repo and summarize it",
        "What's my battery level?"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "🦀",
            fontSize = 56.sp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "NeoClaw",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = cs.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Your on-device AI agent",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(32.dp))

        suggestions.forEach { suggestion ->
            OutlinedButton(
                onClick = { onSuggestionClick(suggestion) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, cs.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = cs.surface,
                    contentColor = cs.onSurface
                )
            ) {
                Text(suggestion, fontSize = 14.sp)
            }
        }
    }
}

// MARK: - Input Bar

@Composable
fun InputBar(
    orchestrator: AgentOrchestrator,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    isProcessing: Boolean
) {
    val cs = MaterialTheme.colorScheme
    var showAttachmentMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attachments = remember { mutableStateListOf<Attachment>() }
    
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { scope.launch { attachments.add(copyAttachmentToDocuments(context, it, "image")) } }
    }
    
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { scope.launch { attachments.add(copyAttachmentToDocuments(context, it, "document")) } }
    }
    
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            cameraUri?.let { scope.launch { attachments.add(copyAttachmentToDocuments(context, it, "photo")) } }
        }
    }
    
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri?.let { scope.launch { attachments.add(processContact(context, it)) } }
    }
    
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.values.any { it }) {
            scope.launch { attachments.add(fetchAndAppendLocation(context)) }
        } else {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val handleSend = {
        val finalInput = buildString {
            if (attachments.isNotEmpty()) {
                append(attachments.joinToString("\\n") { it.content })
                append("\\n\\n")
            }
            append(text.trim())
        }.trim()

        if (finalInput.isNotBlank()) {
            onSend(finalInput)
            attachments.clear()
        }
    }

    Column(modifier = Modifier.fillMaxWidth().background(cs.background)) {
        if (attachments.isNotEmpty()) {
            HorizontalDivider(color = cs.outlineVariant, thickness = 1.dp)
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(attachments, key = { it.id }) { att ->
                    AttachmentChip(att) { attachments.remove(att) }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
        // Leading + icon
        Box {
            IconButton(
                onClick = { showAttachmentMenu = true },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(cs.surface)
            ) {
                Icon(Icons.Default.Add, null, tint = cs.onSurface, modifier = Modifier.size(24.dp))
            }
            
            DropdownMenu(
                expanded = showAttachmentMenu,
                onDismissRequest = { showAttachmentMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Image Upload") },
                    onClick = { 
                        showAttachmentMenu = false
                        imagePicker.launch("image/*")
                    },
                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Camera") },
                    onClick = { 
                        showAttachmentMenu = false
                        val file = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        cameraUri = uri
                        cameraLauncher.launch(uri)
                    },
                    leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Location") },
                    onClick = { 
                        showAttachmentMenu = false
                        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Contact") },
                    onClick = { 
                        showAttachmentMenu = false
                        contactPicker.launch(null)
                    },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Document / File") },
                    onClick = { 
                        showAttachmentMenu = false
                        docPicker.launch("*/*")
                    },
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) }
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Text field capsule
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(cs.surface)
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                if (text.isEmpty()) {
                    Text(
                        "Message",
                        color = cs.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = cs.onSurface,
                        fontSize = 16.sp
                    ),
                    cursorBrush = Brush.verticalGradient(listOf(cs.secondary, cs.secondary)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { handleSend() }),
                    singleLine = false,
                    maxLines = 4
                )
            }
        }

        Spacer(Modifier.width(8.dp))

            // Send button — integrated on the right
            val canSendByTextOrAtt = text.isNotBlank() || attachments.isNotEmpty()
            val finalCanSend = canSendByTextOrAtt && !isProcessing
            
            if (isProcessing) {
                IconButton(
                    onClick = { onStop() },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(cs.surface)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = cs.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else if (canSendByTextOrAtt) {
                IconButton(
                    onClick = { handleSend() },
                    enabled = finalCanSend,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (finalCanSend) cs.primary else Color.Transparent)
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (finalCanSend) cs.onPrimary else cs.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                var isRecording by remember { mutableStateOf(false) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (!isGranted) {
                        Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
                    }
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) cs.error else cs.primaryContainer)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        return@detectTapGestures
                                    }
                                    
                                    isRecording = true
                                    try {
                                        orchestrator.speechService.startListening(
                                            onTranscription = { transcribed ->
                                                if (transcribed.isNotBlank()) {
                                                    onTextChange(text + if(text.isEmpty()) transcribed else " " + transcribed)
                                                }
                                            },
                                            onError = { err ->
                                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                        awaitRelease()
                                    } finally {
                                        isRecording = false
                                        orchestrator.speechService.stopListening()
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Hold to Talk",
                        tint = if (isRecording) cs.onError else cs.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// MARK: - Attachment Chip

@Composable
fun AttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(modifier = Modifier.size(64.dp)) {
        // Main content
        if (attachment.type == "image" || attachment.type == "photo") {
            AsyncImage(
                model = attachment.displayUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            // Document, location, contact
            Card(
                colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val icon = when (attachment.type) {
                        "location" -> Icons.Default.LocationOn
                        "contact" -> Icons.Default.Person
                        else -> Icons.Default.Description
                    }
                    Icon(icon, null, tint = cs.primary, modifier = Modifier.size(24.dp))
                    Text(
                        text = attachment.name.ifEmpty { attachment.type.capitalize() },
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
                    )
                }
            }
        }

        // Close button
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(cs.errorContainer)
        ) {
            Icon(Icons.Default.Close, null, tint = cs.onErrorContainer, modifier = Modifier.size(12.dp))
        }
    }
}

// MARK: - Status Dot

@Composable
fun StatusDot(isReady: Boolean) {
    val cs = MaterialTheme.colorScheme
    val color = if (isReady) cs.secondary else Color(0xFFFF9800)
    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
    val alpha by if (!isReady) {
        infiniteTransition.animateFloat(
            initialValue = 0.4f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "dotPulse"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

// MARK: - Slash Command Popup

@Composable
fun SlashCommandPopup(
    skills: List<com.parth.neoclaw.models.Skill>,
    onSelect: (com.parth.neoclaw.models.Skill) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        LazyColumn(
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(skills, key = { it.id }) { skill ->
                Surface(
                    onClick = { onSelect(skill) },
                    color = Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer),
                            shape = CircleShape,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("/${skill.name}", fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant, fontSize = 14.sp)
                            Text(skill.description, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

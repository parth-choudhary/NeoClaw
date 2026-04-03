package com.parth.mobileclaw.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserLoginScreen(
    onBack: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("mobileclaw_browser_sessions", android.content.Context.MODE_PRIVATE)

    var currentUrl by remember { mutableStateOf("https://google.com") }
    var inputUrl by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("Browser Loader") }
    
    // History array stored in prefs
    var savedDomains by remember { 
        mutableStateOf(prefs.getStringSet("domains", emptySet())?.toList()?.sorted() ?: emptyList())
    }

    // We keep a reference to WebView to control it from Composable
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    
    // Toggle between the web view and the list of saved sessions
    var showSessionsList by remember { mutableStateOf(true) }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { 
                        Text(
                            if (showSessionsList) "Background Browser Sessions" else pageTitle,
                            maxLines = 1,
                            fontSize = 18.sp
                        ) 
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (!showSessionsList) {
                                showSessionsList = true
                            } else {
                                onBack()
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        if (!showSessionsList) {
                            IconButton(onClick = { webViewRef?.reload() }) {
                                Icon(Icons.Default.Refresh, "Reload")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background)
                )
                
                if (!showSessionsList) {
                    // Address bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Enter URL (e.g. twitter.com)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    var target = inputUrl.trim()
                                    if (!target.startsWith("http://") && !target.startsWith("https://")) {
                                        target = "https://$target"
                                    }
                                    currentUrl = target
                                    webViewRef?.loadUrl(target)
                                }
                            ),
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = cs.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedContainerColor = cs.surfaceVariant.copy(alpha = 0.5f),
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            leadingIcon = {
                                Icon(Icons.Default.Language, null, modifier = Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                            }
                        )
                    }
                    
                    if (isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = cs.primary)
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showSessionsList) {
                // List of sessions / Welcome screen
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = cs.secondaryContainer,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, null, tint = cs.onSecondaryContainer)
                                    Spacer(Modifier.width(8.dp))
                                    Text("How this works", fontWeight = FontWeight.Bold, color = cs.onSecondaryContainer)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Log into websites here so the Agent can browse them in the background on your behalf. " +
                                    "Your login cookies are securely shared with the background Agent.",
                                    color = cs.onSecondaryContainer,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        
                        Button(
                            onClick = { 
                                inputUrl = ""
                                showSessionsList = false 
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open New Website")
                        }
                        
                        Spacer(Modifier.height(32.dp))
                        
                        Text(
                            "Visited Domains", 
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = cs.onBackground,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        if (savedDomains.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No domains visited yet.", color = cs.onSurfaceVariant)
                            }
                        }
                    }
                    
                    items(savedDomains) { domain ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(cs.surfaceVariant.copy(alpha = 0.3f))
                                .clickable {
                                    currentUrl = "https://$domain"
                                    inputUrl = currentUrl
                                    showSessionsList = false
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = cs.surfaceVariant,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(domain.take(1).uppercase(), fontWeight = FontWeight.Bold, color = cs.onSurface)
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Text(domain, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, color = cs.onSurface)
                            
                            IconButton(onClick = {
                                // Clear cookies for this domain
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setCookie(domain, "")
                                cookieManager.flush()
                                
                                // Remove from prefs
                                val newSet = savedDomains.toMutableSet()
                                newSet.remove(domain)
                                prefs.edit().putStringSet("domains", newSet).apply()
                                savedDomains = newSet.toList().sorted()
                            }) {
                                Icon(Icons.Default.Delete, "Delete", tint = cs.error)
                            }
                        }
                    }
                }
            } else {
                // The actual WebView
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                setSupportMultipleWindows(true)
                                javaScriptCanOpenWindowsAutomatically = true
                                // Use a real Chrome mobile UA — Google blocks default WebView UAs from OAuth
                                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.64 Mobile Safari/537.36"
                            }
                            
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    inputUrl = url ?: ""
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    pageTitle = view?.title ?: "Web Page"
                                    
                                    CookieManager.getInstance().flush()
                                    
                                    // Extract domain and save to history
                                    try {
                                        if (url != null) {
                                            val uri = android.net.Uri.parse(url)
                                            val host = uri.host
                                            if (host != null) {
                                                val domain = host.removePrefix("www.")
                                                val currentDomains = prefs.getStringSet("domains", emptySet()) ?: emptySet()
                                                if (!currentDomains.contains(domain)) {
                                                    val newDomains = currentDomains.toMutableSet().apply { add(domain) }
                                                    prefs.edit().putStringSet("domains", newDomains).apply()
                                                    savedDomains = newDomains.toList().sorted()
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Ignore URI parse errors
                                    }
                                }
                                
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    return false // Let WebView load it
                                }
                            }
                            
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    if (newProgress == 100) {
                                        isLoading = false
                                    }
                                }
                                
                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title ?: "Web Page"
                                }
                                
                                // Handle popup windows (required for Google Login, OAuth flows)
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?
                                ): Boolean {
                                    // Extract the URL from the new window request and load it
                                    // in the same WebView (inline navigation)
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                                    if (transport != null && view != null) {
                                        // Create a temp WebView to capture the URL
                                        val tempWebView = WebView(view.context)
                                        tempWebView.webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                v: WebView?,
                                                request: WebResourceRequest?
                                            ): Boolean {
                                                val url = request?.url?.toString()
                                                if (url != null) {
                                                    view.loadUrl(url)
                                                }
                                                tempWebView.destroy()
                                                return true
                                            }
                                        }
                                        transport.webView = tempWebView
                                        resultMsg.sendToTarget()
                                        return true
                                    }
                                    return false
                                }
                                
                                override fun onCloseWindow(window: WebView?) {
                                    // No-op: we navigated inline, nothing extra to close
                                }
                            }
                            
                            webViewRef = this
                            loadUrl(currentUrl)
                        }
                    },
                    update = { view ->
                        // Re-assignment not needed for basic usage since we use webViewRef
                    }
                )
            }
        }
    }
}

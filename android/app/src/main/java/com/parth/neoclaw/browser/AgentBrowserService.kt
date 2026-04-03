package com.parth.neoclaw.browser

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.parth.neoclaw.background.AgentForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Background service that manages headless WebViews for the agent to browse the web.
 * Uses an invisible 1x1px overlay window if SYSTEM_ALERT_WINDOW permission is granted,
 * otherwise falls back to a detached WebView (which has some limitations but usually works).
 */
class AgentBrowserService : Service() {

    companion object {
        var instance: AgentBrowserService? = null
            private set
            
        private val _isReady = MutableStateFlow(false)
        val isReady: StateFlow<Boolean> = _isReady
        
        fun start(context: Context) {
            val intent = Intent(context, AgentBrowserService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // We rely on AgentForegroundService to already be running and satisfying
                // the foreground service requirements. This service just binds to it conceptually.
                context.startService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private val sessions = mutableMapOf<String, BrowserSession>()
    
    // The invisible container for our WebViews
    private var overlayContainer: View? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        setupOverlayContainer()
        _isReady.value = true
        Log.d("BrowserService", "AgentBrowserService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isReady.value = false
        
        // Clean up all sessions
        Handler(Looper.getMainLooper()).post {
            sessions.values.forEach { it.destroy() }
            sessions.clear()
            
            overlayContainer?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e("BrowserService", "Error removing overlay", e)
                }
            }
        }
        Log.d("BrowserService", "AgentBrowserService destroyed")
    }

    private fun setupOverlayContainer() {
        Handler(Looper.getMainLooper()).post {
            try {
                overlayContainer = android.widget.FrameLayout(this).apply {
                    // Invisible but full-size so child WebViews can properly lay out their content
                    alpha = 0f 
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && android.provider.Settings.canDrawOverlays(this)) {
                    // Use full screen dimensions so the WebView renders the page properly.
                    // The container is alpha=0 + FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE,
                    // so the user never sees or interacts with it.
                    val params = WindowManager.LayoutParams(
                        1080, 1920, // Full mobile viewport
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSPARENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = -2000  // Position off-screen for extra safety
                        y = -2000
                    }
                    
                    windowManager.addView(overlayContainer, params)
                    Log.d("BrowserService", "Overlay container added (full-size, offscreen)")
                } else {
                    Log.w("BrowserService", "SYSTEM_ALERT_WINDOW not granted, running detached WebViews")
                }
            } catch (e: Exception) {
                Log.e("BrowserService", "Failed to setup overlay", e)
            }
        }
    }

    // MARK: - API for Agent Tools

    fun getSession(id: String?): BrowserSession {
        // If no ID provided, use the first available or create a default one
        if (id.isNullOrBlank()) {
            return sessions.values.firstOrNull() ?: createSession()
        }
        
        return sessions[id] ?: createSession(id)
    }
    
    fun createSession(id: String? = null): BrowserSession {
        val sessionId = id ?: java.util.UUID.randomUUID().toString().take(8)
        
        // Use application context to avoid memory leaks
        val session = BrowserSession(sessionId, applicationContext)
        sessions[sessionId] = session
        
        // Attach to overlay if available (helps with rendering and JS execution)
        Handler(Looper.getMainLooper()).post {
            overlayContainer?.let { container ->
                if (container is android.view.ViewGroup) {
                    container.addView(session.webView)
                }
            }
        }
        
        return session
    }
    
    fun closeSession(id: String): Boolean {
        val session = sessions.remove(id) ?: return false
        
        Handler(Looper.getMainLooper()).post {
            overlayContainer?.let { container ->
                if (container is android.view.ViewGroup) {
                    container.removeView(session.webView)
                }
            }
            session.destroy()
        }
        
        return true
    }
    
    fun listSessions(): List<Map<String, String>> {
        return sessions.values.map { 
            mapOf(
                "id" to it.sessionId,
                "url" to it.currentUrl,
                "title" to it.pageTitle
            )
        }
    }
}

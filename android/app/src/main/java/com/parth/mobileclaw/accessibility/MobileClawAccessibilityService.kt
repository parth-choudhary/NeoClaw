package com.parth.mobileclaw.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android Accessibility Service — enables MobileClaw to interact with
 * ANY app on the device. This is the most powerful capability:
 *
 *  - Read the screen (all visible text and UI elements)
 *  - Tap on buttons, text fields, or coordinates
 *  - Type text into any input field
 *  - Scroll up/down
 *  - Swipe gestures
 *  - Press back / home / recents
 *  - Monitor notifications
 *
 * The user must manually enable this service in:
 *   Settings → Accessibility → MobileClaw → Enable
 */
class MobileClawAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yService"
        var instance: MobileClawAccessibilityService? = null
            private set

        private val _isEnabled = MutableStateFlow(false)
        val isEnabled: StateFlow<Boolean> = _isEnabled

        /**
         * Check if the service is running and connected.
         */
        val isConnected: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isEnabled.value = true
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We handle events on-demand via the bridge, not reactively
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isEnabled.value = false
        Log.d(TAG, "Accessibility service destroyed")
    }

    // MARK: - Screen Reading

    /**
     * Dump the current screen UI tree as a readable text representation.
     * Returns a structured list of all visible elements with their types,
     * text, content descriptions, bounds, and clickability.
     */
    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return "(no active window)"
        val sb = StringBuilder()
        sb.appendLine("=== Screen: ${root.packageName} ===")
        dumpNode(root, sb, 0)
        root.recycle()
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 25) return // Prevent excessive tree depth
        
        val indent = "  ".repeat(depth)
        val className = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        // Only show relevant nodes: has content, is interactive, or has a stable ID
        val hasContent = text.isNotEmpty() || desc.isNotEmpty()
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
        val hasStableId = node.viewIdResourceName != null

        if (hasContent || isInteractive || hasStableId) {
            sb.append("${indent}[${className}]")
            if (text.isNotEmpty()) sb.append(" text=\"$text\"")
            if (desc.isNotEmpty()) sb.append(" desc=\"$desc\"")
            if (node.viewIdResourceName != null) sb.append(" id=\"${node.viewIdResourceName.substringAfterLast('/')}\"")
            if (node.isClickable) sb.append(" [clickable]")
            if (node.isEditable) sb.append(" [editable]")
            if (node.isScrollable) sb.append(" [scrollable]")
            if (node.isCheckable) sb.append(" checked=${node.isChecked}")
            sb.append(" bounds=${bounds.toShortString()}")
            sb.appendLine()
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                dumpNode(child, sb, depth + 1)
                child.recycle()
            }
        }
    }

    // MARK: - Tap

    /**
     * Tap at screen coordinates.
     */
    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        val deferred = CompletableDeferred<Boolean>()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)

        return deferred.await()
    }

    /**
     * Tap on a UI element by its text content.
     */
    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val target = nodes.firstOrNull { it.isClickable }
            ?: nodes.firstOrNull()

        return if (target != null) {
            val result = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target.recycle()
            root.recycle()
            result
        } else {
            root.recycle()
            false
        }
    }

    /**
     * Tap on a UI element by content description (accessibility label).
     */
    fun tapByDescription(description: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeByDescription(root, description)

        return if (target != null) {
            val result = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            target.recycle()
            root.recycle()
            result
        } else {
            root.recycle()
            false
        }
    }

    // MARK: - Text Input

    /**
     * Type text into the currently focused editable field, or find one by hint.
     */
    fun typeText(text: String, fieldHint: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false

        val target = if (fieldHint != null) {
            findEditableByHint(root, fieldHint)
        } else {
            findFocusedEditable(root)
        }

        return if (target != null) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            target.recycle()
            root.recycle()
            result
        } else {
            root.recycle()
            false
        }
    }

    // MARK: - Scrolling

    /**
     * Scroll down on the first scrollable element.
     */
    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollable(root)
        return if (scrollable != null) {
            val result = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            scrollable.recycle()
            root.recycle()
            result
        } else {
            root.recycle()
            false
        }
    }

    /**
     * Scroll up on the first scrollable element.
     */
    fun scrollUp(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollable(root)
        return if (scrollable != null) {
            val result = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            scrollable.recycle()
            root.recycle()
            result
        } else {
            root.recycle()
            false
        }
    }

    // MARK: - Swipe

    /**
     * Swipe from one point to another.
     */
    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val deferred = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { deferred.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { deferred.complete(false) }
        }, null)
        return deferred.await()
    }

    // MARK: - Navigation

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun takeScreenshot(): Boolean = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    // MARK: - Find Current App

    fun getCurrentApp(): String {
        val root = rootInActiveWindow ?: return "unknown"
        val pkg = root.packageName?.toString() ?: "unknown"
        root.recycle()
        return pkg
    }

    // MARK: - Helper Functions

    private fun findNodeByDescription(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true) {
            return AccessibilityNodeInfo.obtain(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByDescription(child, desc)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findEditableByHint(root: AccessibilityNodeInfo, hint: String): AccessibilityNodeInfo? {
        if (root.isEditable && (root.text?.toString()?.contains(hint, ignoreCase = true) == true ||
                    root.contentDescription?.toString()?.contains(hint, ignoreCase = true) == true ||
                    root.hintText?.toString()?.contains(hint, ignoreCase = true) == true)) {
            return AccessibilityNodeInfo.obtain(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findEditableByHint(child, hint)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused?.isEditable == true) return focused
        // Fallback: find first editable
        return findFirstEditable(root)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }
}

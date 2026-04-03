package com.parth.neoclaw.browser

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import android.os.Message
import java.util.UUID

/**
 * Wrapper around a headless WebView for agent interactions.
 * Uses proper event simulation (MouseEvent/PointerEvent) for SPA compatibility.
 */
class BrowserSession(
    val sessionId: String = UUID.randomUUID().toString().take(8),
    val context: Context
) {

    val webView: WebView = WebView(context)
    
    var currentUrl: String = ""
        private set
        
    var pageTitle: String = ""
        private set

    private var loadDeferred: CompletableDeferred<Boolean>? = null

    init {
        setupWebView()
    }

    private fun setupWebView() {
        Handler(Looper.getMainLooper()).post {
            webView.apply {
                // Use a real mobile viewport size so the page lays out properly
                layoutParams = ViewGroup.LayoutParams(1080, 1920)
                
                // Force the WebView to actually measure/layout even if not attached to a visible window
                measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 1080, 1920)
                
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // Use a standard Chrome mobile UA so sites serve normal mobile content
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.64 Mobile Safari/537.36"
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        currentUrl = url ?: ""
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        currentUrl = url ?: ""
                        pageTitle = view?.title ?: ""
                        CookieManager.getInstance().flush()
                        
                        // Small delay for JS frameworks to hydrate, then resolve
                        Handler(Looper.getMainLooper()).postDelayed({
                            loadDeferred?.complete(true)
                            loadDeferred = null
                        }, 1500)
                    }
                    
                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request?.isForMainFrame == true) {
                            Log.e("BrowserSession", "HTTP Error: ${errorResponse?.statusCode} for ${request.url}")
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                        pageTitle = title ?: ""
                    }
                    
                    // Handle popup windows (Google Login, OAuth flows)
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?
                    ): Boolean {
                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                        if (transport != null && view != null) {
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
                        // No-op
                    }
                }
            }
        }
    }

    suspend fun loadUrl(url: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        loadDeferred = deferred
        
        Handler(Looper.getMainLooper()).post {
            val targetUrl = if (url.startsWith("http")) url else "https://$url"
            webView.loadUrl(targetUrl)
        }
        
        return deferred.await()
    }

    suspend fun getPageContent(): String {
        val deferred = CompletableDeferred<String>()
        // This JS extracts a rich, structured view of the page including clickable items
        val js = """
            (function() {
                var result = '## ' + document.title + '\n' + '**URL:** ' + window.location.href + '\n\n';
                
                // Walk through all visible elements
                var all = document.querySelectorAll('h1, h2, h3, h4, h5, h6, p, li, span, div, a, button, input, textarea, select, [role="button"], [role="link"], [role="tab"], [role="menuitem"], [onclick], [data-testid]');
                var seen = new Set();
                
                for (var i = 0; i < all.length; i++) {
                    var el = all[i];
                    var tag = el.tagName.toLowerCase();
                    var text = (el.innerText || el.textContent || '').trim();
                    var val_ = el.value || '';
                    var ph = el.placeholder || '';
                    var ariaLabel = el.getAttribute('aria-label') || '';
                    var role = el.getAttribute('role') || '';
                    
                    // Skip empty and duplicate
                    var display = text || val_ || ph || ariaLabel;
                    if (!display || display.length > 500) continue;
                    // Limit to first 120 chars for readability
                    if (display.length > 120) display = display.substring(0, 120) + '...';
                    
                    var key = tag + ':' + display.substring(0, 60);
                    if (seen.has(key)) continue;
                    seen.add(key);
                    
                    var isClickable = el.onclick || tag === 'a' || tag === 'button' || role === 'button' || role === 'link' || role === 'tab' || role === 'menuitem' || el.getAttribute('onclick');
                    var isInput = tag === 'input' || tag === 'textarea' || tag === 'select';
                    
                    if (tag.match(/^h[1-6]$/)) {
                        var level = parseInt(tag[1]);
                        result += '\n' + '#'.repeat(level) + ' ' + display + '\n';
                    } else if (tag === 'p' || (tag === 'div' && !isClickable && text.length > 20 && text.length < 500)) {
                        result += display + '\n\n';
                    } else if (tag === 'li') {
                        result += '- ' + display + '\n';
                    } else if (isClickable) {
                        var href = el.getAttribute('href') || '';
                        if (tag === 'a' && href) {
                            result += '[LINK: ' + display + '] (href=' + href + ')\n';
                        } else {
                            result += '[CLICKABLE: ' + display + ']\n';
                        }
                    } else if (isInput) {
                        var type = el.getAttribute('type') || tag;
                        result += '[INPUT(' + type + '): ' + (val_ || ph || ariaLabel || 'empty') + ']\n';
                    }
                }
                
                // Fallback: if very little was extracted, dump body text
                if (result.length < 200) {
                    var bodyText = document.body.innerText || '';
                    if (bodyText.length > 3000) bodyText = bodyText.substring(0, 3000) + '...';
                    result += '\n---\nRaw page text:\n' + bodyText;
                }
                
                return result;
            })();
        """.trimIndent()
        
        executeJs(js) { result -> 
            val cleanResult = unescapeJsString(result)
            deferred.complete(cleanResult)
        }
        
        return deferred.await()
    }

    suspend fun clickElement(selectorOrText: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        
        val cleanQuery = selectorOrText
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
        
        // Use comprehensive click simulation with MouseEvent + PointerEvent
        // for SPA compatibility (React, Vue, Angular, Svelte, etc.)
        val js = """
            (function() {
                var query = "$cleanQuery";
                var el = null;
                
                // 1. Try CSS selector
                try { el = document.querySelector(query); } catch(e) {}
                
                // 2. Try matching by text content across all clickable elements  
                if (!el) {
                    var candidates = document.querySelectorAll('a, button, [role="button"], [role="link"], [role="tab"], [role="menuitem"], [onclick], input[type="submit"], input[type="button"]');
                    for (var i = 0; i < candidates.length; i++) {
                        var c = candidates[i];
                        var txt = (c.innerText || c.textContent || c.value || c.getAttribute('aria-label') || '').trim();
                        if (txt && (txt === query || txt.toLowerCase().indexOf(query.toLowerCase()) >= 0)) {
                            el = c;
                            break;
                        }
                    }
                }
                
                // 3. Try broader search: any element containing the text
                if (!el) {
                    var allEls = document.querySelectorAll('*');
                    for (var i = 0; i < allEls.length; i++) {
                        var c = allEls[i];
                        if (c.children.length > 3) continue; // skip containers
                        var txt = (c.innerText || c.textContent || '').trim();
                        if (txt && txt.length < 200 && txt.toLowerCase().indexOf(query.toLowerCase()) >= 0) {
                            el = c;
                            break;
                        }
                    }
                }
                
                if (!el) return 'not_found';
                
                // Scroll element into view
                el.scrollIntoView({block: 'center', behavior: 'instant'});
                
                // Get element center coordinates
                var rect = el.getBoundingClientRect();
                var x = rect.left + rect.width / 2;
                var y = rect.top + rect.height / 2;
                
                // Dispatch full event sequence that SPAs expect
                var evtInit = {bubbles: true, cancelable: true, view: window, clientX: x, clientY: y};
                
                // Pointer events (for React 17+)
                try {
                    el.dispatchEvent(new PointerEvent('pointerdown', evtInit));
                    el.dispatchEvent(new PointerEvent('pointerup', evtInit));
                } catch(e) {}
                
                // Mouse events
                el.dispatchEvent(new MouseEvent('mousedown', evtInit));
                el.dispatchEvent(new MouseEvent('mouseup', evtInit));
                el.dispatchEvent(new MouseEvent('click', evtInit));
                
                // Also try the native click as a final fallback
                try { el.click(); } catch(e) {}
                
                // For links, try direct navigation
                if (el.tagName === 'A' && el.href) {
                    try { window.location.href = el.href; } catch(e) {}
                }
                
                return 'clicked';
            })();
        """.trimIndent()
        
        executeJs(js) { result ->
            val clean = unescapeJsString(result)
            deferred.complete(clean.contains("clicked"))
        }
        
        // Give the page time to react to the click
        val success = deferred.await()
        if (success) delay(1000)
        return success
    }

    suspend fun typeInField(selectorOrText: String, text: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        
        val cleanQuery = selectorOrText.replace("\\", "\\\\").replace("\"", "\\\"")
        val cleanText = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        
        val js = """
            (function() {
                var query = "$cleanQuery";
                var textToType = "$cleanText";
                var target = null;
                
                // Try CSS selector
                try { target = document.querySelector(query); } catch(e) {}
                
                // Try matching by placeholder, name, aria-label, type, or data-placeholder
                if (!target) {
                    var inputs = document.querySelectorAll('input, textarea, [contenteditable], [contenteditable="true"], [role="textbox"], .input-message-input, .compose-input, .ql-editor');
                    for (var i = 0; i < inputs.length; i++) {
                        var el = inputs[i];
                        var ph = el.placeholder || el.getAttribute('data-placeholder') || '';
                        var nm = el.name || '';
                        var ar = el.getAttribute('aria-label') || '';
                        var tp = el.getAttribute('type') || '';
                        var cl = el.className || '';
                        var q = query.toLowerCase();
                        if (ph.toLowerCase().indexOf(q) >= 0 || 
                            nm.toLowerCase().indexOf(q) >= 0 ||
                            ar.toLowerCase().indexOf(q) >= 0 ||
                            tp.toLowerCase().indexOf(q) >= 0 ||
                            cl.toLowerCase().indexOf(q) >= 0) {
                            target = el;
                            break;
                        }
                    }
                }
                
                // Fallback: find ANY editable element on the page
                if (!target) {
                    var editables = document.querySelectorAll('[contenteditable="true"], [contenteditable=""], [role="textbox"]');
                    if (editables.length > 0) {
                        // Pick the last one (usually the message input is at the bottom)
                        target = editables[editables.length - 1];
                    }
                }
                
                // Final fallback: any visible input/textarea
                if (!target) {
                    var fields = document.querySelectorAll('input[type="text"], input:not([type]), textarea');
                    for (var i = 0; i < fields.length; i++) {
                        if (fields[i].offsetParent !== null) {
                            target = fields[i];
                            break;
                        }
                    }
                }
                
                if (!target) return 'not_found: no matching input field';
                
                // Focus the element
                target.focus();
                target.scrollIntoView({block: 'center'});
                
                // Determine if this is a contenteditable element
                var isContentEditable = target.isContentEditable || 
                    target.getAttribute('contenteditable') === 'true' || 
                    target.getAttribute('contenteditable') === '' ||
                    target.getAttribute('role') === 'textbox';
                
                if (isContentEditable) {
                    // Clear existing content
                    target.innerHTML = '';
                    
                    // Use execCommand for contenteditable (what the browser's native typing uses)
                    // This is what Telegram Web, WhatsApp Web, etc. actually listen for
                    document.execCommand('insertText', false, textToType);
                    
                    // Also dispatch input events for good measure
                    target.dispatchEvent(new InputEvent('input', {
                        bubbles: true, 
                        cancelable: true,
                        inputType: 'insertText',
                        data: textToType
                    }));
                    
                    return 'typed_contenteditable';
                }
                
                // For standard input/textarea elements
                var nativeInputValueSetter = Object.getOwnPropertyDescriptor(
                    window.HTMLInputElement.prototype, 'value'
                );
                var nativeTextareaValueSetter = Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value'
                );
                
                var setter = target.tagName === 'TEXTAREA' ? nativeTextareaValueSetter : nativeInputValueSetter;
                if (setter && setter.set) {
                    setter.set.call(target, textToType);
                } else {
                    target.value = textToType;
                }
                
                // Fire events
                target.dispatchEvent(new Event('focus', {bubbles: true}));
                target.dispatchEvent(new Event('input', {bubbles: true}));
                target.dispatchEvent(new Event('change', {bubbles: true}));
                
                return 'typed_input';
            })();
        """.trimIndent()
        
        executeJs(js) { result ->
            val clean = unescapeJsString(result)
            deferred.complete(clean.startsWith("typed"))
        }
        
        return deferred.await()
    }
    
    /**
     * Press Enter key on the currently focused element.
     * Essential for sending messages in chat apps (Telegram, WhatsApp Web, etc.)
     */
    suspend fun pressEnter(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        
        val js = """
            (function() {
                var active = document.activeElement;
                if (!active) active = document.body;
                
                var opts = {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true};
                active.dispatchEvent(new KeyboardEvent('keydown', opts));
                active.dispatchEvent(new KeyboardEvent('keypress', opts));
                active.dispatchEvent(new KeyboardEvent('keyup', opts));
                
                // Also try submitting the enclosing form if any
                var form = active.closest('form');
                if (form) {
                    try { form.requestSubmit(); } catch(e) {
                        try { form.submit(); } catch(e2) {}
                    }
                }
                
                return true;
            })();
        """.trimIndent()
        
        executeJs(js) { result ->
            deferred.complete(result == "true")
        }
        
        val success = deferred.await()
        if (success) delay(1000)
        return success
    }
    
    suspend fun scroll(direction: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        
        val scrollAmount = if (direction.lowercase() == "up") -800 else 800
        
        val js = """
            (function() {
                window.scrollBy({ top: $scrollAmount, behavior: 'instant' });
                return true;
            })();
        """.trimIndent()
        
        executeJs(js) { result ->
            deferred.complete(result == "true")
        }
        
        val success = deferred.await()
        if (success) delay(500) // Let lazy-loaded content appear
        return success
    }

    fun executeJs(script: String, callback: (String?) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript(script, callback)
        }
    }
    
    suspend fun executeJsSuspend(script: String): String? {
        val deferred = CompletableDeferred<String?>()
        executeJs(script) { result ->
            deferred.complete(unescapeJsString(result))
        }
        return deferred.await()
    }
    
    /** Unescape the string returned by evaluateJavascript (comes back JSON-encoded) */
    private fun unescapeJsString(raw: String?): String {
        if (raw == null || raw == "null") return ""
        var s = raw
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        return s.replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
    }

    fun destroy() {
        Handler(Looper.getMainLooper()).post {
            webView.stopLoading()
            webView.clearHistory()
            webView.destroy()
        }
    }
}

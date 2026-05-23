package com.aggregatorx.app.engine.webview

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.aggregatorx.app.engine.util.AppContextHolder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import android.util.Log

/**
 * Android WebView engine for JS-heavy / SPA sites with robust error handling.
 *
 * Replaces any Playwright dependency — runs entirely on the device using the
 * system WebView (Chromium-based on all modern Android including Snapdragon S24).
 *
 * All public methods are suspend functions safe to call from IO coroutines;
 * they internally dispatch WebView operations to the Main thread.
 */
@SuppressLint("SetJavaScriptEnabled")
class JavaScriptWebViewEngine(private val existingWebView: WebView? = null) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val TAG = "JSWebViewEngine"

    /**
     * Load [url], wait for JS to settle, return full rendered HTML.
     * Optionally injects [query] into the first search input found.
     * Includes comprehensive error handling and timeout management.
     */
    suspend fun loadUrlWithJavaScript(
        url: String,
        query: String? = null,
        timeoutMs: Long = 15_000L
    ): String = withTimeoutOrNull(timeoutMs) {
        try {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    try {
                        val ctx = AppContextHolder.get()
                            ?: existingWebView?.context
                            ?: run {
                                Log.e(TAG, "No context available")
                                if (cont.isActive) cont.resume("")
                                return@post
                            }

                        val wv = existingWebView ?: WebView(ctx)
                        var resumed = false

                        fun done(html: String) {
                            if (!resumed) {
                                resumed = true
                                try {
                                    if (existingWebView == null) {
                                        wv.stopLoading()
                                        wv.destroy()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error destroying WebView", e)
                                }
                                if (cont.isActive) cont.resume(html)
                            }
                        }

                        cont.invokeOnCancellation {
                            try {
                                done("")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in cancellation handler", e)
                            }
                        }

                        try {
                            configureWebView(wv)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error configuring WebView", e)
                            done("")
                            return@post
                        }

                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                if (view == null) return
                                view.postDelayed({
                                    try {
                                        if (query != null) {
                                            injectSearchAndCapture(view, query, 4_000) { done(it) }
                                        } else {
                                            captureHtml(view) { done(it) }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error in onPageFinished", e)
                                        done("")
                                    }
                                }, 2_500)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                req: WebResourceRequest?,
                                err: WebResourceError?
                            ) {
                                if (view != null && req?.isForMainFrame == true) {
                                    Log.e(TAG, "WebView error: ${err?.description}")
                                    try {
                                        captureHtml(view) { done(it) }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error capturing HTML on error", e)
                                        done("")
                                    }
                                }
                            }
                        }

                        try {
                            wv.loadUrl(url)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading URL: $url", e)
                            done("")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Fatal error in loadUrlWithJavaScript", e)
                        if (cont.isActive) cont.resume("")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadUrlWithJavaScript coroutine", e)
            ""
        }
    } ?: ""

    /**
     * Inject [query] into the search field, click submit, wait for [resultSelector], return HTML.
     */
    suspend fun injectSearchAndWait(
        searchSelector: String,
        submitSelector: String,
        query: String,
        resultSelector: String,
        timeoutMs: Long = 18_000L
    ): String = withTimeoutOrNull(timeoutMs) {
        try {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    try {
                        val wv = existingWebView ?: run {
                            Log.w(TAG, "No WebView available for search injection")
                            if (cont.isActive) cont.resume("")
                            return@post
                        }
                        var resumed = false

                        fun done(html: String) {
                            if (!resumed) {
                                resumed = true
                                if (cont.isActive) cont.resume(html)
                            }
                        }

                        cont.invokeOnCancellation { done("") }

                        val escaped = query.replace("'", "\\\'")
                        val js = """
                            (function(){
                                var inp = document.querySelector('$searchSelector');
                                if(inp){
                                    inp.value='$escaped';
                                    inp.dispatchEvent(new Event('input',{bubbles:true}));
                                    inp.dispatchEvent(new Event('change',{bubbles:true}));
                                }
                                var btn = document.querySelector('$submitSelector');
                                if(btn) btn.click();
                            })();
                        """.trimIndent()

                        try {
                            wv.evaluateJavascript(js) { }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error evaluating JS", e)
                            done("")
                            return@post
                        }

                        val start = System.currentTimeMillis()
                        val check = object : Runnable {
                            override fun run() {
                                try {
                                    wv.evaluateJavascript(
                                        "(function(){ return document.querySelectorAll('$resultSelector').length; })()"
                                    ) { res ->
                                        try {
                                            val count = res?.trim('"')?.toIntOrNull() ?: 0
                                            val elapsed = System.currentTimeMillis() - start
                                            if (count > 0 || elapsed >= timeoutMs - 1_000) {
                                                captureHtml(wv) { done(it) }
                                            } else {
                                                wv.postDelayed(this, 600)
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error checking result count", e)
                                            captureHtml(wv) { done(it) }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error in result check runnable", e)
                                    captureHtml(wv) { done(it) }
                                }
                            }
                        }
                        wv.postDelayed(check, 1_500)
                    } catch (e: Exception) {
                        Log.e(TAG, "Fatal error in injectSearchAndWait", e)
                        if (cont.isActive) cont.resume("")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in injectSearchAndWait coroutine", e)
            ""
        }
    } ?: ""

    /**
     * Scroll to bottom with error handling.
     */
    suspend fun scrollToBottom(scrollCount: Int = 3) {
        repeat(scrollCount) {
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    mainHandler.post {
                        try {
                            val wv = existingWebView ?: run {
                                if (cont.isActive) cont.resume(Unit)
                                return@post
                            }
                            wv.evaluateJavascript("window.scrollBy(0, window.innerHeight * 2); true;", null)
                            wv.postDelayed({ if (cont.isActive) cont.resume(Unit) }, 1_800)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error scrolling", e)
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in scrollToBottom", e)
            }
        }
    }

    /**
     * Extract all links with error handling.
     */
    suspend fun extractAllLinks(selector: String = "a[href]"): List<String> =
        try {
            suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    try {
                        val wv = existingWebView ?: run {
                            if (cont.isActive) cont.resume(emptyList())
                            return@post
                        }
                        wv.evaluateJavascript("""
                            (function(){
                                return JSON.stringify(
                                    Array.from(document.querySelectorAll('$selector'))
                                        .map(a=>a.href)
                                        .filter(h=>h&&h.startsWith('http'))
                                );
                            })()
                        """.trimIndent()) { raw ->
                            try {
                                val json = raw?.unescapeJs() ?: "[]"
                                val links = try {
                                    kotlinx.serialization.json.Json.decodeFromString<List<String>>(json)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing links JSON", e)
                                    emptyList()
                                }
                                if (cont.isActive) cont.resume(links)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in link extraction callback", e)
                                if (cont.isActive) cont.resume(emptyList())
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error extracting links", e)
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in extractAllLinks", e)
            emptyList()
        }

    /**
     * Cleanup resources.
     */
    fun destroy() {
        mainHandler.post {
            try {
                existingWebView?.stopLoading()
                existingWebView?.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying WebView", e)
            }
        }
    }

    fun reset() {
        mainHandler.post {
            try {
                existingWebView?.clearHistory()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing history", e)
            }
        }
    }

    // ── Internal helpers

    private fun configureWebView(wv: WebView) {
        try {
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(false)
                userAgentString = "Mozilla/5.0 (Linux; Android 14; SM-S928B) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
            }
            wv.clearCache(true)
            wv.clearHistory()
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring WebView settings", e)
        }
    }

    private fun captureHtml(view: WebView, callback: (String) -> Unit) {
        try {
            view.evaluateJavascript(
                "(function(){ return document.documentElement.outerHTML; })()"
            ) { raw ->
                try {
                    callback(raw?.unescapeJs() ?: "")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in HTML capture callback", e)
                    callback("")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing HTML", e)
            callback("")
        }
    }

    private fun injectSearchAndCapture(
        view: WebView,
        query: String,
        waitMs: Long,
        callback: (String) -> Unit
    ) {
        try {
            val escaped = query.replace("'", "\\\'")
            val js = """
                (function(){
                    var selectors = [
                        'input[type="search"]','input[type="text"][name*="q"]',
                        'input[name*="query"]','input[placeholder*="search" i]',
                        'input[type="text"]'
                    ];
                    var inp = null;
                    for(var s of selectors){ inp = document.querySelector(s); if(inp) break; }
                    if(inp){
                        inp.value='$escaped';
                        inp.dispatchEvent(new Event('input',{bubbles:true}));
                        inp.dispatchEvent(new Event('change',{bubbles:true}));
                        var form = inp.closest('form');
                        if(form){ form.submit(); return 'form'; }
                        var btn = document.querySelector(
                            'button[type="submit"],input[type="submit"],.search-btn,.btn-search,[class*="search"][class*="btn"]'
                        );
                        if(btn){ btn.click(); return 'btn'; }
                    }
                    return 'none';
                })()
            """.trimIndent()

            view.evaluateJavascript(js) { result ->
                try {
                    val action = result?.trim('"') ?: "none"
                    val delay = if (action == "none") 500L else waitMs
                    view.postDelayed({ captureHtml(view, callback) }, delay)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in search capture", e)
                    captureHtml(view, callback)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting search", e)
            captureHtml(view, callback)
        }
    }

    private fun String.unescapeJs(): String =
        trim('"')
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")
            .replace("\\\/", "/")
}

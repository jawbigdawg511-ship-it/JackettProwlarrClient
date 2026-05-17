package com.aggregatorx.app.engine.provider

import android.content.Context
import android.webkit.WebView
import android.util.Log
import com.aggregatorx.app.data.model.Provider
import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.engine.webview.JavaScriptWebViewEngine
import org.jsoup.Jsoup
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Fixed WebView Search Engine - Safe Layout Parsers and Synchronized Data Collection.
 */
class WebViewProviderSearchEngine(private val context: Context) {

    companion object {
        private const val TAG = "WebViewProviderSearch"
    }

    suspend fun searchWithWebView(
        provider: Provider,
        query: String
    ): String {
        val webView = WebView(context)
        val engine = JavaScriptWebViewEngine(webView)

        return try {
            Log.d(TAG, "Starting WebView search on ${provider.name} for query: $query")
            val html = engine.loadUrlWithJavaScript(
                url = buildSearchUrl(provider, query),
                query = query,
                timeoutMs = 12000
            )
            html
        } catch (e: Exception) {
            Log.e(TAG, "WebView search failed for ${provider.name}: ${e.message}", e)
            throw e
        } finally {
            engine.destroy()
        }
    }

    suspend fun searchWithJSInjection(
        provider: Provider,
        query: String,
        searchInputSelector: String,
        submitButtonSelector: String,
        resultSelector: String
    ): String {
        val webView = WebView(context)
        val engine = JavaScriptWebViewEngine(webView)

        return try {
            Log.d(TAG, "Starting JS injection search on ${provider.name}")
            engine.loadUrlWithJavaScript(provider.baseUrl, query, 8000)

            val renderedHtml = engine.injectSearchAndWait(
                searchSelector = searchInputSelector,
                submitSelector = submitButtonSelector,
                query = query,
                resultSelector = resultSelector,
                timeoutMs = 15000
            )
            renderedHtml
        } catch (e: Exception) {
            Log.e(TAG, "JS injection search failed for ${provider.name}: ${e.message}", e)
            throw e
        } finally {
            engine.destroy()
        }
    }

    suspend fun searchWithInfiniteScroll(
        provider: Provider,
        query: String,
        scrollIterations: Int = 3
    ): String {
        val webView = WebView(context)
        val engine = JavaScriptWebViewEngine(webView)

        return try {
            Log.d(TAG, "Starting infinite scroll search on ${provider.name}")
            engine.loadUrlWithJavaScript(buildSearchUrl(provider, query), query, 10000)

            repeat(scrollIterations) { iteration ->
                Log.d(TAG, "Scroll iteration ${iteration + 1}/$scrollIterations for ${provider.name}")
                engine.scrollToBottom(1)
            }

            // Securely await the HTML snapshot asynchronously
            val finalHtml = suspendCancellableCoroutine<String> { continuation ->
                webView.evaluateJavascript("document.documentElement.outerHTML") { result ->
                    val html = result?.trim('"')?.replace("\\\"", "\"") ?: ""
                    continuation.resume(html)
                }
            }
            finalHtml
        } catch (e: Exception) {
            Log.e(TAG, "Infinite scroll search failed for ${provider.name}: ${e.message}", e)
            throw e
        } finally {
            engine.destroy()
        }
    }

    private fun buildSearchUrl(provider: Provider, query: String): String {
        return provider.searchPattern
            .replace("{query}", query)
            .replace("{QUERY}", query)
            .let { url ->
                if (url.startsWith("http")) url else provider.baseUrl.trimEnd('/') + "/" + url.trimStart('/')
            }
    }

    fun parseWebViewResults(
        html: String,
        provider: Provider
    ): List<SearchResult> {
        return try {
            val doc = Jsoup.parse(html, provider.baseUrl)
            val results = mutableListOf<SearchResult>()

            // Fallback list parser system to match across different dynamic formats
            var resultElements = doc.select("tr:has(a), .result-item, .search-result, .torrent-box, .play-row, [class*='item']:has(a)")

            if (resultElements.isEmpty()) {
                val wrappers = doc.select(".result, .results, #results, .search-results")
                if (wrappers.size == 1) {
                    resultElements = wrappers.first()?.select("tr, div[class*='item'], div[class*='row'], li, a") ?: doc.select("a")
                } else if (wrappers.size > 1) {
                    resultElements = wrappers
                }
            }

            if (resultElements.isEmpty()) {
                resultElements = doc.select("a")
            }

            resultElements.forEach { element ->
                try {
                    val anchor = if (element.tagName() == "a") element else element.selectFirst("a")
                    val title = anchor?.text() ?: ""
                    var url = anchor?.attr("href") ?: ""
                    val quality = element.selectFirst("[class*='quality'], [class*='resolution']")?.text() ?: "auto"

                    if (url.startsWith("/")) {
                        url = provider.baseUrl.trimEnd('/') + url
                    }

                    // Strict filter to discard site UI utility links
                    val junkWords = listOf("home", "login", "register", "sign up", "faq", "about", "contact", "privacy", "terms", "logout", "index")
                    val isJunk = junkWords.any { title.equals(it, ignoreCase = true) } || url.contains(".css") || url.contains(".js") || url.startsWith("#")

                    if (url.isNotEmpty() && title.isNotEmpty() && !isJunk && title.length > 3) {
                        // FIXED: Correct parameters passed matching your SearchResult model
                        results.add(
                            SearchResult(
                                title = title,
                                url = url,
                                quality = quality,
                                providerId = provider.id,
                                providerName = provider.name,
                                relevanceScore = 0.8f
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse result element: ${e.message}")
                }
            }

            Log.d(TAG, "Parsed ${results.size} results from WebView HTML")
            results
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WebView results: ${e.message}", e)
            emptyList()
        }
    }
}

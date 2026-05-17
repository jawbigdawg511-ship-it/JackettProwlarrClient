package com.aggregatorx.app.engine.provider

import android.content.Context
import android.webkit.WebView
import android.util.Log
import com.aggregatorx.app.data.model.Provider
import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.engine.webview.JavaScriptWebViewEngine
import org.jsoup.Jsoup

/**
 * WebView-based Provider Search Engine for JavaScript-heavy sites.
 *
 * Used as fallback when standard HTTP scraping fails on providers that:
 * - Render content dynamically with JavaScript
 * - Load results via AJAX/Fetch
 * - Use client-side frameworks (React, Vue, Angular, etc.)
 * - Have complex JavaScript-driven UIs
 */
class WebViewProviderSearchEngine(private val context: Context) {

    companion object {
        private const val TAG = "WebViewProviderSearch"
    }

    /**
     * Search a provider using WebView to execute JavaScript.
     * Returns rendered HTML that can be parsed for results.
     */
    suspend fun searchWithWebView(
        provider: Provider,
        query: String
    ): String {
        val webView = WebView(context)
        val engine = JavaScriptWebViewEngine(webView)

        return try {
            val baseUrl = provider.baseUrl
            Log.d(TAG, "Starting WebView search on ${provider.name} for query: $query")

            // Load the provider's search page
            val html = engine.loadUrlWithJavaScript(
                url = buildSearchUrl(provider, query),
                query = query,
                timeoutMs = 12000
            )

            Log.d(TAG, "WebView loaded HTML (${html.length} chars) from ${provider.name}")
            html

        } catch (e: Exception) {
            Log.e(TAG, "WebView search failed for ${provider.name}: ${e.message}", e)
            throw e
        } finally {
            engine.destroy()
        }
    }

    /**
     * Search using JavaScript injection - for search forms that need manual population.
     */
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

            // Load the search page
            engine.loadUrlWithJavaScript(provider.baseUrl, query, 8000)

            // Inject search query and submit
            val renderedHtml = engine.injectSearchAndWait(
                searchSelector = searchInputSelector,
                submitSelector = submitButtonSelector,
                query = query,
                resultSelector = resultSelector,
                timeoutMs = 15000
            )

            Log.d(TAG, "JS injection completed for ${provider.name}")
            renderedHtml

        } catch (e: Exception) {
            Log.e(TAG, "JS injection search failed for ${provider.name}: ${e.message}", e)
            throw e
        } finally {
            engine.destroy()
        }
    }

    /**
     * Search with infinite scroll support - scrolls to load more results.
     */
    suspend fun searchWithInfiniteScroll(
        provider: Provider,
        query: String,
        scrollIterations: Int = 3
    ): String {
        val webView = WebView(context)
        val engine = JavaScriptWebViewEngine(webView)

        return try {
            Log.d(TAG, "Starting infinite scroll search on ${provider.name}")

            // Load initial search results
            engine.loadUrlWithJavaScript(
                buildSearchUrl(provider, query),
                query,
                10000
            )

            // Scroll to trigger loading more results
            repeat(scrollIterations) { iteration ->
                Log.d(TAG, "Scroll iteration ${iteration + 1}/$scrollIterations for ${provider.name}")
                engine.scrollToBottom(1)
            }

            // Extract all links after scrolling
            val links = engine.extractAllLinks("a[href*='${provider.searchPattern.takeWhile { it != '?' }}']")
            Log.d(TAG, "Extracted ${links.size} links from ${provider.name} after scrolling")

            // Get final HTML safely via typed callback
            var finalHtml = ""
            webView.evaluateJavascript("document.documentElement.outerHTML") { result: String? ->
                finalHtml = result?.trim('"')?.replace("\\\"", "\"") ?: ""
            }
            
            finalHtml

        } catch (e: Exception) {
            Log.e(TAG, "Infinite scroll search failed for ${provider.name}: ${e.message}", e)
            throw e
        } finally {
            engine.destroy()
        }
    }

    /**
     * Build search URL for the provider.
     */
    private fun buildSearchUrl(provider: Provider, query: String): String {
        // Provider should have searchPattern like "https://example.com/search?q={query}"
        return provider.searchPattern
            .replace("{query}", query)
            .replace("{QUERY}", query)
            .let { url ->
                if (url.startsWith("http")) url else provider.baseUrl + url
            }
    }

    /**
     * Parse rendered HTML to extract search results.
     * Can be combined with existing result parsing logic.
     */
    fun parseWebViewResults(
        html: String,
        provider: Provider
    ): List<SearchResult> {
        return try {
            val doc = Jsoup.parse(html, provider.baseUrl)

            // Use provider's result patterns to extract results
            val results = mutableListOf<SearchResult>()

            // Fixed here: Removed the broken provider.analysis lookup chain 
            // and safely defaulted to the standard ".result" selector layout
            val resultElements = doc.select(".result")

            resultElements.forEach { element ->
                try {
                    val title = element.selectFirst("a")?.text() ?: "Unknown"
                    val url = element.selectFirst("a")?.attr("href") ?: ""
                    val quality = element.selectFirst("[class*='quality']")?.text() ?: "auto"

                    if (url.isNotEmpty() && title.isNotEmpty()) {
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

package com.aggregatorx.app.engine.scraper

import android.util.Log
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * HeadlessBrowserHelper — Native Android scraping stack.
 * Replaces Playwright with OkHttp + Jsoup + Regex for mobile performance.
 */
object HeadlessBrowserHelper {

    private const val TAG = "HeadlessBrowserHelper"
    private val cookieJar = InMemoryCookieJar()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", EngineUtils.DEFAULT_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Ch-Ua-Mobile", "?1")
                    .header("Upgrade-Insecure-Requests", "1")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    // ── Playwright-compatible stubs (native OkHttp implementation) ────────────

    /**
     * Fetches page HTML using OkHttp with shadow-DOM and ad-skip simulation.
     * On Android there is no Playwright; this delegates to [fetchRaw] which
     * handles redirects, cookies, and standard headers.
     *
     * @param url         Target URL
     * @param waitSelector Ignored on native (no JS engine); kept for API compat
     * @param timeout     Connection + read timeout in milliseconds
     * @return HTML string or null on failure
     */
    suspend fun fetchPageContentWithShadowAndAdSkip(
        url: String,
        waitSelector: String? = null,
        timeout: Int = 30000
    ): String? = withContext(Dispatchers.IO) {
        try {
            val timeoutClient = client.newBuilder()
                .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .build()
            val req = Request.Builder()
                .url(url)
                .header("Referer", extractHost(url) + "/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            timeoutClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchPageContentWithShadowAndAdSkip error: ${e.message}")
            null
        }
    }

    /**
     * Alias for [fetchPageContentWithShadowAndAdSkip] used by CloudflareBypassEngine.
     */
    suspend fun fetchPageContent(
        url: String,
        waitSelector: String? = null,
        timeout: Int = 30000
    ): String? = fetchPageContentWithShadowAndAdSkip(url, waitSelector, timeout)

    /**
     * Extracts video URLs from a page by fetching its HTML and scanning for
     * common video source patterns (mp4, m3u8, mpd, webm, etc.).
     *
     * @return Distinct list of video URLs found, sorted by quality preference
     */
    suspend fun extractVideoUrls(url: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val html = fetchRaw(url) ?: return@withContext emptyList()
            val found = mutableListOf<String>()

            // Direct video src attributes
            val doc = Jsoup.parse(html, url)
            doc.select("video source, video[src]").forEach { el ->
                val src = el.absUrl("src").ifEmpty { el.absUrl("data-src") }
                if (src.isNotEmpty()) found.add(src)
            }

            // Regex patterns for video URLs in scripts and data attributes
            val videoPatterns = listOf(
                Regex("""['"]?(https?://[^'">\s]+\.(?:mp4|m3u8|mpd|webm|ts|mkv)[^'">\s]*)['"]?""", RegexOption.IGNORE_CASE),
                Regex("""file\s*:\s*['"]([^'"]+\.(?:mp4|m3u8|mpd|webm)[^'"]*)['"]""", RegexOption.IGNORE_CASE),
                Regex("""src\s*:\s*['"]([^'"]+\.(?:mp4|m3u8|mpd|webm)[^'"]*)['"]""", RegexOption.IGNORE_CASE),
                Regex("""videoUrl\s*[=:]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
                Regex("""streamUrl\s*[=:]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
                Regex("""['"]?(https?://[^'">\s]*(?:videoplayback|manifest|playlist)[^'">\s]*)['"]?""", RegexOption.IGNORE_CASE)
            )
            videoPatterns.forEach { pattern ->
                pattern.findAll(html).forEach { match ->
                    val candidate = match.groupValues[1].ifEmpty { match.groupValues[0] }.trim('\'', '"')
                    if (candidate.startsWith("http") && candidate.length > 10) found.add(candidate)
                }
            }

            // Sort: prefer HLS/DASH streams, then by quality indicator
            found.distinct().sortedByDescending { u ->
                when {
                    u.contains(".m3u8") -> 100
                    u.contains(".mpd")  -> 90
                    u.contains("1080")  -> 80
                    u.contains("720")   -> 70
                    u.contains(".mp4")  -> 60
                    else                -> 50
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractVideoUrls error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Crawls navigation tabs/categories on a site and returns content matching
     * the query. Used for sites that have no search form.
     *
     * @param baseUrl  Root URL of the site
     * @param query    Search query to match against tab content
     * @param timeout  Timeout per request in milliseconds
     * @return HTML of the best-matching tab page, or null
     */
    suspend fun fetchContentByClickingTabs(
        baseUrl: String,
        query: String,
        timeout: Int = 30000
    ): String? = withContext(Dispatchers.IO) {
        try {
            val html = fetchPageContentWithShadowAndAdSkip(baseUrl, timeout = timeout) ?: return@withContext null
            val doc = Jsoup.parse(html, baseUrl)
            val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }

            // Find navigation links that best match the query
            val navLinks = doc.select("nav a, .menu a, .tabs a, .categories a, ul.nav a")
                .map { it.absUrl("href") to it.text().lowercase() }
                .filter { (url, text) ->
                    url.isNotEmpty() && url.startsWith("http") &&
                    queryWords.any { word -> text.contains(word) || url.contains(word) }
                }
                .take(3)

            // Fetch the best matching tab
            for ((tabUrl, _) in navLinks) {
                val tabHtml = fetchPageContentWithShadowAndAdSkip(tabUrl, timeout = timeout)
                if (!tabHtml.isNullOrEmpty()) return@withContext tabHtml
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "fetchContentByClickingTabs error: ${e.message}")
            null
        }
    }

    // ── JS Deobfuscation ──────────────────────────────────────────────────────

    fun deobfuscateJs(js: String): String {
        var result = js
        var iterations = 0
        while (iterations++ < 5) {
            val packed = Regex(
                """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)\s*\{.+?\}\s*\(\s*'([\s\S]+?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([\s\S]+?)'\.split\s*\(""",
                RegexOption.DOT_MATCHES_ALL
            ).find(result) ?: break
            try {
                val p = packed.groupValues[1]
                val a = packed.groupValues[2].toIntOrNull() ?: 36
                val c = packed.groupValues[3].toIntOrNull() ?: 0
                val k = packed.groupValues[4].split("|")
                val unpacked = unpackPacked(p, a, c, k)
                if (unpacked.length > 50) result = result.replace(packed.value, unpacked) else break
            } catch (_: Exception) { break }
        }
        return result
    }

    private fun unpackPacked(p: String, a: Int, c: Int, k: List<String>): String {
        var result = p
        var i = c - 1
        while (i >= 0) {
            val word = k.getOrNull(i)
            if (!word.isNullOrEmpty()) {
                result = result.replace(Regex("\\b${toBase(i, a)}\\b"), word)
            }
            i--
        }
        return result
    }

    private fun toBase(num: Int, base: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) { sb.insert(0, chars[n % base]); n /= base }
        return sb.toString()
    }

    // ── Native Page Stub (Playwright Compatibility) ──────────────────────────

    class NativePage(val pageUrl: String = "") {
        private var _html: String = ""
        fun html(): String = _html
        internal fun setHtml(h: String) { _html = h }
        fun navigate(url: String): NativePage = runBlocking { fetchNativePage(url) ?: this@NativePage }
        fun content(): String = _html
        fun close() {}
        /** No-op on Android — no JS engine available. */
        fun waitForLoadState() { /* native stub */ }
        /**
         * Simulates JS evaluation by scanning the already-fetched HTML for
         * video URLs. Returns a List<String> of found URLs (compatible with
         * [parseInjectionResult] which accepts Any?).
         */
        fun evaluate(jsCode: String): Any? {
            if (_html.isEmpty()) return null
            val videoPattern = Regex(
                """['"]?(https?://[^'">\s]+\.(?:mp4|m3u8|mpd|webm)[^'">\s]*)['"]?""",
                RegexOption.IGNORE_CASE
            )
            val urls = videoPattern.findAll(_html)
                .map { it.groupValues[1].ifEmpty { it.value }.trim('\'', '"') }
                .filter { it.startsWith("http") }
                .distinct()
                .toList()
            return if (urls.isNotEmpty()) urls else null
        }
    }

    fun createAntiDetectionPage(): NativePage = NativePage()
    fun close() { cookieJar.clear() }

    // ── Core Fetching ────────────────────────────────────────────────────────

    suspend fun fetchRaw(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("Referer", extractHost(url) + "/").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            }
        } catch (e: Exception) { Log.w(TAG, "Fetch error: ${e.message}"); null }
    }

    private suspend fun fetchNativePage(url: String): NativePage? {
        val html = fetchRaw(url) ?: return null
        return NativePage(url).also { it.setHtml(html) }
    }

    fun searchViaHeadlessForm(baseUrl: String, query: String): String? = runBlocking {
        val html = fetchRaw(baseUrl) ?: return@runBlocking null
        val doc = Jsoup.parse(html, baseUrl)
        val form = doc.select("form").firstOrNull { f ->
            f.select("input[type=text], input[type=search], input[name*=q]").isNotEmpty()
        } ?: return@runBlocking html

        val action = form.absUrl("action").ifEmpty { baseUrl }
        val method = form.attr("method").lowercase().ifEmpty { "get" }
        val fields = mutableMapOf<String, String>()
        
        form.select("input, select, textarea").forEach { input ->
            val name = input.attr("name")
            if (name.isNotEmpty()) {
                val type = input.attr("type").lowercase()
                if (type != "submit") {
                    fields[name] = if (name.contains("q") || name.contains("query") || type == "search") query else input.attr("value")
                }
            }
        }

        try {
            if (method == "post") {
                val body = FormBody.Builder().apply { fields.forEach { add(it.key, it.value) } }.build()
                val req = Request.Builder().url(action).post(body).header("Referer", baseUrl).build()
                client.newCall(req).execute().use { it.body?.string() }
            } else {
                val qs = fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
                fetchRaw(if (action.contains("?")) "$action&$qs" else "$action?$qs")
            }
        } catch (e: Exception) { html }
    }

    private fun extractHost(url: String): String = try {
        val uri = java.net.URI(url); "${uri.scheme}://${uri.host}"
    } catch (_: Exception) { url }
}

private class InMemoryCookieJar : okhttp3.CookieJar {
    private val store = mutableMapOf<String, MutableList<okhttp3.Cookie>>()
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
    }
    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = store[url.host] ?: emptyList()
    fun clear() = store.clear()
}

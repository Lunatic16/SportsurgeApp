package io.sportsurge.app.scraper

import io.sportsurge.app.model.ServerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Kotlin port of `SportsurgeScraper` from sportsurge_links.py.
 *
 * Differences from the Python version:
 *  - Uses OkHttp instead of `requests` (Kotlin's de-facto standard).
 *  - `suspend fun getEmbedUrls(...)` so callers can launch it from a coroutine.
 *  - Returns `Result<List<ServerEntry>>` instead of throwing — caller decides how
 *    to surface failures to the UI (Snackbar, toast, dialog, ...).
 *  - Retry/backoff for transient HTTP errors is implemented inline below because
 *    OkHttp doesn't have a direct equivalent to `urllib3.Retry` for the public API.
 */
class SportsurgeScraper(
    private val verbose: Boolean = false,
    client: OkHttpClient? = null
) {

    private val client: OkHttpClient = client ?: defaultClient()

    /**
     * Default client — matches Python's `TIMEOUT = 15` and retry policy.
     * 15s connect + 15s read.
     */
    companion object {
        private fun defaultClient(): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(Constants.TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(Constants.TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(Constants.TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
            return builder.build()
        }
    }

    /**
     * Fetches a URL with the rotating User-Agent pool and custom retry policy,
     * mirroring `requests.Session.get(..., allow_redirects=True)` + `Retry(...)`.
     *
     * Returns Pair(html, finalUrl) to match the Python convention of capturing both.
     *
     * @throws IOException on network failure after all retries exhausted
     * @throws HttpStatusException on non-retryable HTTP status
     */
    suspend fun fetch(url: String, referer: String? = null): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val headers = buildHeaders(referer)

            // Retry loop — mirrors `urllib3.Retry(total=3, backoff_factor=0.5,
            // status_forcelist=[429,500,502,503,504], allowed_methods=['GET'])`.
            // Up to MAX_RETRIES retries (4 total attempts max).
            repeat(Constants.MAX_RETRIES + 1) { zeroBasedAttempt ->
                val attempt = zeroBasedAttempt + 1
                val response: Response = try {
                    doRequest(url, headers).execute()
                } catch (e: IOException) {
                    if (attempt > Constants.MAX_RETRIES) throw e
                    delayBackoff(attempt)
                    return@repeat
                }

                try {
                    if (shouldRetryOnStatus(response.code) && attempt <= Constants.MAX_RETRIES) {
                        delayBackoff(attempt)
                        return@repeat
                    }
                    if (!response.isSuccessful) {
                        throw HttpStatusException(response.code, response.message)
                    }
                    val body = response.body?.string()
                        ?: throw IOException("Empty response body")
                    val finalUrl = response.request.url.toString()
                    return@withContext body to finalUrl
                } finally {
                    // closeQuietly: ignore exceptions during connection teardown.
                    try { response.close() } catch (_: Throwable) { /* no-op */ }
                }
            }

            // Reach here only if every retry exited via retry/withContext paths above.
            throw IOException("Retry loop exited unexpectedly without a successful response")
        }

    /**
     * Public API: fetch a watch page and return all server entries.
     *
     * Mirrors `SportsurgeScraper.get_embed_urls`. The result is wrapped so the
     * UI layer can render either the entry list or specific failure messages.
     */
    suspend fun getEmbedUrls(watchUrl: String): Result<List<ServerEntry>> =
        withContext(Dispatchers.IO) {
            val html = try {
                fetch(watchUrl).first
            } catch (e: Exception) {
                return@withContext Result.failure<List<ServerEntry>>(e)
            }
            HtmlParser.buildServerEntries(html)
        }

    /**
     * Convenience: fetch the homepage and return raw HTML (used for the events-list
     * feature; reuse via `getHomepageEvents` if you wire that screen up).
     */
    suspend fun getHomepageEvents(): List<EventInfo> =
        withContext(Dispatchers.IO) {
            val (html, _) = fetch(Constants.SPORTSURGE_BASE + "/")
            parseEvents(html)
        }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Mirrors `SportsurgeScraper._make_headers`:
     *  - Random User-Agent from the pool
     *  - Accept-Language / Accept headers
     *  - Optional Referer for follow-up requests
     */
    private fun buildHeaders(referer: String?): Headers {
        val builder = Headers.Builder()
            .add("User-Agent", UserAgents.random())
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        if (referer != null) {
            builder.add("Referer", referer)
        }
        return builder.build()
    }

    /**
     * Mirrors `urllib3.Retry.status_forcelist = [429, 500, 502, 503, 504]`.
     */
    private fun shouldRetryOnStatus(code: Int): Boolean =
        code == 429 || code in setOf(500, 502, 503, 504)

    /**
     * Mirrors `Retry(backoff_factor=0.5)`: sleep `0.5 * (2 ** (attempt - 1))` seconds.
     */
    private suspend fun delayBackoff(attempt: Int) {
        val seconds = Constants.BACKOFF_FACTOR_SECONDS * 2.0.pow(attempt - 1)
        kotlinx.coroutines.delay((seconds * 1000).toLong())
    }

    /** Wraps `client.newCall(...)` so callers can attach a request once and reuse. */
    private fun doRequest(url: String, headers: Headers): Call {
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .get()
            .build()
        return client.newCall(request)
    }

    /**
     * Minimal homepage events browser — extracted from `get_homepage_events` in Python.
     * Kept here (not in HtmlParser) because it's not ported from the regex fallback —
     * we directly parse the HTML with a CSS-style selector via regex.
     */
    private fun parseEvents(html: String): List<EventInfo> {
        val aPattern = Regex(
            """<a[^>]+href=["'](https://sportsurge\.ws/(?:watch|event)/[^'"]+)["'][^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val hrefAndInner = aPattern.findAll(html).map { it.destructured }.toList()
        return hrefAndInner.map { (href, inner) ->
            val title = extractTitleFromLinkInner(inner)
                ?: titleFromSlug(href)
                ?: "Untitled Event"
            val category = extractCategoryFromLinkInner(inner) ?: "Unknown Sport"
            val status = extractStatusFromLinkInner(inner)
            EventInfo(title = title, category = category, status = status, url = href)
        }
    }

    private fun extractTitleFromLinkInner(inner: String): String? {
        // First alt that doesn't start with "Watch " — usually team-name image alts.
        val altRegex = Regex("""alt=["']([^"']+)["']""")
        val teams = altRegex.findAll(inner)
            .map { it.groupValues[1] }
            .filter { !it.startsWith("Watch") && !it.contains("chevron", ignoreCase = true) }
            .toList()
        return if (teams.isNotEmpty()) teams.joinToString(" vs ") else null
    }

    private fun extractCategoryFromLinkInner(inner: String): String? {
        val watchAlt = Regex("""alt=["']Watch ([^"']+)["']""").find(inner)?.groupValues?.get(1)
        if (watchAlt != null && ":" in watchAlt) {
            return watchAlt.split(":", limit = 2).first().trim()
        }
        // Fallback: look for known sports in text content.
        val textContent = inner.replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        val knownSports = listOf(
            "MLB", "WNBA", "NBA", "NFL", "NHL", "Boxing", "MMA",
            "FIFA World Cup", "UFC", "WWE", "NCAA"
        )
        return knownSports.firstOrNull { textContent.contains(it) }
    }

    private fun extractStatusFromLinkInner(inner: String): String {
        val textContent = inner.replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        if ("LIVE" in textContent) return "LIVE"
        val relativeTime = Regex(
            """(\d+\s+(?:minute|hour|day)s?\s+from\s+now)""",
            RegexOption.IGNORE_CASE
        ).find(textContent)
        return relativeTime?.groupValues?.get(1) ?: "Scheduled"
    }

    private fun titleFromSlug(href: String): String? {
        val parts = href.split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val slug = if (parts.last().all(Char::isDigit) && parts.size >= 2) {
            parts[parts.size - 2]
        } else {
            parts.last()
        }
        val cleaned = slug.removeSuffix("-live-streaming-links")
            .removeSuffix("-streaming-links")
            .removeSuffix("-live-stream")
        return cleaned.replace("-", " ").replaceFirstChar { it.uppercase() }
    }
}

/**
 * Lightweight value type for the homepage events list.
 * Kept separate from `ServerEntry` because the model is intentionally simple —
 * the Events screen is a future addition, not part of the MVP.
 */
data class EventInfo(
    val title: String,
    val category: String,
    val status: String,
    val url: String
)

/**
 * Thrown by `fetch` on non-retryable HTTP status codes (4xx other than 429, etc.).
 * Surfaced through `Result.failure` so UI can show a meaningful message.
 */
class HttpStatusException(
    val code: Int,
    message: String?
) : IOException("HTTP $code ${message ?: ""}".trim()) {
    val statusCode: Int get() = code
}

// OkHttp doesn't ship a "closeQuietly"-style helper on Response; mirror it locally
// so we can short-circuit the body without leaking the connection.
private fun Response.closeQuietly() {
    try {
        close()
    } catch (_: Throwable) {
        /* no-op */
    }
}

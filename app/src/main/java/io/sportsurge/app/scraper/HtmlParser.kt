package io.sportsurge.app.scraper

import io.sportsurge.app.model.ServerEntry

/**
 * Regex patterns ported verbatim from sportsurge_links.py.
 *
 * - IFRAME_PATTERNS: tries to find the embed iframe src in the page HTML.
 *   Each pattern captures the src value in capture group 1.
 *
 * - SERVER_PATTERN: matches Sportsurge's `onclick="window.changeStream(N)"` buttons
 *   and captures the stream id (N) plus the inner label text.
 *
 * Keep these as separate top-level constants so the JVM regex compiler only
 * compiles them once.
 */
object Patterns {

    /** Four fallback patterns: src=, data-src=, embedUrl= JS var, JS object src: ...embed... */
    val IFRAME_PATTERNS: List<Regex> = listOf(
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<iframe[^>]+data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""embedUrl\s*=\s*["']([^"']+)["']"""),
        Regex("""src:\s*["']([^"']+embed[^"']+)["']""")
    )

    /**
     * Captures `(streamId, rawInnerLabel)` pairs from server buttons.
     *
     * Mirror of the Python pattern from `sportsurge_links.py`:
     *   re.compile(r'onclick=["']window\.changeStream\((\d+)\)["'][^>]*>(.*?)<', re.DOTALL)
     *
     * Hardened for Java/Kotlin: restricts the second capture to terminate at the
     * next closing tag (`</anything>`) rather than literally the next `<`. The
     * shape `<li onclick="..."><b>Server</b></li>` (inner markup) is therefore
     * handled correctly. The strip-tags step in [parseServers] reduces the
     * captured markup back to a plain label.
     *
     * `DOT_MATCHES_ALL` is implied by the `[\s\S]*?` form, which behaves like
     * Python's `re.DOTALL` does for `.`.
     */
    val SERVER_PATTERN: Regex = Regex(
        """onclick=["']window\.changeStream\((\d+)\)["'][^>]*>([\s\S]*?)</\w+"""
    )

    /** Single numeric-group suffix matcher for iframe src. */
    val TRAILING_NUMERIC: Regex = Regex("""(\d+)$""")
}

/**
 * Pure parsing helpers extracted from the Python class so they're easy to unit-test.
 *
 * These don't touch the network — they assume you've already done `fetch(url).first`
 * and have the HTML string in hand.
 */
object HtmlParser {

    /**
     * Returns (label, streamId) pairs in document order, with HTML tags stripped from labels.
     *
     * Mirrors `SportsurgeScraper.parse_servers`.
     */
    fun parseServers(html: String): List<Pair<String, String>> {
        val matches = Patterns.SERVER_PATTERN.findAll(html)
        val results = mutableListOf<Pair<String, String>>()
        for (match in matches) {
            val (sid, rawLabel) = match.destructured
            // Strip any nested HTML tags (same regex sub as the Python `re.sub(r'<[^>]+>', '', raw)`).
            val label = rawLabel.replace(Regex("""<[^>]+>"""), "").trim()
            if (label.isNotEmpty()) {
                results += label to sid
            }
        }
        return results
    }

    /**
     * Tries each IFRAME_PATTERN in order and returns the first SQLite match's src.
     * Strips a trailing numeric group to yield the reusable base URL.
     *
     * Mirrors `SportsurgeScraper.parse_base_url`.
     */
    fun parseBaseUrl(html: String): String? {
        for (pattern in Patterns.IFRAME_PATTERNS) {
            val match = pattern.find(html) ?: continue
            val src = match.groupValues[1]
            return src.replace(Regex("""\d+$"""), "")
        }
        return null
    }

    /**
     * Returns the numeric id currently loaded in the iframe src, or null.
     *
     * Mirrors `SportsurgeScraper.parse_default_id`.
     */
    fun parseDefaultId(html: String): String? {
        for (pattern in Patterns.IFRAME_PATTERNS) {
            val match = pattern.find(html) ?: continue
            val src = match.groupValues[1]
            val dm = Patterns.TRAILING_NUMERIC.find(src)
            return dm?.groupValues?.get(1)
        }
        return null
    }

    /**
     * Build full embed URL list from a trimmed HTML page.
     *
     * Returns a `Result` so callers can surface specific failure reasons to the UI.
     */
    fun buildServerEntries(html: String): Result<List<ServerEntry>> {
        val baseUrl = parseBaseUrl(html)
        val defaultId = parseDefaultId(html)
        var servers = parseServers(html)

        if (servers.isEmpty()) {
            if (baseUrl.isNullOrEmpty() || defaultId.isNullOrEmpty()) {
                if (html.length < 500) {
                    return Result.failure(
                        IllegalStateException(
                            "Page response is suspiciously small — may be blocked or redirected to an error page."
                        )
                    )
                }
                return Result.failure(
                    IllegalStateException(
                        "No server entries found and no iframe/embed URL could be located. " +
                            "The page may be JS-rendered (content injected after load) or the URL may be invalid."
                    )
                )
            }
            // Single embedded iframe, no alternate-server buttons — treat as sole server.
            servers = listOf("Server1" to defaultId)
        }

        if (baseUrl.isNullOrEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "Could not locate an iframe/embed URL in the page source. " +
                        "The site may use JS-injected embeds not visible in raw HTML."
                )
            )
        }

        val entries = servers.map { (label, sid) ->
            ServerEntry(
                label = label,
                streamId = sid,
                url = "$baseUrl$sid",
                isDefault = (sid == defaultId)
            )
        }
        return Result.success(entries)
    }
}

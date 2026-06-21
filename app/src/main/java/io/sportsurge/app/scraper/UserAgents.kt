package io.sportsurge.app.scraper

/**
 * Rotating desktop User-Agent strings — mirrors the Python list.
 * Sportsurge's anti-bot is browser-UA based, so we mimic desktop Chrome/Firefox/Safari.
 */
object UserAgents {
    val POOL: List<String> = listOf(
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.3 Safari/605.1.15",
        "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0"
    )

    fun random(): String = POOL.random()
}

object Constants {
    const val TIMEOUT_SECONDS: Long = 15
    const val MAX_RETRIES: Int = 3
    const val BACKOFF_FACTOR_SECONDS: Double = 0.5
    const val SPORTSURGE_BASE: String = "https://sportsurge.ws"
}

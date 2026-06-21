package io.sportsurge.app.model

/**
 * Mirrors the Python `@dataclass ServerEntry`.
 *
 * @param label       Display name of the server ("Server1", "HD1", "Backup")
 * @param stream_id   Numeric id used to swap streams on the same embed base URL
 * @param url         Full stream URL: `{baseUrl}{streamId}`
 * @param isDefault   True if this entry is the one currently loaded in the iframe
 */
data class ServerEntry(
    val label: String,
    val streamId: String,
    val url: String,
    val isDefault: Boolean = false
)

package io.sportsurge.app

/**
 * Lightweight value type passed between MainActivity (events / streams
 * screens) and PlayerActivity. Decoupled from [io.sportsurge.app.model.ServerEntry]
 * so the player intent stays stable if the scraper's model evolves.
 */
data class ServerMetadata(
    val label: String,
    val streamId: String,
    val url: String
)

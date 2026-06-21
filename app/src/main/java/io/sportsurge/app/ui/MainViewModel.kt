package io.sportsurge.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.sportsurge.app.model.ServerEntry
import io.sportsurge.app.scraper.EventInfo
import io.sportsurge.app.scraper.HttpStatusException
import io.sportsurge.app.scraper.SportsurgeScraper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * Drives a 3-screen navigation flow that mirrors the original Python script's UX:
 *
 *   EventsList  ── tap event ──▶  StreamsList  ── tap server ──▶  Player
 *
 * - [onLoadEvents] fetches https://sportsurge.ws/ and renders the event list.
 * - [onEventSelected] drills into the picked event by resolving its /watch/ URL.
 * - [onBackToEvents] clears the resolved streams and pops back to the list.
 *
 * State is held in a single immutable [MainUiState] so rotation/process-death
 * recovery is straightforward.
 */
sealed interface Screen {
    data object Events : Screen
    data object Streams : Screen
}

data class MainUiState(
    val screen: Screen = Screen.Events,

    // Events-list state
    val events: List<EventInfo> = emptyList(),
    val isLoadingEvents: Boolean = false,
    val selectedEventTitle: String? = null,

    // Streams-list state
    val url: String = "",
    val isLoadingStreams: Boolean = false,
    val servers: List<ServerEntry> = emptyList(),

    // Shared
    val errorMessage: String? = null
)

class MainViewModel(
    private val scraper: SportsurgeScraper = SportsurgeScraper()
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        // Auto-load the events list on first view-model creation, matching
        // the Python script's `select_event_interactively()` path which hit
        // the homepage before displaying options.
        loadEvents()
    }

    // ------------------------------------------------------------------
    // Events list
    // ------------------------------------------------------------------

    fun onRefreshEvents() = loadEvents()

    private fun loadEvents() {
        _state.update {
            it.copy(isLoadingEvents = true, errorMessage = null)
        }
        viewModelScope.launch {
            try {
                val events = scraper.getHomepageEvents()
                _state.update {
                    it.copy(
                        isLoadingEvents = false,
                        events = events,
                        errorMessage = if (events.isEmpty()) {
                            "No active sporting events found on the homepage."
                        } else null
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoadingEvents = false,
                        errorMessage = humanError(e)
                    )
                }
            }
        }
    }

    /**
     * User tapped an event in the list. Switch to streams screen and start
     * resolving the /watch/ URL into server embeds.
     */
    fun onEventSelected(event: EventInfo) {
        _state.update {
            it.copy(
                screen = Screen.Streams,
                selectedEventTitle = event.title,
                url = event.url,
                isLoadingStreams = true,
                servers = emptyList(),
                errorMessage = null
            )
        }
        resolveUrl(event.url)
    }

    // ------------------------------------------------------------------
    // Streams list
    // ------------------------------------------------------------------

    /** Manually trigger a resolution (used by the "Refresh" button in the streams screen). */
    fun onResolveClick() {
        val url = _state.value.url.trim()
        if (url.isEmpty()) return
        _state.update {
            it.copy(isLoadingStreams = true, servers = emptyList(), errorMessage = null)
        }
        resolveUrl(url)
    }

    private fun resolveUrl(url: String) {
        viewModelScope.launch {
            val result = scraper.getEmbedUrls(url)
            result.fold(
                onSuccess = { servers ->
                    _state.update {
                        it.copy(
                            isLoadingStreams = false,
                            servers = servers,
                            errorMessage = if (servers.isEmpty()) "No streams found." else null
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            isLoadingStreams = false,
                            errorMessage = humanError(e)
                        )
                    }
                }
            )
        }
    }

    fun onBackToEvents() {
        _state.update {
            it.copy(
                screen = Screen.Events,
                servers = emptyList(),
                url = "",
                selectedEventTitle = null,
                errorMessage = null
            )
        }
    }

    fun onClearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    // ------------------------------------------------------------------
    // Errors
    // ------------------------------------------------------------------

    private fun humanError(t: Throwable): String = when (t) {
        is HttpStatusException -> "Sportsurge returned HTTP ${t.code}. " +
            "The page may be unavailable or this version of the site may require updates."
        is TimeoutException -> "The request timed out. Check your connection and try again."
        is IOException -> "Could not reach Sportsurge: ${t.message ?: "network error"}."
        else -> t.message ?: "Unexpected error."
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                return MainViewModel() as T
            }
        }
    }
}

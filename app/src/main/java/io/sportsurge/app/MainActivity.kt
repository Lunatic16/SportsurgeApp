package io.sportsurge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.sportsurge.app.model.ServerEntry
import io.sportsurge.app.player.PlayerActivity
import io.sportsurge.app.scraper.EventInfo
import io.sportsurge.app.ui.MainViewModel
import io.sportsurge.app.ui.Screen
import io.sportsurge.app.ui.theme.DefaultGreen
import io.sportsurge.app.ui.theme.LiveRed
import io.sportsurge.app.ui.theme.SportsurgeAppTheme
import io.sportsurge.app.ui.theme.WarningAmber

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SportsurgeAppTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val ctx = LocalContext.current

    val currentError = state.errorMessage
    LaunchedEffect(currentError) {
        currentError?.let { snackbarHostState.showSnackbar(it) }
    }

    val title = when (state.screen) {
        Screen.Events -> "Sportsurge"
        Screen.Streams -> state.selectedEventTitle ?: "Streams"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1) },
                navigationIcon = {
                    if (state.screen == Screen.Streams) {
                        IconButton(onClick = viewModel::onBackToEvents) {
                            Icon(
                                imageVector = Icons.Outlined.ArrowBack,
                                contentDescription = "Back to events"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        when (state.screen) {
                            Screen.Events -> viewModel.onRefreshEvents()
                            Screen.Streams -> viewModel.onResolveClick()
                        }
                    }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) } },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        when (state.screen) {
            Screen.Events -> EventsScreen(
                events = state.events,
                loading = state.isLoadingEvents,
                onEventClick = viewModel::onEventSelected,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
            Screen.Streams -> StreamsScreen(
                servers = state.servers,
                loading = state.isLoadingStreams,
                onPlay = { entry ->
                    ctx.startActivity(
                        PlayerActivity.intent(
                            ctx,
                            ServerMetadata(label = entry.label, streamId = entry.streamId, url = entry.url)
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun EventsScreen(
    events: List<EventInfo>,
    loading: Boolean,
    onEventClick: (EventInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    if (loading && events.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().padding(top = 64.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Loading events...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }
        return
    }
    if (events.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().padding(top = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No active sporting events.\nTap Refresh to try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
        return
    }

    val groups: List<Pair<String, List<EventInfo>>> = remember(events) {
        val ordered = LinkedHashMap<String, MutableList<EventInfo>>()
        for (ev in events) {
            ordered.getOrPut(ev.category) { mutableListOf() } += ev
        }
        ordered.toList()
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        for ((category, items) in groups) {
            item(key = "h_$category") {
                Text(
                    text = category,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            items(items = items, key = { it.url }) { event ->
                EventRow(event = event, onClick = { onEventClick(event) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventRow(event: EventInfo, onClick: () -> Unit) {
    val isLive = event.status.equals("LIVE", ignoreCase = true)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = event.url.replace("https://sportsurge.ws", ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1
                )
            }
            StatusBadge(text = event.status, live = isLive)
        }
    }
}

@Composable
private fun StatusBadge(text: String, live: Boolean) {
    val bg: androidx.compose.ui.graphics.Color
    val fg: androidx.compose.ui.graphics.Color
    val icon = when {
        live -> {
            bg = LiveRed.copy(alpha = 0.18f)
            fg = LiveRed
            Icons.Filled.FiberManualRecord
        }
        text.lowercase().contains("from now") -> {
            bg = WarningAmber.copy(alpha = 0.18f)
            fg = WarningAmber
            Icons.Outlined.Schedule
        }
        else -> {
            bg = MaterialTheme.colorScheme.surfaceVariant
            fg = MaterialTheme.colorScheme.onSurfaceVariant
            Icons.Outlined.Schedule
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(10.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun StreamsScreen(
    servers: List<ServerEntry>,
    loading: Boolean,
    onPlay: (ServerEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    if (loading) {
        Box(
            modifier = modifier.fillMaxWidth().padding(top = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Resolving streams...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }
        }
        return
    }
    if (servers.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().padding(top = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No streams. Tap Refresh to retry.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(items = servers, key = { it.streamId }) { entry ->
            ServerRow(entry = entry, onPlay = { onPlay(entry) })
        }
    }
}

@Composable
private fun ServerRow(entry: ServerEntry, onPlay: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (entry.isDefault) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (entry.isDefault) DefaultGreen
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (entry.isDefault) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = "Default server",
                        tint = DefaultGreen
                    )
                }
                IconButton(onClick = onPlay) {
                    Icon(
                        imageVector = Icons.Outlined.PlayCircle,
                        contentDescription = "Play ${entry.label}",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = entry.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 2
            )
            HorizontalDivider(
                modifier = Modifier.padding(top = 10.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(entry.url))
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy URL")
                }
            }
        }
    }
}

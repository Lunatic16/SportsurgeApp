package io.sportsurge.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.sportsurge.app.ServerMetadata
import io.sportsurge.app.ui.theme.SportsurgeAppTheme

/**
 * Hosts the embed URL in an immersive, full-screen Android WebView.
 *
 * Previous version used a Compose Scaffold with a TopAppBar + URL footer
 * around a `weight(1f)` WebView. The top/bottom chrome shrunk the
 * WebView's touchable area and led to taps landing on Compose widgets
 * instead of the embedded player — especially near the bottom of the
 * page where the URL label was rendered.
 *
 * This version claims the whole window for the player:
 *   - hides status bar + navigation bar (immersive sticky)
 *   - removes TopAppBar and the URL footer
 *   - lets the WebView render edge-to-edge with `MATCH_PARENT`
 *   - overlays only a transient LinearProgressIndicator while loading
 *   - close uses a back-press gesture (system Back, or predictive on 14+)
 *
 * The page-sourced-player still owns playback; the activity is a thin
 * shell around the WebView so the embed sees maximum viewport real
 * estate and the click coordinates we pass through are 1:1 with the
 * rendered page.
 */
class PlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        val embedUrl = intent.getStringExtra(EXTRA_EMBED_URL).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "Stream"
        setContent {
            SportsurgeAppTheme {
                PlayerScreen(
                    title = label,
                    embedUrl = embedUrl,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Returning from background (notification shade pull, recent apps,
        // etc.) re-asserts immersive mode — otherwise the system bars
        // can sneak back in and start stealing taps.
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        // Lay out behind the system bars so the WebView gets the full
        // surface, then hide them. Without `LAYOUT_STABLE` the window
        // would resize on bar visibility changes and the WebView would
        // relayout — visible flicker + lost clicks on close gestures.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        const val EXTRA_EMBED_URL = "io.sportsurge.app.EXTRA_EMBED_URL"
        const val EXTRA_LABEL = "io.sportsurge.app.EXTRA_LABEL"
        const val EXTRA_STREAM_ID = "io.sportsurge.app.EXTRA_STREAM_ID"

        fun intent(context: Context, metadata: ServerMetadata): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_EMBED_URL, metadata.url)
                putExtra(EXTRA_LABEL, metadata.label)
                putExtra(EXTRA_STREAM_ID, metadata.streamId)
            }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PlayerScreen(
    title: String,
    embedUrl: String,
    onClose: () -> Unit
) {
    var isLoading by remember { mutableStateOf(embedUrl.isNotBlank()) }
    var progress by remember { mutableStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Activity-level onClose isn't wired into the UI anymore: the chrome
    // (top bar / footer) is gone. back-handler-style dismissal lives in
    // `BackHandler` below, which uses the system Back gesture predictively
    // on Android 14+ and the legacy back key/gesture elsewhere.
    androidx.activity.compose.BackHandler(enabled = true) { onClose() }

    // Cookie settings: enable once per process so the embed can carry a
    // session across activity restarts in the same launch. The third-party
    // cookie setter was deprecated; first-party cookies are sufficient
    // for the embed use-case.
    LaunchedEffect(Unit) {
        CookieManager.getInstance().setAcceptCookie(true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when {
            pageError != null -> CenteredMessage("Page error: ${pageError}")
            embedUrl.isBlank() -> CenteredMessage("No embed URL provided")
            else -> {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            // Touch focus on the WebView so the OS dispatches
                            // taps into the embed rather than into Compose's
                            // parent. Without these the platform routes tap
                            // events to the Compose root which drops them
                            // when no clickable child is hit.
                            isFocusable = true
                            isFocusableInTouchMode = true
                            isClickable = true
                            isLongClickable = false
                            setOnLongClickListener { true }
                            // NOTE: `pointerIcon` is intentionally NOT set.
                            // Custom pointer icons are a desktop / pointer-
                            // driven input concept; on touch devices the
                            // OS ignores them and on some builds setting one
                            // can collapse click routing. Touch tap routing
                            // is governed by focusable/clickable above.
                            configureForEmbed(
                                onProgress = { p ->
                                    progress = p
                                    if (p in 1..99) {
                                        isLoading = false
                                    } else if (p == 0) {
                                        // onPageStarted will toggle this.
                                    } else {
                                        isLoading = false
                                    }
                                },
                                onError = { msg ->
                                    pageError = msg
                                    isLoading = false
                                    progress = 0
                                },
                                onStarted = {
                                    isLoading = true
                                    progress = 0
                                    pageError = null
                                }
                            )
                            webViewRef = this
                            loadUrl(embedUrl)
                        }
                    },
                    update = { /* no-op: state is owned by the embed page */ },
                    modifier = Modifier.fillMaxSize()
                )

                // Floating, translucent progress bar overlaid on top of the
                // WebView. Lifted above the WebView (not beside it) so it
                // never eats vertical space from the player's click target.
                if (isLoading || progress in 1..99) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LinearProgressIndicator(
                            progress = (progress.coerceIn(0, 100)) / 100f,
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Transparent.copy(alpha = 0.0f)
                        )
                    }
                }
            }
        }
    }

    // `title` is retained in the intent & back stack so a future
    // re-entry (e.g. screen-recorded UI overlays) can surface it; the
    // in-UI TopAppBar is intentionally removed to maximize viewport.
    @Suppress("UNUSED_EXPRESSION") title
}

private fun WebView.configureForEmbed(
    onProgress: (Int) -> Unit,
    onError: (String) -> Unit,
    onStarted: () -> Unit
) {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        cacheMode = WebSettings.LOAD_DEFAULT
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            // Keep navigations inside our WebView so the player state is
            // preserved when the user clicks something within the embed.
            view.loadUrl(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            onStarted()
        }

        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            onError("HTTP $errorCode ${description ?: ""}".trim())
        }
    }
    webChromeClient = object : WebChromeClient() {
        // Progress updates are reported on WebChromeClient, not WebViewClient.
        // Surfacing them lets the activity show a floating overlay bar
        // while the embed's player bundle is initializing.
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            onProgress(newProgress)
        }
    }
}

@Composable
private fun CenteredMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
    }
}

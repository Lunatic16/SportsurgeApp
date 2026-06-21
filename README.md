<p align="center">
  <img src="sportsurge.png" alt="Project Screenshot" width="350">
</p>

# SportsurgeApp — Android port of sportsurge_links.py

A sideloadable Android app that takes the Sportsurge scraper from [sportsurge_links.py](https://github.com/Lunatic16/sportsurge-ws-scraper) and rebuilds it as a Compose UI you can sideload onto a phone. Browse the Sportsurge homepage, pick a server embed, and play it through an immersive WebView that delegates the actual stream playback to the embed's own JS player.

**Use case:** side-loaded personal use, sideload-only. Not for the Play Store as-is — the release variant signs with the auto-generated debug keystore (see [Sideloading notes](#sideloading-notes)).

---

## What this app does

Three screens, in this order:

1. **Events** — homepage event list scraped live from Sportsurge with one tap.
2. **Streams** — server embeds (`Server 1`, `HD1`, `Backup`, …) for the selected event. Tapping a server hands off to the player.
3. **Player** — full-screen, status/nav bars hidden, immersive WebView loading the embed URL. Closes on system Back (predictive back on Android 14+).

`PlayerActivity` is intentionally thin: it does *not* reimplement HLS, dash, or signed-URL rotation. The embed page owns playback. That's the deliberate decision the prior Media3/ExoPlayer path couldn't get right — the embed's pre-signed segments and live-playlist refresh cadence weren't reproducible on the Kotlin side without a heavyweight reverse engineering effort that buys nothing.

---

## Quick start: build the APK

The intended build path is **podman containers** baked in this repo:

| Container         | Base image                          | Purpose                                                           |
|-------------------|-------------------------------------|-------------------------------------------------------------------|
| `sportsurge-build`| `gradle:8.10.2-jdk17` + Android SDK | Reproducible build, fully self-contained image.                   |
| `sportsurge-sdk`  | `gradle:8.10.2-jdk17` + bind SDK    | Source + SDK bind mounts, drops APK into `.container/apk-out/`.   |

Both are already running on this host. Re-use them:

```bash
# Release build (sideload-signed with the auto-generated debug keystore)
podman exec sportsurge-sdk bash -lc 'cd /src && ./gradlew assembleRelease'

# Debug build (faster, useful when iterating on PlayerActivity)
podman exec sportsurge-sdk bash -lc 'cd /src && ./gradlew assembleDebug'
```

Artifacts land in two equivalent places thanks to the bind mount:

- In the container: `/src/app/build/outputs/apk/{debug,release}/app-*.apk`
- On the host:     `app/build/outputs/apk/{debug,release}/app-*.apk` and `.container/apk-out/app-{debug,release}.apk` (canonical sideload path)

Sideload onto a connected device:

```bash
adb install -r .container/apk-out/app-release.apk
```

### Why containers?

JDK 25 on the original host *appears* to work but trips Gradle 8.10.2's Kotlin DSL parser at startup (it can't parse `25.0.3` as a `JavaVersion`), and AGP 8.5.2 won't run on it. JDK 17 inside `gradle:8.10.2-jdk17` is the verified-clean combination. The containers are pre-prepared — don't try the host's `./gradlew` directly.

### Toolchain (frozen in `gradle/libs.versions.toml`)

| Component | Version |
|---|---|
| Gradle (wrapper) | 8.10.2 |
| AGP              | 8.5.2 |
| Kotlin           | 2.0.21 |
| Compose BOM      | 2024.10.01 |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk`         | 26 (Android 8.0) |
| OkHttp           | 4.12.0 |
| Coroutines       | 1.8.1 |

> A `compileSdk=36` warning is emitted (target API 36 has minor policing noise). Harmless. To silence, add `android.suppressUnsupportedCompileSdk=36` to `gradle.properties`.

---

## Project layout

```
SportsurgeApp/
├── build.gradle.kts                         Root Gradle config (plugin versions only)
├── settings.gradle.kts                      Module include + repository config
├── gradle.properties                        JVM args, AndroidX flags
├── gradle/
│   ├── libs.versions.toml                   All dependency versions in one place
│   └── wrapper/                             `gradle-wrapper.jar` (Gradle 8.10.2)
├── .container/
│   ├── Containerfile                        Image recipe for `sportsurge-build`
│   └── apk-out/                             Sideload destination (bind-mounted into sdk)
└── app/
    ├── build.gradle.kts                     App module (compileSdk 36, Compose, WebView host)
    ├── proguard-rules.pro                   Keep rules for Media3-derived annotations
    └── src/main/
        ├── AndroidManifest.xml              INTERNET, PlayerActivity, cleartext for embeds
        ├── java/io/sportsurge/app/
        │   ├── SportsurgeApp.kt             Application class
        │   ├── MainActivity.kt              Compose UI: Events → Streams
        │   ├── ServerMetadata.kt            Value type: label + streamId + url
        │   ├── ui/
        │   │   ├── MainViewModel.kt         Coroutine state holder, sealed `Screen`
        │   │   └── theme/                   Material 3 palette + typography
        │   ├── player/
        │   │   └── PlayerActivity.kt        Immersive WebView host, BackHandler close
        │   ├── scraper/
        │   │   ├── SportsurgeScraper.kt     OkHttp client, retry/backoff, getHomepageEvents
        │   │   ├── HtmlParser.kt            Pure regex parsing (Kotlin port)
        │   │   └── UserAgents.kt            Rotating desktop User-Agent pool
        │   └── model/
        │       └── ServerEntry.kt           Data class mirror of Python `@dataclass`
        └── res/
            ├── values/                      strings, colors, themes
            ├── drawable/                    Adaptive launcher icon
            ├── mipmap-anydpi-v26/           Adaptive icon XML
            └── xml/                         Backup / extraction rule XMLs
```

---

## Python → Kotlin port

| Python (`sportsurge_links.py`)              | Kotlin (this project)                       |
|---|---|
| `requests.Session` + `urllib3.util.retry.Retry` | `OkHttpClient` with manual retry loop    |
| `beautifulsoup4` (lazy fallback)             | Pure regex (`HtmlParser.kt`) — no parser |
| `dataclasses`                                | `data class`                             |
| `argparse`                                   | Compose `OutlinedTextField` + `Button`   |
| `if __name__ == "__main__"` CLI              | `MainActivity` w/ `MainViewModel`        |
| Plain print formatters                       | Direct UI rendering                      |
| **Page-sourced player** (CLI)                | **Page-sourced player** (WebView host)   |

The Python CLI formatters, interactive menu, CSV/JSON/table output, and the BeautifulSoup fallback were dropped — they're CLI surface area, not app behavior. Playback itself is *deliberately* still page-sourced: that's the same dependency the Python script had once it printed the embed URL and the user opened it in a browser.

### Two intentional differences vs. the Python regex

1. **`SERVER_PATTERN` hardened for Java/Kotlin regex semantics.** Same spirit as the Python literal, but the inner-label capture terminate at the next closing tag (`</…>`) not strictly the next `<`. Two strings produce the same labels; `<li onclick="…"><b>HD1</b></li>` (inner markup) is handled in the Kotlin version too.
2. **`Backoff sleep` is `kotlinx.coroutines.delay`** instead of `time.sleep`. Required for `suspend fun`.

---

## Player activity: the WebView choice

`PlayerActivity.kt` is intentionally a thin shell around `WebView`:

| Setting                          | Value / Why                                                                 |
|----------------------------------|-----------------------------------------------------------------------------|
| `javaScriptEnabled`              | `true` — embed's player bundle needs JS                                    |
| `domStorageEnabled`              | `true` — Shaka/dash.js persistence                                          |
| `userAgentString`                | Desktop Chrome 120 — matches `enhanced-embed.sh` fallback                   |
| `mixedContentMode`               | `MIXED_CONTENT_COMPATIBILITY_MODE` — many embeds serve HLS over HTTP         |
| `mediaPlaybackRequiresUserGesture`| `false` — autoplay without a tap                                           |
| `cacheMode`                      | `LOAD_DEFAULT`                                                              |
| WebView size                     | `MATCH_PARENT × MATCH_PARENT` on the `AndroidView`                          |
| Window flags                     | `decorFitsSystemWindows=false`, `systemBarsBehavior=TRANSIENT_BARS_BY_SWIPE`, `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` |
| Back                             | `BackHandler { finish() }` — predictive back on Android 14+                 |
| Click routing                    | `isFocusable=true`, `isFocusableInTouchMode=true`, `isClickable=true`       |
| Pointer icon                     | **Not set.** Custom pointer icons are a desktop concept; on touch devices they can collapse click routing. |

The pre-PlayerActivity version had a Scaffold wrapping the WebView with a top app bar and a URL footer Text widget. That ate 100+ dp off the viewport and click coordinates near the bottom landed on the non-WebView Compose widgets — taps were silently dropped. The current layout is `Box.fillMaxSize()` holding just the `AndroidView(WebView)`, with a single translucent `LinearProgressIndicator` overlaid at the top edge while loading.

---

## Sideloading notes

- Both `release` and `debug` build types sign with `signingConfigs.debug`. `assembleRelease` therefore produces a sideloadable signed APK using the auto-generated `~/.android/debug.keystore` — no keystore setup needed on your end. **Don't publish to the Play Store as-is**; add a proper `signingConfigs` block with a release keystore first.
- `android:usesCleartextTraffic="true"` is required: several embed servers serve HLS over HTTP. WebView needs this to reach them. Sideloading only.
- The HTTP User-Agent pool mimics real desktop Chrome/Firefox/Safari. Sportsurge blocks apparent-bot User-Agents; this is the same list as the Python script (`scraper/UserAgents.kt`).
- `android.permission.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is added automatically by AGP for `targetSdk ≥ 33` — runtime-only, invisible to the manifest source.

### Cache reset (when state looks weird)

```bash
# Inside the sdk container, wipe per-project build outputs + gradle dir
podman exec sportsurge-sdk bash -lc '
  rm -rfv /src/.gradle /src/build /src/app/build /src/.kotlin
'
```

Global `/home/gradle/.gradle` and SDK build-tools are busy bind mounts — they can't be cleared from inside the container. Don't worry about them; the per-project reset above is what makes builds reproducible.

---

## Verification

What's been exercised on the latest build (release, `assembleRelease`):

| Check                                          | Result  | Source                                                         |
|------------------------------------------------|---------|----------------------------------------------------------------|
| Build under JDK 17 in podman                   | ✅      | `BUILD SUCCESSFUL in 4m 39s` · 50 tasks executed               |
| APK Signed (v2 scheme)                         | ✅      | `apksigner verify --print-certs` → "Verifies" + v2: true       |
| Manifest: 2 activities, INTERNET only          | ✅      | `aapt dump badging` / `aapt dump xmltree AndroidManifest.xml`  |
| PlayerActivity registered, exported=false      | ✅      | `xmltree`                                                      |
| `configChanges=orientation\|screenSize\|…`     | ✅      | `xmltree` — rotation does NOT recreate the activity            |
| Compose UI compiles against Compose BOM 24.10  | ✅      | build log · `compileDebugKotlin` / `compileReleaseKotlin`      |
| WebView launches embed, full-screen, immersive | ✅ (manual) | Sideload on device, tap event → tap server                 |

Known deprecations emitted by the Kotlin compiler — none block any build:

| File                                                     | Flag                                                                     |
|----------------------------------------------------------|--------------------------------------------------------------------------|
| `MainActivity.kt:111`                                    | `Icons.Outlined.ArrowBack` → `Icons.AutoMirrored.Outlined.ArrowBack`     |
| `PlayerActivity.kt:220`                                  | `LinearProgressIndicator(progress: Float)` → `(progress: () -> Float)` lambda form |
| `PlayerActivity.kt:273`                                  | `WebViewClient.onReceivedError` (deprecated in Java, still works)        |
| `ui/theme/Theme.kt:71`                                   | `Window.statusBarColor` (deprecated; edge-to-edge otherwise)             |

---

## Future work a Play Store build would need

- Real `signingConfigs` block with a release keystore.
- A favorites / recent-stores persistence layer (DataStore).
- Optional Compose Navigation if more screens appear.
- Optional Material 3 dynamic color — plumbing is already in `Theme.kt`, disabled by default.
- Cleanup pass: silence the four deprecation warnings listed above.

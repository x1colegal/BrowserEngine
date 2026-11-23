package com.x1colegal.browserengine

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Tracks
import kotlinx.coroutines.delay
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DataSource
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import android.net.Uri

class MainActivity : ComponentActivity() {
    private lateinit var browser: BrowserTabs
    private var pictureInPicture by mutableStateOf(false)
    private var previousOrientation: Int? = null
    private var nativePlayer: Player? = null
    private var pipAspect = Rational(16, 9)
    private val pipPlayerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updatePipControls()
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = updatePipControls()
    }
    private val pipControls = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!pictureInPicture || intent.action != PIP_CONTROL) return
            val command = PipCommand.entries.firstOrNull { it.name == intent.getStringExtra(PIP_COMMAND) } ?: return
            val tab = browser.current ?: return
            if (tab.media != null) nativePlayer?.let { command.run(it) }
            else tab.webMediaSession?.let { command.run(it, tab.webFeatures) }
        }
    }

    private val supportsPip: Boolean
        get() = Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browser = lastCustomNonConfigurationInstance as? BrowserTabs ?: BrowserTabs(
            applicationContext,
            savedInstanceState?.getStringArrayList("tabs")
                ?: arrayListOf(savedInstanceState?.getString("url").orEmpty()),
            savedInstanceState?.getInt("selectedTab") ?: 0
        )
        if (supportsPip) {
            pictureInPicture = isInPictureInPictureMode
            ContextCompat.registerReceiver(
                this, pipControls, IntentFilter(PIP_CONTROL), ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        setContent {
            val tab = browser.current
            LaunchedEffect(pictureInPicture, tab, tab?.webMediaSession, tab?.webPlaying, tab?.webFeatures) {
                updatePipControls()
            }
            BrowserScreen(
                browser, this, supportsPip, pictureInPicture, ::enterVideoPip,
                ::setNativePlayer, ::setVideoFullscreen
            )
        }
    }

    private fun setVideoFullscreen(fullscreen: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            if (previousOrientation == null) {
                previousOrientation = requestedOrientation
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            previousOrientation?.let { requestedOrientation = it }
            previousOrientation = null
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun enterVideoPip(aspect: Rational): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !supportsPip || isInPictureInPictureMode) return false
        pipAspect = aspect
        return runCatching { enterPictureInPictureMode(currentPipParams()) }.getOrDefault(false)
    }

    private fun enterCurrentVideoPip(): Boolean {
        val tab = browser.current ?: return false
        val aspect = if (tab.media != null) {
            nativePlayer?.takeIf { it.isPlaying }?.videoSize?.let { size ->
                if (size.width > 0 && size.height > 0) {
                    videoAspect((size.width * size.pixelWidthHeightRatio).toLong(), size.height.toLong())
                } else null
            }
        } else tab.fullscreenAspect.takeIf { tab.fullscreen && tab.webPlaying }
        return aspect?.let(::enterVideoPip) ?: false
    }

    @RequiresApi(26)
    private fun currentPipParams(): PictureInPictureParams {
        val tab = browser.current
        val player = nativePlayer.takeIf { tab?.media != null }
        if (player != null) return pipParams(this, pipAspect, player.playWhenReady) { it.available(player) }
        val features = if (tab?.webMediaSession != null) tab.webFeatures else MediaSession.Feature.NONE
        return pipParams(this, pipAspect, tab?.webPlaying == true) { it.available(features) }
    }

    private fun updatePipControls() {
        if (Build.VERSION.SDK_INT >= 26 && pictureInPicture && !isDestroyed && !isFinishing) {
            setPictureInPictureParams(currentPipParams())
        }
    }

    private fun setNativePlayer(player: Player?) {
        if (nativePlayer === player) return
        nativePlayer?.removeListener(pipPlayerListener)
        nativePlayer = player
        player?.addListener(pipPlayerListener)
        updatePipControls()
    }

    override fun onPictureInPictureRequested(): Boolean = enterCurrentVideoPip()

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31) enterCurrentVideoPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        pictureInPicture = inPip
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("tabs", ArrayList(browser.tabs.map { it.displayUrl }))
        outState.putInt("selectedTab", browser.tabs.indexOf(browser.current))
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onRetainCustomNonConfigurationInstance(): Any = browser

    override fun onDestroy() {
        if (supportsPip) unregisterReceiver(pipControls)
        setNativePlayer(null)
        if (!isChangingConfigurations) browser.close()
        super.onDestroy()
    }
}

private fun videoAspect(width: Long, height: Long): Rational {
    if (width <= 0 || height <= 0) return Rational(16, 9)
    val ratio = (width.toDouble() / height).coerceIn(0.42, 2.38)
    return Rational((ratio * 1000).toInt(), 1000)
}

private const val BROWSER_PREFERENCES = "browser"
private const val HTTP2_ENABLED = "http2_enabled"

private object BrowserRuntime {
    var http2Enabled = true
        private set

    var enabledCiphers: Set<TlsCipherSuite> = emptySet()
        private set

    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime {
        runtime?.let { return it }
        val app = context.applicationContext
        val config = app.filesDir.resolve("geckoview.yaml")
        val preferences = app.getSharedPreferences(BROWSER_PREFERENCES, Context.MODE_PRIVATE)
        http2Enabled = preferences.getBoolean(HTTP2_ENABLED, true)
        enabledCiphers = readEnabledCiphers(preferences)
        val defaults = app.assets.open("geckoview.yaml").bufferedReader().use { it.readText() }
        config.writeText(buildString {
            appendLine(defaults.trimEnd())
            appendLine("  network.http.http2.enabled: $http2Enabled")
            for (cipher in TlsCipherSuite.entries) {
                appendLine("  ${cipher.preference}: ${cipher in enabledCiphers}")
            }
        })
        val settings = GeckoRuntimeSettings.Builder()
            .configFilePath(config.absolutePath)
            .build()
        return GeckoRuntime.create(app, settings).also { runtime = it }
    }
}

private data class MediaTarget(val url: String, val mime: String?, val referer: String? = null)

private fun readEnabledCiphers(preferences: SharedPreferences): Set<TlsCipherSuite> =
    TlsCipherSuite.entries.filterTo(mutableSetOf()) {
        preferences.getBoolean(it.preference, it.enabledByDefault)
    }

private class BrowserTabs(context: Context, urls: List<String>, selected: Int) {
    private val preferences = context.getSharedPreferences(BROWSER_PREFERENCES, Context.MODE_PRIVATE)
    private val runtime = BrowserRuntime.get(context)
    val executor = GeckoWebExecutor(runtime)
    val tabs = mutableStateListOf<BrowserSession>()
    var current by mutableStateOf<BrowserSession?>(null)
        private set
    var http2Enabled by mutableStateOf(preferences.getBoolean(HTTP2_ENABLED, true))
        private set
    val httpChangePending get() = http2Enabled != BrowserRuntime.http2Enabled
    var enabledCiphers by mutableStateOf(readEnabledCiphers(preferences))
        private set
    val cipherChangePending get() = enabledCiphers != BrowserRuntime.enabledCiphers
    var dark by mutableStateOf(preferences.getBoolean("dark", true))
        private set
    private var extensionReady = false
    private var inspector: WebExtension? = null
    private var closed = false
    private var nextTabId = 0L

    init {
        urls.forEach { newTab(it) }
        tabs.getOrNull(selected)?.let(::select)
        runtime.webExtensionController.ensureBuiltIn(
            "resource://android/assets/inspection/", "connection@browserengine.local"
        ).accept(::startInspector, { startInspector(null) })
    }

    private fun startInspector(extension: WebExtension?) {
        if (closed) return
        inspector = extension
        extensionReady = true
        tabs.forEach { it.startInspector(extension) }
    }

    fun newTab(url: String = "", popup: Boolean = false): BrowserSession {
        val tab = BrowserSession(nextTabId++, runtime, preferences, url, popup) {
            newTab(popup = true).session
        }
        tabs.add(tab)
        if (extensionReady) tab.startInspector(inspector)
        select(tab)
        return tab
    }

    fun select(tab: BrowserSession) {
        if (current !== tab) {
            current?.mediaChoice = null
            current = tab
        }
    }

    fun closeTab(tab: BrowserSession) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        if (current === tab) {
            if (tabs.isEmpty()) current = null else select(tabs[minOf(index, tabs.lastIndex)])
        }
        tab.close()
    }

    fun close() {
        closed = true
        tabs.forEach { it.close() }
        tabs.clear()
        current = null
    }

    fun setHttp2(value: Boolean) {
        http2Enabled = value
        preferences.edit().putBoolean(HTTP2_ENABLED, value).apply()
    }

    fun setTheme(value: Boolean) {
        dark = value
        preferences.edit().putBoolean("dark", value).apply()
    }

    fun toggleCipher(cipher: TlsCipherSuite) {
        val wasEnabled = cipher in enabledCiphers
        if (wasEnabled && enabledCiphers.size == 1) return
        enabledCiphers = if (wasEnabled) enabledCiphers - cipher else enabledCiphers + cipher
        preferences.edit().putBoolean(cipher.preference, !wasEnabled).apply()
    }

    fun resetCiphers() {
        preferences.edit().apply {
            TlsCipherSuite.entries.forEach { remove(it.preference) }
        }.apply()
        enabledCiphers = readEnabledCiphers(preferences)
    }
}

private class BrowserSession(
    val id: Long,
    runtime: GeckoRuntime,
    private val preferences: SharedPreferences,
    restore: String,
    popup: Boolean,
    private val openPopup: () -> GeckoSession
) {
    var desktop by mutableStateOf(preferences.getBoolean("desktop", false))
        private set
    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var progress by mutableIntStateOf(100)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var security by mutableStateOf<JSONObject?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var media by mutableStateOf<MediaTarget?>(null)
    val displayUrl get() = media?.url ?: url
    var mediaReload by mutableIntStateOf(0)
        private set
    var mediaChoice by mutableStateOf<MediaTarget?>(null)
    var fullscreen by mutableStateOf(false)
        private set
    var webPlaying by mutableStateOf(false)
        private set
    var webMediaSession by mutableStateOf<MediaSession?>(null)
        private set
    var webFeatures by mutableLongStateOf(MediaSession.Feature.NONE)
        private set
    var fullscreenAspect by mutableStateOf(Rational(16, 9))
        private set
    var videoPort by mutableStateOf<WebExtension.Port?>(null)
        private set
    var webPipIntegrated by mutableStateOf(false)
        private set
    var webPipRequested by mutableStateOf(false)
    var webVideoInteraction by mutableIntStateOf(0)
        private set
    private var extensionReady = false
    private var pendingUrl: String? = null

    val session = GeckoSession(GeckoSessionSettings.Builder()
        .userAgentOverride(BrowserNetwork.agent(desktop))
        .viewportMode(viewport())
        .build())

    init {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url?.toHttpUriOrNull() != null) this@BrowserSession.url = url
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@BrowserSession.canGoBack = canGoBack
            }

            override fun onLoadRequest(
                session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
                val mime = BrowserNetwork.mediaMime(request.uri, null)
                if (mime != null && request.uri.toHttpUriOrNull() != null) {
                    media = MediaTarget(request.uri, mime, request.triggerUri)
                    return GeckoResult.fromValue(org.mozilla.geckoview.AllowOrDeny.DENY)
                }
                return null
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                return GeckoResult.fromValue(openPopup())
            }

            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                if (uri != null) url = uri
                security = null
                return when (error.code) {
                    WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_BAD_HSTS_CERT -> {
                        val query = if (error.code == WebRequestError.ERROR_BAD_HSTS_CERT) "?hsts" else ""
                        GeckoResult.fromValue("resource://android/assets/errors/certificate.html" + query)
                    }
                    else -> {
                        failure = when (error.code) {
                            WebRequestError.ERROR_UNKNOWN_HOST -> "The site could not be found."
                            WebRequestError.ERROR_NET_TIMEOUT -> "The connection timed out."
                            WebRequestError.ERROR_SECURITY_SSL -> "A secure connection could not be established."
                            else -> "The page could not be loaded."
                        }
                        null
                    }
                }
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (url.toHttpUriOrNull() != null) {
                    this@BrowserSession.url = url
                    title = ""
                    fullscreen = false
                    webPipRequested = false
                    webPipIntegrated = false
                    webPlaying = false
                    webMediaSession = null
                    webFeatures = MediaSession.Feature.NONE
                    fullscreenAspect = Rational(16, 9)
                    security = null
                    failure = null
                    progress = 0
                }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                this@BrowserSession.progress = progress
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                progress = 100
            }
        }

        session.mediaSessionDelegate = object : MediaSession.Delegate {
            override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
                webMediaSession = mediaSession
            }

            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
                if (webMediaSession !== mediaSession) return
                webMediaSession = null
                webPlaying = false
                webFeatures = MediaSession.Feature.NONE
            }

            override fun onFeatures(session: GeckoSession, mediaSession: MediaSession, features: Long) {
                webMediaSession = mediaSession
                webFeatures = features
            }

            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) { webPlaying = true }
            override fun onPause(session: GeckoSession, mediaSession: MediaSession) { webPlaying = false }
            override fun onStop(session: GeckoSession, mediaSession: MediaSession) { webPlaying = false }

            override fun onFullscreen(
                session: GeckoSession, mediaSession: MediaSession, enabled: Boolean, meta: MediaSession.ElementMetadata?
            ) {
                fullscreenAspect = if (enabled && meta != null) videoAspect(meta.width, meta.height)
                else Rational(16, 9)
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                fullscreen = fullScreen
            }

            override fun onTitleChange(session: GeckoSession, title: String?) {
                this@BrowserSession.title = title.orEmpty()
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val type = response.headers.entries
                    .firstOrNull { it.key.equals("Content-Type", true) }?.value
                val mime = BrowserNetwork.mediaMime(response.uri, type)
                response.body?.let { runCatching { it.close() } }
                if (mime != null && response.uri.toHttpUriOrNull() != null) {
                    media = MediaTarget(response.uri, mime)
                }
            }

            override fun onContextMenu(
                session: GeckoSession, screenX: Int, screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                val source = element.srcUri ?: return
                if (source.toHttpUriOrNull() == null) return
                if (element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO ||
                    element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO
                ) mediaChoice = MediaTarget(source, BrowserNetwork.mediaMime(source, null), element.baseUri)
            }
        }

        if (!popup) session.open(runtime)
        if (restore.toHttpUriOrNull() != null) navigate(restore)
    }

    fun startInspector(extension: WebExtension?) {
        if (extension != null) {
            session.webExtensionController.setMessageDelegate(extension, object : WebExtension.MessageDelegate {
                override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                    security = message as? JSONObject
                    return null
                }
            }, "connection")
        }
        if (extension != null) {
            session.webExtensionController.setMessageDelegate(extension, object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    videoPort = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            if (videoPort !== port) return
                            when (message) {
                                "pip" -> webPipRequested = true
                                "integrated" -> webPipIntegrated = true
                                "fallback" -> webPipIntegrated = false
                                "touch" -> webVideoInteraction++
                            }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            if (videoPort !== port) return
                            videoPort = null
                            webPipIntegrated = false
                            webPipRequested = false
                        }
                    })
                }
            }, "video")
        }
        extensionReady = true
        val target = pendingUrl
        pendingUrl = null
        if (target != null) session.loadUri(target)
    }

    fun updateWebPip(supported: Boolean, inPip: Boolean) {
        videoPort?.postMessage(JSONObject().put("supported", supported).put("inPip", inPip))
    }

    fun close() {
        if (session.isOpen) session.close()
    }

    private fun viewport() = if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        else GeckoSessionSettings.VIEWPORT_MODE_MOBILE

    fun setDesktopMode(value: Boolean) {
        desktop = value
        preferences.edit().putBoolean("desktop", value).apply()
        session.settings.setUserAgentOverride(BrowserNetwork.agent(value))
        session.settings.setViewportMode(viewport())
        reload()
    }

    fun navigate(address: String) {
        val value = address.trim()
        if (value.isEmpty()) return
        val target = when {
            value.toHttpUriOrNull() != null -> value
            value.none(Char::isWhitespace) && ("." in value || value.startsWith("[") || value.startsWith("localhost")) &&
                "https://$value".toHttpUriOrNull() != null -> "https://$value"
            else -> "https://www.google.com/search?q=${Uri.encode(value)}"
        }
        val mime = BrowserNetwork.mediaMime(target, null)
        if (mime != null) {
            media = MediaTarget(target, mime)
            return
        }
        media = null
        failure = null
        security = null
        url = target
        if (extensionReady) session.loadUri(target) else pendingUrl = target
    }

    fun reload() = reload(bypassCache = false)

    fun reload(bypassCache: Boolean) {
        if (media != null) {
            mediaReload++
            return
        }
        if (url.isNotEmpty()) {
            failure = null
            security = null
            if (extensionReady) {
                session.reload(if (bypassCache) GeckoSession.LOAD_FLAGS_BYPASS_CACHE else GeckoSession.LOAD_FLAGS_NONE)
            } else pendingUrl = url
        }
    }

    fun home() {
        media = null
        pendingUrl = null
        session.loadUri("about:blank")
        url = ""
        title = ""
        failure = null
        security = null
    }
}

private enum class Screen { Browser, Settings, Theme, HttpVersions, TlsCiphers, Connection }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(
    tabs: BrowserTabs,
    lifecycleOwner: LifecycleOwner,
    supportsPip: Boolean,
    inPip: Boolean,
    enterPip: (Rational) -> Boolean,
    onNativePlayer: (Player?) -> Unit,
    setVideoFullscreen: (Boolean) -> Unit
) {
    val colors = if (tabs.dark) darkColorScheme(
        primary = Color(0xFFBFC7FF), background = Color.Black, surface = Color.Black,
        surfaceContainer = Color(0xFF17181F), surfaceContainerHigh = Color(0xFF21222B)
    ) else lightColorScheme(
        primary = Color(0xFF394EB3), background = Color.White, surface = Color.White
    )
    var tabsVisible by rememberSaveable { mutableStateOf(false) }
    val browser = tabs.current
    MaterialTheme(colorScheme = colors) {
        if (tabsVisible || browser == null) {
            BackHandler(enabled = browser != null) { tabsVisible = false }
            Scaffold(topBar = {
                TopAppBar(
                    title = { Text("Tabs") },
                    navigationIcon = {
                        if (browser != null) NavigationIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back") {
                            tabsVisible = false
                        }
                    },
                    actions = {
                        NavigationIcon(Icons.Default.Add, "New tab") {
                            tabs.newTab()
                            tabsVisible = false
                        }
                    }
                )
            }) { padding ->
                val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = appear,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    enter = fadeIn(tween(180))
                ) {
                    TabList(tabs) {
                        tabs.select(it)
                        tabsVisible = false
                    }
                }
            }
        } else {
            BrowserContent(
                tabs, browser, lifecycleOwner, supportsPip, inPip, enterPip, onNativePlayer,
                setVideoFullscreen, openTabs = { tabsVisible = true }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BrowserContent(
    tabs: BrowserTabs,
    browser: BrowserSession,
    lifecycleOwner: LifecycleOwner,
    supportsPip: Boolean,
    inPip: Boolean,
    enterPip: (Rational) -> Boolean,
    onNativePlayer: (Player?) -> Unit,
    setVideoFullscreen: (Boolean) -> Unit,
    openTabs: () -> Unit
) {
    var screen by rememberSaveable { mutableStateOf(Screen.Browser) }
    val displayedUrl = browser.displayUrl
    val media = browser.media
    val address = rememberTextFieldState(displayedUrl)
    val addressScroll = rememberScrollState()
    LaunchedEffect(browser, displayedUrl) {
        if (address.text.toString() != displayedUrl) {
            address.edit {
                replace(0, length, displayedUrl)
                selection = TextRange(0)
            }
            addressScroll.scrollTo(0)
        }
    }
    var options by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val submit = {
        focus.clearFocus()
        browser.navigate(address.text.toString())
    }
    val webFullscreen = browser.fullscreen && screen == Screen.Browser && media == null
    LaunchedEffect(browser, browser.videoPort, supportsPip, inPip) {
        browser.updateWebPip(supportsPip, inPip)
    }
    LaunchedEffect(browser, browser.webPipRequested, webFullscreen, supportsPip) {
        if (browser.webPipRequested && webFullscreen && supportsPip) {
            browser.webPipRequested = false
            enterPip(browser.fullscreenAspect)
        }
    }
    var showFallbackPip by remember(browser) { mutableStateOf(false) }
    LaunchedEffect(browser, webFullscreen, browser.webPipIntegrated, browser.webVideoInteraction, inPip) {
        showFallbackPip = webFullscreen && !browser.webPipIntegrated && !inPip
        if (showFallbackPip) {
            delay(2_500)
            showFallbackPip = false
        }
    }
    var nativeFullscreen by remember(browser, media) { mutableStateOf(false) }
    val immersiveVideo = (nativeFullscreen || webFullscreen) && !inPip
    DisposableEffect(immersiveVideo) {
        if (immersiveVideo) setVideoFullscreen(true)
        onDispose { if (immersiveVideo) setVideoFullscreen(false) }
    }
    val back = {
        when {
            nativeFullscreen -> nativeFullscreen = false
            webFullscreen -> browser.session.exitFullScreen()
            screen == Screen.Theme || screen == Screen.HttpVersions || screen == Screen.TlsCiphers -> screen = Screen.Settings
            screen != Screen.Browser -> screen = Screen.Browser
            media != null -> browser.media = null
            browser.canGoBack -> browser.session.goBack()
            else -> browser.home()
        }
    }

    BackHandler(!inPip && (browser.media != null || webFullscreen ||
        screen != Screen.Browser || browser.url.isNotEmpty()), onBack = back)

    Scaffold(topBar = {
        when {
            inPip || webFullscreen || nativeFullscreen -> Unit
            screen != Screen.Browser -> PageBar(
                when (screen) {
                    Screen.Settings -> "Settings"
                    Screen.Theme -> "Theme"
                    Screen.HttpVersions -> "HTTP versions"
                    Screen.TlsCiphers -> "TLS cipher suites"
                    else -> "Connection details"
                }, back
            )
            else -> Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavigationIcon(Icons.Default.Home, "Home") {
                    focus.clearFocus()
                    browser.home()
                    address.edit { replace(0, length, "") }
                }
                Row(
                    Modifier.weight(1f).height(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.extraLarge)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        state = address,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Search or enter address" },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        onKeyboardAction = { submit() },
                        scrollState = addressScroll,
                        decorator = { field ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (address.text.isEmpty()) {
                                    Text(
                                        "Search or enter address", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                field()
                            }
                        }
                    )
                    val edited = address.text.toString() != displayedUrl
                    NavigationIcon(
                        if (edited) Icons.Default.Search else Icons.Default.Refresh,
                        if (edited) "Go" else "Reload"
                    ) { if (edited) submit() else browser.reload() }
                }
                TabCounter(tabs.tabs.size) {
                    focus.clearFocus()
                    openTabs()
                }
                NavigationIcon(Icons.Default.MoreVert, "Connection details") { screen = Screen.Connection }
                Box {
                    NavigationIcon(Icons.Default.Settings, "Options") { options = true }
                    DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                        DropdownMenuItem(
                            text = { Text("Settings") }, leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { options = false; screen = Screen.Settings }
                        )
                        DropdownMenuItem(
                            text = { Text("Desktop site") }, leadingIcon = { Icon(Icons.Default.Computer, null) },
                            trailingIcon = { Checkbox(browser.desktop, onCheckedChange = null) },
                            onClick = { options = false; browser.setDesktopMode(!browser.desktop) }
                        )
                        DropdownMenuItem(
                            text = { Text("Back") }, enabled = media != null || browser.canGoBack,
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                            onClick = { options = false; back() }
                        )
                    }
                }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                media != null && screen == Screen.Browser -> key(browser.id, browser.mediaReload) {
                    VideoPlayer(
                        media, browser.desktop, tabs.executor, lifecycleOwner,
                        supportsPip, inPip, nativeFullscreen, enterPip,
                        { nativeFullscreen = !nativeFullscreen }, onNativePlayer
                    )
                }
                screen == Screen.Theme -> Column {
                    ThemeRow("Dark Theme", tabs.dark) { tabs.setTheme(true) }
                    ThemeRow("Light Theme", !tabs.dark) { tabs.setTheme(false) }
                }
                screen == Screen.TlsCiphers -> CipherSettings(tabs)
                screen == Screen.HttpVersions -> Column {
                    ListItem(
                        headlineContent = { Text("HTTP/1.1") },
                        supportingContent = { Text("Always available") },
                        trailingContent = { Checkbox(checked = true, onCheckedChange = null, enabled = false) }
                    )
                    ListItem(
                        headlineContent = { Text("HTTP/2") },
                        trailingContent = { Checkbox(tabs.http2Enabled, tabs::setHttp2) },
                        modifier = Modifier.clickable { tabs.setHttp2(!tabs.http2Enabled) }
                    )
                    if (tabs.httpChangePending) {
                        Text("Restart BrowserEngine to apply this change.", Modifier.padding(16.dp))
                    }
                }
                screen == Screen.Settings -> Column {
                    ListItem(
                        headlineContent = { Text("Theme") }, supportingContent = { Text(if (tabs.dark) "Dark Theme" else "Light Theme") },
                        leadingContent = { Icon(Icons.Default.Palette, null) },
                        modifier = Modifier.clickable { screen = Screen.Theme }
                    )
                    ListItem(
                        headlineContent = { Text("Desktop site") },
                        leadingContent = { Icon(Icons.Default.Computer, null) },
                        trailingContent = { Switch(browser.desktop, browser::setDesktopMode) }
                    )
                    ListItem(
                        headlineContent = { Text("HTTP versions") },
                        supportingContent = { Text(if (tabs.http2Enabled) "HTTP/1.1 and HTTP/2" else "HTTP/1.1 only") },
                        leadingContent = { Icon(Icons.Default.Language, null) },
                        modifier = Modifier.clickable { screen = Screen.HttpVersions }
                    )
                    ListItem(
                        headlineContent = { Text("TLS cipher suites") },
                        leadingContent = { Icon(Icons.Default.Lock, null) },
                        modifier = Modifier.clickable { screen = Screen.TlsCiphers }
                    )
                    ListItem(headlineContent = { Text("About BrowserEngine") },
                        supportingContent = { Text("Version ${BuildConfig.VERSION_NAME} · GeckoView 143") })
                }
                screen == Screen.Connection -> ConnectionScreen(browser) {
                    screen = Screen.Browser
                    browser.reload(bypassCache = true)
                }
                else -> {
                    AndroidView(
                        factory = { GeckoView(it).apply { setSession(browser.session) } },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { it.releaseSession() },
                        update = {
                            if (it.session !== browser.session) {
                                it.releaseSession()
                                it.setSession(browser.session)
                            }
                            it.visibility = if (browser.url.isEmpty() || browser.failure != null)
                                android.view.View.INVISIBLE else android.view.View.VISIBLE
                        }
                    )
                    when {
                        browser.url.isEmpty() -> {
                            val appear = remember(browser.id) { MutableTransitionState(false).apply { targetState = true } }
                            AnimatedVisibility(
                                visibleState = appear, modifier = Modifier.align(Alignment.Center),
                                enter = fadeIn(tween(180))
                            ) {
                                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Image(painterResource(R.drawable.ic_browser), "BrowserEngine icon", Modifier.size(96.dp))
                                    Spacer(Modifier.height(20.dp))
                                    Text("BrowserEngine", style = MaterialTheme.typography.headlineLarge)
                                }
                            }
                        }
                        browser.failure != null -> Column(
                            Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(browser.failure.orEmpty())
                            TextButton(onClick = browser::reload) { Text("Try again") }
                        }
                    }
                    if (showFallbackPip && !inPip && webFullscreen && supportsPip) {
                        PipButton(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                            enterPip(browser.fullscreenAspect)
                        }
                    }
                    AnimatedVisibility(
                        visible = !inPip && !webFullscreen && browser.url.isNotEmpty() && browser.progress < 100,
                        enter = fadeIn(), exit = fadeOut()
                    ) {
                        LinearProgressIndicator(progress = { browser.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    if (!inPip) browser.mediaChoice?.let { target ->
        AlertDialog(
            onDismissRequest = { browser.mediaChoice = null },
            title = { Text("Open in video player?") },
            confirmButton = {
                TextButton(onClick = { browser.mediaChoice = null; browser.media = target }) { Text("Play") }
            },
            dismissButton = { TextButton(onClick = { browser.mediaChoice = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PipButton(modifier: Modifier, enter: () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.70f)
    ) {
        IconButton(onClick = enter) {
            Icon(Icons.Default.PictureInPictureAlt, "Picture-in-picture", tint = Color.White)
        }
    }
}

@Composable
private fun TabCounter(count: Int, open: () -> Unit) {
    IconButton(onClick = open, modifier = Modifier.size(42.dp)) {
        Box(
            Modifier.size(width = 29.dp, height = 29.dp)
                .semantics { contentDescription = "$count open tabs" }
        ) {
            val shape = RoundedCornerShape(6.dp)
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).size(23.dp),
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {}
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).size(24.dp),
                shape = shape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = count,
                        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                        label = "Open tabs"
                    ) {
                        Text(if (it > 99) "99+" else it.toString(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabList(tabs: BrowserTabs, open: (BrowserSession) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(152.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tabs.tabs, key = { it.id }) { tab ->
                val selected = tab === tabs.current
                val host = tab.displayUrl.toHttpUriOrNull()?.host.orEmpty()
                val title = tab.title.takeIf { tab.media == null }.orEmpty().ifBlank { host.ifBlank { "New tab" } }
                OutlinedCard(
                    onClick = { open(tab) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(200), placementSpec = tween(240), fadeOutSpec = tween(200)
                    ).fillMaxWidth().heightIn(min = 112.dp),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            title, modifier = Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall
                        )
                        NavigationIcon(Icons.Default.Close, "Close $title") { tabs.closeTab(tab) }
                    }
                    if (host.isNotEmpty()) {
                        Text(
                            host, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = tabs.tabs.isEmpty(),
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(220, delayMillis = 160)),
            exit = fadeOut(tween(120))
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("No tabs open", style = MaterialTheme.typography.headlineSmall)
                Text("Tap + to open a new tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CipherSettings(tabs: BrowserTabs) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text("Cipher suites offered in ClientHello") },
                supportingContent = {
                    Text(if (tabs.cipherChangePending) "Restart BrowserEngine to apply your selection."
                    else "Select the cipher suites to offer on new TLS connections.")
                }
            )
            TextButton(onClick = tabs::resetCiphers, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Restore defaults")
            }
        }
        item {
            Text("TLS 1.3", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        }
        items(TlsCipherSuite.entries.filter { it.tls13 }, key = { it.preference }) {
            CipherRow(it, tabs)
        }
        item {
            Text("TLS 1.2", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        }
        items(TlsCipherSuite.entries.filterNot { it.tls13 }, key = { it.preference }) {
            CipherRow(it, tabs)
        }
    }
}

@Composable
private fun CipherRow(cipher: TlsCipherSuite, tabs: BrowserTabs) {
    val checked = cipher in tabs.enabledCiphers
    val enabled = !checked || tabs.enabledCiphers.size > 1
    ListItem(
        headlineContent = { Text(cipher.suiteName, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { tabs.toggleCipher(cipher) }
    )
}

@Composable
private fun NavigationIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(42.dp)) {
        Icon(icon, label, Modifier.size(21.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PageBar(title: String, back: () -> Unit) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    })
}

@Composable private fun ThemeRow(label: String, selected: Boolean, update: () -> Unit) {
    ListItem(headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected, onClick = null) },
        modifier = Modifier.clickable(onClick = update))
}

@Composable private fun ConnectionScreen(browser: BrowserSession, reload: () -> Unit) {
    val url = browser.displayUrl.toHttpUriOrNull()
    val info = browser.security?.takeIf { it.optString("url").toHttpUriOrNull() == url?.buildUpon()?.fragment(null)?.build() }
    fun field(name: String) = info?.optString(name).orEmpty()
    fun show(value: String) = value.ifBlank { "Unavailable" }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (url == null) {
            Text("Open a website to see its connection details.")
            return@Column
        }
        Text(url.host.orEmpty(), style = MaterialTheme.typography.titleLarge)
        if (url.scheme.equals("http", true)) {
            Text("No Encryption HTTP Connection", color = MaterialTheme.colorScheme.error)
            Text("Your Connection is not encrypted")
        } else {
            if (field("state") == "weak" || field("state") == "broken") {
                Text("This connection has security issues.", color = MaterialTheme.colorScheme.error)
            }
            ListItem(headlineContent = { Text("Cipher suite") }, supportingContent = { Text(show(field("cipher"))) })
            ListItem(headlineContent = { Text("Key exchange") }, supportingContent = { Text(show(keyExchangeLabel(field("keyExchange")))) })
            ListItem(headlineContent = { Text("TLS version") }, supportingContent = { Text(show(tlsVersionLabel(field("tls")))) })
        }
        ListItem(headlineContent = { Text("HTTP version") }, supportingContent = { Text(show(httpVersionLabel(field("http")))) })
        val ip = remoteAddressLabel(field("ip"))
        val cached = info?.optBoolean("fromCache") == true
        ListItem(
            headlineContent = { Text("Remote IP address") },
            supportingContent = {
                Text(when {
                    ip.isNotEmpty() && info?.optBoolean("cachedIp") == true -> "$ip · Last connection"
                    ip.isNotEmpty() -> ip
                    cached -> "Page loaded from cache"
                    else -> "No remote address reported"
                })
            }
        )
        if (ip.isEmpty() || cached) TextButton(onClick = reload) { Text("Reload to check") }
    }
}

@Suppress("DEPRECATION")
@Composable private fun VideoPlayer(
    target: MediaTarget,
    desktop: Boolean,
    executor: GeckoWebExecutor,
    lifecycleOwner: LifecycleOwner,
    supportsPip: Boolean,
    inPip: Boolean,
    fullscreen: Boolean,
    enterPip: (Rational) -> Boolean,
    toggleFullscreen: () -> Unit,
    onNativePlayer: (Player?) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = lifecycleOwner.lifecycle
    var failure by remember(target) { mutableStateOf<String?>(null) }
    var aspect by remember(target) { mutableStateOf<Rational?>(null) }
    var tracks by remember(target) { mutableStateOf(Tracks.EMPTY) }
    var showTracks by remember(target) { mutableStateOf(false) }
    val player = remember(target, desktop) {
        runCatching {
            val factory = DataSource.Factory {
                GeckoMediaDataSource(executor, BrowserNetwork.agent(desktop), target.referer)
            }
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(8_000, 180_000, 1_500, 3_000)
                .setTargetBufferBytes(32 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .build().apply {
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    addListener(object : Player.Listener {
                        override fun onTracksChanged(current: Tracks) { tracks = current }
                        override fun onPlayerError(error: PlaybackException) {
                            android.util.Log.e("BrowserEngine", "ExoPlayer playback failed", error)
                            failure = generateSequence(error as Throwable) { it.cause }
                                .filterIsInstance<MediaRangeRequiredException>().firstOrNull()?.message
                                ?: error.errorCodeName
                        }
                        override fun onVideoSizeChanged(size: com.google.android.exoplayer2.video.VideoSize) {
                            if (size.width > 0 && size.height > 0) {
                                val width = (size.width * size.pixelWidthHeightRatio).toLong()
                                aspect = videoAspect(width, size.height.toLong())
                            }
                        }
                    })
                    setMediaItem(MediaItem.Builder().setUri(target.url).setMimeType(target.mime).build())
                    prepare()
                    playWhenReady = true
                }
        }.getOrNull()
    }
    if (player == null) {
        Text("The video player could not be initialized.", Modifier.padding(16.dp))
        return
    }
    DisposableEffect(player, lifecycle) {
        onNativePlayer(player)
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { player.pause() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            onNativePlayer(null)
            player.release()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { viewContext ->
                LayoutInflater.from(viewContext).inflate(R.layout.browser_video_player, null) as PlayerView
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.player = null },
            update = { view ->
                if (view.player !== player) view.player = player
                view.useController = !inPip
                view.findViewById<ImageButton>(R.id.browser_exo_tracks).setOnClickListener { showTracks = true }
                view.findViewById<ImageButton>(R.id.browser_exo_fullscreen).apply {
                    setImageResource(if (fullscreen) com.google.android.exoplayer2.ui.R.drawable.exo_ic_fullscreen_exit
                        else com.google.android.exoplayer2.ui.R.drawable.exo_ic_fullscreen_enter)
                    contentDescription = view.context.getString(if (fullscreen) R.string.video_exit_fullscreen
                        else R.string.video_enter_fullscreen)
                    setOnClickListener { toggleFullscreen() }
                }
                view.findViewById<ImageButton>(R.id.browser_exo_pip).apply {
                    val shape = aspect
                    visibility = if (supportsPip && shape != null && !inPip) View.VISIBLE else View.GONE
                    setOnClickListener { if (shape != null) enterPip(shape) }
                }
            }
        )
        if (!inPip && showTracks) VideoTracksDialog(player, tracks) { showTracks = false }
        if (!inPip) failure?.let { Text("Playback error ($it)", Modifier.align(Alignment.TopCenter).padding(16.dp), color = Color.White) }
    }
}

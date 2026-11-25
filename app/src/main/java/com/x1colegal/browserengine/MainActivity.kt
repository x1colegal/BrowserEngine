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
import android.graphics.Bitmap
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
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Tracks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DataSource
import org.json.JSONObject
import org.json.JSONArray
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.StorageController
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
import androidx.compose.foundation.selection.toggleable
import androidx.core.view.doOnAttach
import android.net.Uri

class MainActivity : ComponentActivity() {
    private lateinit var browser: BrowserTabs
    private lateinit var experimentPreferences: SharedPreferences
    private var pendingDownloadPermissionCallback: ((Boolean) -> Unit)? = null
    private val storagePermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingDownloadPermissionCallback?.invoke(granted)
        pendingDownloadPermissionCallback = null
    }
    private var pendingExperiments by mutableStateOf<Set<String>>(emptySet())
    private var activeExperiments by mutableStateOf<Set<String>>(emptySet())
    private var discontinued by mutableStateOf(false)
    private var deprecatedPrompt by mutableStateOf(false)
    private var checkedLaunchVersion = false
    private var deprecatedCheckedThisLaunch = false
    private var foregroundExperimentCheck: kotlinx.coroutines.Job? = null
    private val experimentListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ServerExperiments.FLAGS) pendingExperiments = ServerExperiments.pending(this)
        if (key == ServerExperiments.DISCONTINUED_VERSION) discontinued = ServerExperiments.isDiscontinued(this)
        if (key == ServerExperiments.DEPRECATED_VERSION && !deprecatedCheckedThisLaunch &&
            ServerExperiments.shouldShowDeprecatedPrompt(this)
        ) {
            deprecatedCheckedThisLaunch = true
            deprecatedPrompt = true
        }
    }

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
        experimentPreferences = ServerExperiments.preferences(this)
        pendingExperiments = ServerExperiments.pending(this)
        discontinued = ServerExperiments.isDiscontinued(this)
        if (ServerExperiments.shouldShowDeprecatedPrompt(this)) {
            deprecatedCheckedThisLaunch = true
            deprecatedPrompt = true
        }
        if (experimentPreferences.getBoolean(ServerExperiments.PROMPTED, false)) {
            activeExperiments = pendingExperiments
        }
        experimentPreferences.registerOnSharedPreferenceChangeListener(experimentListener)
        ServerExperiments.schedule(applicationContext)
        val restartState = AppPrefs.get(this, RESTART_PREFERENCES)
        val restartUrls = restartState.getString(RESTART_TABS, null)?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                List(array.length()) { array.getString(it) }
            }.getOrNull()
        }
        val restartSelected = restartState.getInt(RESTART_SELECTED_TAB, 0)
        if (restartState.contains(RESTART_TABS)) restartState.edit().clear().apply()
        browser = lastCustomNonConfigurationInstance as? BrowserTabs ?: BrowserTabs(
            applicationContext,
            restartUrls ?: savedInstanceState?.getStringArrayList("tabs")
                ?: arrayListOf(savedInstanceState?.getString("url").orEmpty()),
            if (restartUrls != null) restartSelected else savedInstanceState?.getInt("selectedTab") ?: 0,
            homePresentation(activeExperiments),
            ServerExperiment.CERTIFICATE_WARNING.key in activeExperiments
        )
        browser.customCertificateWarning = ServerExperiment.CERTIFICATE_WARNING.key in activeExperiments
        browser.setHomePresentation(homePresentation(activeExperiments))
        browser.downloads.permissionRequester = { callback ->
            pendingDownloadPermissionCallback = callback
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (supportsPip) {
            pictureInPicture = isInPictureInPictureMode
            ContextCompat.registerReceiver(
                this, pipControls, IntentFilter(PIP_CONTROL), ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        applyStatusBarTheme(browser.dark)
        setContent {
            val dark = browser.dark
            LaunchedEffect(dark) { applyStatusBarTheme(dark) }
            val tab = browser.current
            LaunchedEffect(pictureInPicture, tab, tab?.webMediaSession, tab?.webPlaying, tab?.webFeatures) {
                updatePipControls()
            }
            BrowserScreen(
                browser, this, supportsPip, pictureInPicture, ::enterVideoPip,
                ::setNativePlayer, ::setVideoFullscreen, activeExperiments,
                pendingExperiments, discontinued, deprecatedPrompt, { deprecatedPrompt = false },
                ::restartBrowser, experimentPreferences
            )
        }
    }

    private fun restartBrowser() {
        val state = AppPrefs.get(this, RESTART_PREFERENCES)
        val urls = JSONArray().apply { browser.tabs.forEach { put(it.displayUrl) } }
        val saved = state.edit().putString(RESTART_TABS, urls.toString())
            .putInt(RESTART_SELECTED_TAB, browser.tabs.indexOf(browser.current)).commit()
        val flagsSaved = experimentPreferences.edit().putBoolean(ServerExperiments.PROMPTED, true).commit()
        if (!saved || !flagsSaved) {
            android.widget.Toast.makeText(this, "Couldn't save browser state", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, RestartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(RESTART_PREVIOUS_PID, android.os.Process.myPid()))
    }

    override fun onStart() {
        super.onStart()
        val canApply = experimentPreferences.getBoolean(ServerExperiments.PROMPTED, false)
        if (canApply) applyPendingExperiments()
        val forceCheck = !checkedLaunchVersion || discontinued
        checkedLaunchVersion = true
        foregroundExperimentCheck = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { ServerExperiments.fetch(applicationContext, onlyIfDue = !forceCheck) }
            }
            discontinued = ServerExperiments.isDiscontinued(this@MainActivity)
            if (!deprecatedCheckedThisLaunch && ServerExperiments.shouldShowDeprecatedPrompt(this@MainActivity)) {
                deprecatedCheckedThisLaunch = true
                deprecatedPrompt = true
            }
            if (canApply) applyPendingExperiments()
        }
    }

    override fun onPause() {
        browser.current?.captureThumbnail()
        super.onPause()
    }

    override fun onStop() {
        foregroundExperimentCheck?.cancel()
        foregroundExperimentCheck = null
        super.onStop()
    }

    private fun applyPendingExperiments() {
        activeExperiments = ServerExperiments.pending(this)
        browser.customCertificateWarning = ServerExperiment.CERTIFICATE_WARNING.key in activeExperiments
        browser.setHomePresentation(homePresentation(activeExperiments))
        browser.downloads.permissionRequester = { callback ->
            pendingDownloadPermissionCallback = callback
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyStatusBarTheme(dark: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
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
        if (tab.media == null) return false
        val aspect = nativePlayer?.takeIf { it.isPlaying }?.videoSize?.let { size ->
            if (size.width > 0 && size.height > 0) {
                videoAspect((size.width * size.pixelWidthHeightRatio).toLong(), size.height.toLong())
            } else null
        } ?: return false
        return enterVideoPip(aspect)
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
        experimentPreferences.unregisterOnSharedPreferenceChangeListener(experimentListener)
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
private const val WEBSITE_CACHE_DISABLED = "website_cache_disabled"
private const val HTTP2_ENABLED = "http2_enabled"
private const val HTTP3_ENABLED = "http3_enabled"
private const val COOKIES_ALLOWED = "cookies_allowed"

private const val THIRD_PARTY_COOKIES_ALLOWED = "third_party_cookies_allowed"

private fun cookieBehavior(enabled: Boolean, thirdPartyEnabled: Boolean) = when {
    !enabled -> ContentBlocking.CookieBehavior.ACCEPT_NONE
    thirdPartyEnabled -> ContentBlocking.CookieBehavior.ACCEPT_ALL
    else -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
}

private enum class HomePresentation { Basic, NativeFeed }

private fun homePresentation(experiments: Set<String>) = when {
    ServerExperiment.NATIVE_HOME.key in experiments -> HomePresentation.NativeFeed
    else -> HomePresentation.Basic
}

internal object BrowserRuntime {
    var websiteCacheDisabled = true
        private set

    var http2Enabled = true
        private set
    var http3Enabled = false
        private set

    var enabledCiphers: Set<TlsCipherSuite> = emptySet()
        private set

    private var runtime: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime {
        runtime?.let { return it }
        val app = context.applicationContext
        val config = app.filesDir.resolve("geckoview.yaml")
        val preferences = AppPrefs.get(app, BROWSER_PREFERENCES)
        websiteCacheDisabled = preferences.getBoolean(WEBSITE_CACHE_DISABLED, true)
        http2Enabled = preferences.getBoolean(HTTP2_ENABLED, true)
        http3Enabled = preferences.getBoolean(HTTP3_ENABLED, false)
        enabledCiphers = readEnabledCiphers(preferences)
        val defaults = app.assets.open("geckoview.yaml").bufferedReader().use { it.readText() }
        config.writeText(buildString {
            appendLine(defaults.trimEnd())
            appendLine("  network.http.http2.enabled: $http2Enabled")
            appendLine("  network.http.http3.enable: $http3Enabled")
            appendLine("  browser.cache.disk.enable: ${!websiteCacheDisabled}")
            appendLine("  browser.cache.memory.enable: ${!websiteCacheDisabled}")
            for (cipher in TlsCipherSuite.entries) {
                appendLine("  ${cipher.preference}: ${cipher in enabledCiphers}")
            }
            if (enabledCiphers.none { it.tls13 }) {
                appendLine("  security.tls.version.max: 3")
            }
            if (enabledCiphers.none { !it.tls13 }) {
                appendLine("  security.tls.version.min: 4")
            }
        })
        val settings = GeckoRuntimeSettings.Builder()
            .configFilePath(config.absolutePath)
            .contentBlocking(ContentBlocking.Settings.Builder()
                .cookieBehavior(cookieBehavior(
                    preferences.getBoolean(COOKIES_ALLOWED, true),
                    preferences.getBoolean(THIRD_PARTY_COOKIES_ALLOWED, true)
                ))
                .build())
            .build()
        return GeckoRuntime.create(app, settings).also { runtime = it }
    }
}

private data class MediaTarget(val url: String, val mime: String?, val referer: String? = null)

private fun readEnabledCiphers(preferences: SharedPreferences): Set<TlsCipherSuite> =
    TlsCipherSuite.entries.filterTo(mutableSetOf()) {
        preferences.getBoolean(it.preference, it.enabledByDefault)
    }

private class BrowserTabs(
    context: Context,
    urls: List<String>,
    selected: Int,
    private var homePresentation: HomePresentation,
    var customCertificateWarning: Boolean
) {
    private val preferences = AppPrefs.get(context, BROWSER_PREFERENCES)
    private val runtime = BrowserRuntime.get(context)
    val executor = GeckoWebExecutor(runtime)
    val downloads = BrowserDownloads.get(context)
    val history = BrowserHistory.get(context)
    val homeClient = HomeSessionClient(context, executor)
    private val homeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val homeRequests = mutableMapOf<BrowserSession, kotlinx.coroutines.Job>()
    var nativeHomeSections by mutableStateOf<List<HomeSection>?>(null)
        private set
    var nativeHomeWeather by mutableStateOf<WeatherInfo?>(null)
        private set
    var weatherUpdating by mutableStateOf(false)
        private set
    var weatherError by mutableStateOf<String?>(null)
        private set
    var homeBackoffSeconds by mutableIntStateOf(0)
        private set
    private var hasLoadedNativeHome by mutableStateOf(false)
    var incognito by mutableStateOf(false)
        private set
    val savedWeatherLocation get() = homeClient.savedWeatherLocation()
    var cookiesAllowed by mutableStateOf(preferences.getBoolean(COOKIES_ALLOWED, true))
        private set
    var thirdPartyCookiesAllowed by mutableStateOf(preferences.getBoolean(THIRD_PARTY_COOKIES_ALLOWED, true))
        private set
    var websiteCacheDisabled by mutableStateOf(preferences.getBoolean(WEBSITE_CACHE_DISABLED, true))
        private set
    val cacheChangePending get() = websiteCacheDisabled != BrowserRuntime.websiteCacheDisabled
    val tabs = mutableStateListOf<BrowserSession>()
    var current by mutableStateOf<BrowserSession?>(null)
        private set
    var http2Enabled by mutableStateOf(preferences.getBoolean(HTTP2_ENABLED, true))
        private set
    var http3Enabled by mutableStateOf(preferences.getBoolean(HTTP3_ENABLED, false))
        private set
    val httpChangePending get() = http2Enabled != BrowserRuntime.http2Enabled ||
        http3Enabled != BrowserRuntime.http3Enabled
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

    fun newTab(url: String = "", popup: Boolean = false, parentTabId: Long? = null): BrowserSession {
        val home = !popup && (url.isEmpty() || url.substringBefore('#') == HOME_URL)
        val tabId = nextTabId++
        val tab = BrowserSession(
            tabId, runtime, preferences, if (home) "" else url, popup, parentTabId, downloads, history, executor,
            incognito, { customCertificateWarning }
        ) {
            newTab(popup = true, parentTabId = tabId).session
        }
        tabs.add(tab)
        if (extensionReady) tab.startInspector(inspector)
        select(tab)
        if (home) openHome(tab)
        return tab
    }

    fun toggleIncognito() {
        incognito = !incognito
        homeRequests.values.forEach { it.cancel() }
        homeRequests.clear()
        val oldTabs = tabs.toList()
        tabs.clear()
        current = null
        oldTabs.forEach { it.close() }
        newTab()
    }

    fun openHome(tab: BrowserSession) {
        homeRequests.remove(tab)?.cancel()
        tab.home()
        if (homePresentation == HomePresentation.NativeFeed) openNativeHome(tab)
    }

    private fun openNativeHome(tab: BrowserSession) {
        tab.showNativeHome()
        tab.beginHomeLoading()
        homeBackoffSeconds = 0
        homeRequests[tab] = homeScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    runCatching {
                        homeClient.loadNativeHome { seconds ->
                            homeBackoffSeconds = seconds
                        }
                    }.onFailure {
                        android.util.Log.e("BrowserHome", "Failed to load native home", it)
                    }.getOrNull()
                }
                if (!tabs.contains(tab) || !tab.nativeHome) return@launch
                if (data != null) {
                    nativeHomeSections = data.sections
                    nativeHomeWeather = data.weather
                    hasLoadedNativeHome = true
                }
                tab.finishHomeLoading()
            } finally {
                homeBackoffSeconds = 0
                if (homeRequests[tab] === coroutineContext[kotlinx.coroutines.Job]) {
                    homeRequests.remove(tab)
                    tab.finishHomeLoading()
                }
            }
        }
    }

    suspend fun searchWeatherLocations(query: String): List<WeatherLocationResult> =
        withContext(Dispatchers.IO) { homeClient.searchWeatherLocations(query) }

    fun updateWeather(location: String, label: String? = null) {
        if (weatherUpdating) return
        weatherUpdating = true
        weatherError = null
        homeScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    runCatching { homeClient.updateWeather(location, label) }
                }
                updated.onSuccess { nativeHomeWeather = it }
                updated.onFailure { weatherError = "Could not load weather for \"${label ?: location}\"." }
            } finally {
                weatherUpdating = false
            }
        }
    }

    fun refreshWeather() {
        val saved = homeClient.savedWeatherLocation()
        if (saved.isBlank()) return
        updateWeather(saved, homeClient.savedWeatherLabel().takeIf { it.isNotBlank() })
    }

    fun canReloadHome(tab: BrowserSession): Boolean =
        !tab.nativeHome || (!tab.homeLoading && homeBackoffSeconds == 0 && hasLoadedNativeHome)

    fun reload(tab: BrowserSession) {
        if (tab.nativeHome) {
            if (!canReloadHome(tab)) return
            nativeHomeSections = null
            nativeHomeWeather = null
            openHome(tab)
        } else {
            tab.reload()
        }
    }

    fun setHomePresentation(presentation: HomePresentation) {
        if (homePresentation == presentation) return
        homePresentation = presentation
        tabs.filter { it.displayUrl.isEmpty() || it.displayUrl.substringBefore('#') == HOME_URL }
            .forEach { openHome(it) }
    }

    fun select(tab: BrowserSession) {
        if (current !== tab) {
            current?.captureThumbnail()
            current?.mediaChoice = null
            current?.downloadChoice = null
            current = tab
        }
    }

    fun closeTab(tab: BrowserSession) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        homeRequests.remove(tab)?.cancel()
        tabs.removeAt(index)
        if (current === tab) {
            if (tabs.isEmpty()) current = null
            else {
                val parent = tab.openedFromTabId?.let { pid -> tabs.firstOrNull { it.id == pid } }
                select(parent ?: tabs[minOf(index, tabs.lastIndex)])
            }
        }
        tab.close()
    }

    fun close() {
        closed = true
        homeScope.cancel()
        tabs.forEach { it.close() }
        tabs.clear()
        current = null
    }

    fun updateWebsiteCache(disabled: Boolean) {
        websiteCacheDisabled = disabled
        preferences.edit().putBoolean(WEBSITE_CACHE_DISABLED, disabled).apply()
    }

    fun setHttp2(value: Boolean) {
        http2Enabled = value
        preferences.edit().putBoolean(HTTP2_ENABLED, value).apply()
    }

    fun setHttp3(value: Boolean) {
        http3Enabled = value
        preferences.edit().putBoolean(HTTP3_ENABLED, value).apply()
    }

    fun setTheme(value: Boolean) {
        dark = value
        preferences.edit().putBoolean("dark", value).apply()
    }

    fun updateCookiePolicy(allowed: Boolean) {
        cookiesAllowed = allowed
        preferences.edit().putBoolean(COOKIES_ALLOWED, allowed).apply()
        applyCookiePolicy()
    }

    fun updateThirdPartyCookiePolicy(allowed: Boolean) {
        thirdPartyCookiesAllowed = allowed
        preferences.edit().putBoolean(THIRD_PARTY_COOKIES_ALLOWED, allowed).apply()
        applyCookiePolicy()
    }

    private fun applyCookiePolicy() {
        runtime.settings.contentBlocking.setCookieBehavior(cookieBehavior(cookiesAllowed, thirdPartyCookiesAllowed))
    }

    fun clearCookies(result: (Boolean) -> Unit) {
        runtime.storageController.clearData(StorageController.ClearFlags.COOKIES)
            .accept({ result(true) }, { result(false) })
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

internal data class ContextMenuTarget(val imageUrl: String? = null, val linkUrl: String? = null)

private class BrowserSession(
    val id: Long,
    private val runtime: GeckoRuntime,
    private val preferences: SharedPreferences,
    restore: String,
    popup: Boolean,
    val openedFromTabId: Long? = null,
    private val downloads: BrowserDownloads,
    private val history: BrowserHistory,
    private val executor: GeckoWebExecutor,
    private val incognito: Boolean,
    private val useCustomCertificateWarning: () -> Boolean,
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
    private var pageCertificate by mutableStateOf<GeckoSession.ProgressDelegate.SecurityInformation?>(null)
    private var rejectedCertificate by mutableStateOf<Pair<String, java.security.cert.X509Certificate>?>(null)
    val certificateRejected get() = rejectedCertificate?.first == displayUrl.substringBefore('#')
    val certificate get() = if (certificateRejected) rejectedCertificate?.second
        else pageCertificate?.takeIf { it.host.equals(displayUrl.toHttpUriOrNull()?.host, true) }?.certificate
    var failure by mutableStateOf<String?>(null)
        private set
    var media by mutableStateOf<MediaTarget?>(null)
    val displayUrl get() = media?.url ?: url
    var mediaReload by mutableIntStateOf(0)
        private set
    val closeOnBack: Boolean = popup || openedFromTabId != null
    var mediaChoice by mutableStateOf<MediaTarget?>(null)
    var downloadChoice by mutableStateOf<ContextMenuTarget?>(null)
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
    var webPipRequested by mutableStateOf(false)
    var thumbnail by mutableStateOf<Bitmap?>(null)
        private set
    private var attachedWebView: GeckoView? = null
    private var allowNewSession = false
    private val pendingNavigationUrls = mutableListOf<String>()
    private var extensionReady = false
    private var pendingUrl: String? = null
    private var modeChangeUrl: String? = null
    private var modeTrace = 0

    private fun traceMode(message: String) {
        if (BuildConfig.DEBUG && modeTrace > 0) android.util.Log.d("BrowserEngineMode", "tab=$id switch=$modeTrace $message")
    }
    private var mobilePageUrl: String? = null
    private var firstDesktopPage: String? = null
    private var modePageStarted = false
    private val modeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    var nativeHome by mutableStateOf(false)
        private set
    private var inspector: WebExtension? = null
    var homeLoading by mutableStateOf(false)
        private set

    private fun createSession() = GeckoSession(GeckoSessionSettings.Builder()
        .usePrivateMode(incognito)
        .viewportMode(viewport())
        .userAgentMode(userAgentMode())
        .userAgentOverride(BrowserNetwork.agent(desktop))
        .build())

    var session by mutableStateOf(createSession())
        private set
    var pipSession by mutableStateOf<GeckoSession?>(null)
        private set

    fun clearPipSession() {
        val old = pipSession
        pipSession = null
        if (old != null && old.isOpen) old.close()
    }

    init {
        configureSession()
        PlatformPip.configure(session, runtime, onEnter = { cloned ->
            clearPipSession()
            pipSession = cloned
        }, onExit = {
            clearPipSession()
        })
        if (!popup) session.open(runtime)
        if (restore.toHttpUriOrNull() != null) navigate(restore)
    }

    private fun configureSession() {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (session === this@BrowserSession.session && !nativeHome && url?.toHttpUriOrNull() != null) {
                    val changed = this@BrowserSession.url != url
                    this@BrowserSession.url = url
                    if (changed) {
                        pendingNavigationUrls.add(url)
                        attachedWebView?.postDelayed({ captureThumbnail() }, 600)
                    }
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                if (session === this@BrowserSession.session && !nativeHome) {
                    this@BrowserSession.canGoBack = canGoBack
                }
            }

            override fun onLoadRequest(
                session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<org.mozilla.geckoview.AllowOrDeny>? {
                if (session !== this@BrowserSession.session || nativeHome) {
                    return GeckoResult.fromValue(org.mozilla.geckoview.AllowOrDeny.DENY)
                }
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    allowNewSession = request.hasUserGesture && !request.isRedirect
                    if (!allowNewSession) {
                        return GeckoResult.fromValue(org.mozilla.geckoview.AllowOrDeny.DENY)
                    }
                } else {
                    allowNewSession = false
                    if (!request.isRedirect) pendingNavigationUrls.clear()
                    pendingNavigationUrls.add(request.uri)
                }
                val mime = BrowserNetwork.mediaMime(request.uri, null)
                if (mime != null && request.uri.toHttpUriOrNull() != null) {
                    media = MediaTarget(request.uri, mime, request.triggerUri)
                    return GeckoResult.fromValue(org.mozilla.geckoview.AllowOrDeny.DENY)
                }
                return null
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val allowed = allowNewSession
                allowNewSession = false
                if (!allowed) return null
                return GeckoResult.fromValue(openPopup())
            }

            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                if (session !== this@BrowserSession.session || nativeHome) return null
                if (uri != null) url = uri
                rejectedCertificate = uri
                    ?.takeIf { it.toHttpUriOrNull()?.scheme.equals("https", true) }
                    ?.let { failedUrl -> error.certificate?.let { failedUrl.substringBefore('#') to it } }
                finishHomeLoading()
                security = null
                return when (error.code) {
                    WebRequestError.ERROR_SECURITY_BAD_CERT, WebRequestError.ERROR_BAD_HSTS_CERT -> {
                        val query = if (error.code == WebRequestError.ERROR_BAD_HSTS_CERT) "?hsts" else ""
                        val page = if (useCustomCertificateWarning()) "certificate-warning.html" else "certificate.html"
                        GeckoResult.fromValue("resource://android/assets/errors/$page$query")
                    }
                    WebRequestError.ERROR_SECURITY_SSL -> {
                        if (useCustomCertificateWarning()) {
                            GeckoResult.fromValue("resource://android/assets/errors/certificate-warning.html?tls")
                        } else {
                            failure = "A secure connection could not be established."
                            null
                        }
                    }
                    else -> {
                        failure = when (error.code) {
                            WebRequestError.ERROR_UNKNOWN_HOST -> "The site could not be found."
                            WebRequestError.ERROR_NET_TIMEOUT -> "The connection timed out."
                            else -> "The page could not be loaded."
                        }
                        null
                    }
                }
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (session === this@BrowserSession.session && !nativeHome && url.toHttpUriOrNull() != null) {
                    rejectedCertificate = null
                    modePageStarted = true
                    traceMode("pageStart host=${url.toHttpUriOrNull()?.host}")
                    this@BrowserSession.url = url
                    title = ""
                    thumbnail = null
                    fullscreen = false
                    webPipRequested = false
                    webPlaying = false
                    webMediaSession = null
                    webFeatures = MediaSession.Feature.NONE
                    fullscreenAspect = Rational(16, 9)
                    security = security?.takeIf { it.optString("url").sameOriginAs(url) }
                    failure = null
                    progress = 0
                }
            }

            override fun onSecurityChange(
                session: GeckoSession, securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
            ) {
                if (session === this@BrowserSession.session && !nativeHome) pageCertificate = securityInfo
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                this@BrowserSession.progress = progress
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (session !== this@BrowserSession.session || nativeHome) return
                traceMode("pageStop success=$success host=${url.toHttpUriOrNull()?.host} hasInspectorReport=${security != null}")
                if (success && modePageStarted && desktop && mobilePageUrl != null && firstDesktopPage == null) {
                    firstDesktopPage = url.substringBefore('#')
                }
                if (success && url.toHttpUriOrNull() != null) {
                    history.recordSite(url, title, executor, incognito)
                }
                if (success) pendingNavigationUrls.clear()
                progress = 100
                if (success) {
                    attachedWebView?.post { captureThumbnail() }
                    attachedWebView?.postDelayed({ captureThumbnail() }, 350)
                }
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
                if (!nativeHome && !title.isNullOrBlank() && url.toHttpUriOrNull() != null) {
                    history.recordSite(url, title, executor, incognito)
                }
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val mime = BrowserNetwork.mediaMime(response.uri, response.header("Content-Type"))
                val attachment = response.header("Content-Disposition")
                    ?.substringBefore(';')?.trim()?.equals("attachment", true) == true
                if (!attachment && mime != null && response.uri.toHttpUriOrNull() != null) {
                    response.body?.let { runCatching { it.close() } }
                    media = MediaTarget(response.uri, mime)
                } else if (response.statusCode in 200..299 && response.body != null) {
                    history.suppressDownloadUrl(response.uri)
                    pendingNavigationUrls.forEach(history::suppressDownloadUrl)
                    pendingNavigationUrls.clear()
                    downloads.receive(response, incognito)
                } else {
                    response.body?.let { runCatching { it.close() } }
                }
            }

            override fun onContextMenu(
                session: GeckoSession, screenX: Int, screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                val source = element.srcUri?.takeIf { it.toHttpUriOrNull() != null }
                if (source != null && (element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO ||
                    element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO)
                ) {
                    mediaChoice = MediaTarget(source, BrowserNetwork.mediaMime(source, null), element.baseUri)
                } else {
                    val imageUrl = source?.takeIf {
                        element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
                    }
                    val linkUrl = element.linkUri?.takeIf { it.toHttpUriOrNull() != null }
                    if (imageUrl != null || linkUrl != null) {
                        downloadChoice = ContextMenuTarget(imageUrl = imageUrl, linkUrl = linkUrl)
                    } else if (source != null) {
                        downloadChoice = ContextMenuTarget(linkUrl = source)
                    }
                }
            }
        }

    }

    fun startInspector(extension: WebExtension?) {
        inspector = extension
        if (extension != null) {
            val inspectedSession = session
            inspectedSession.webExtensionController.setMessageDelegate(extension, object : WebExtension.MessageDelegate {
                override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                    if (session !== inspectedSession || nativeHome || message !is JSONObject) return null
                    if (message.optString("event") == "pageshow") {
                        traceMode(
                            "pageshow host=${message.optString("host")} jsUa=${message.optString("userAgent")} " +
                                "width=${message.optInt("width")} screenWidth=${message.optInt("screenWidth")} " +
                                "viewport=${message.optString("viewport")} protocol=${message.optString("nextHopProtocol")} " +
                                "serviceWorker=${message.optBoolean("serviceWorker")} workerStart=${message.optDouble("workerStart", 0.0)}"
                        )
                    } else {
                        traceMode(
                            "request host=${message.optString("url").toHttpUriOrNull()?.host} " +
                                "requestUa=${message.optString("requestUserAgent")} http=${message.optString("http")} " +
                                "tls=${message.optString("tls")} hasIp=${message.optString("ip").isNotEmpty()} " +
                                "fromCache=${message.optBoolean("fromCache")}"
                        )
                        if (message.optString("http").isNotEmpty() || message.optString("tls").isNotEmpty() ||
                            message.optString("navProtocol").isNotEmpty() || message.optString("ip").isNotEmpty()
                        ) {
                            security = message
                        }
                    }
                    return GeckoResult.fromValue<Any>(true)
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
                            if (message == "pip") webPipRequested = true
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            if (videoPort !== port) return
                            videoPort = null
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

    fun detachWebView(view: GeckoView) {
        if (attachedWebView === view) attachedWebView = null
    }

    fun captureThumbnail() {
        val view = attachedWebView ?: return
        if (nativeHome || media != null || url.isEmpty()) return
        runCatching {
            view.capturePixels().accept({ bmp ->
                if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                    val maxW = 480
                    thumbnail = if (bmp.width > maxW) {
                        val h = (bmp.height * (maxW.toFloat() / bmp.width)).toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(bmp, maxW, h, true).also { if (it !== bmp) bmp.recycle() }
                    } else bmp
                }
            }, {})
        }
    }

    fun close() {
        attachedWebView = null
        modeHandler.removeCallbacksAndMessages(null)
        clearPipSession()
        if (session.isOpen) session.close()
    }

    private fun viewport() = if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        else GeckoSessionSettings.VIEWPORT_MODE_MOBILE

    private fun userAgentMode() = if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        else GeckoSessionSettings.USER_AGENT_MODE_MOBILE

    fun setDesktopMode(value: Boolean) {
        val target = if (!value && url.substringBefore('#') == firstDesktopPage) mobilePageUrl ?: url else url
        mobilePageUrl = target.takeIf { value && it.isNotEmpty() }
        firstDesktopPage = null
        desktop = value
        modeTrace++
        traceMode("switch desktop=$desktop targetHost=${target.toHttpUriOrNull()?.host} expectedUa=${BrowserNetwork.agent(desktop)}")
        preferences.edit().putBoolean("desktop", value).apply()
        failure = null
        security = null
        replaceWebSession()
        modeChangeUrl = target.takeIf { it.toHttpUriOrNull() != null }
        if (media != null) mediaReload++
    }

    fun onWebViewAttached(attachedSession: GeckoSession, view: GeckoView? = null) {
        if (view != null) attachedWebView = view
        if (session !== attachedSession) return
        val target = modeChangeUrl ?: return
        modeChangeUrl = null
        modePageStarted = false
        url = target
        traceMode("attached host=${target.toHttpUriOrNull()?.host} userAgentMode=${session.settings.userAgentMode} viewportMode=${session.settings.viewportMode} override=${session.settings.userAgentOverride}")
        session.load(GeckoSession.Loader().uri(target).flags(GeckoSession.LOAD_FLAGS_BYPASS_CACHE))
    }

    fun navigate(address: String) {
        val value = address.trim()
        if (value.isEmpty()) return
        val isSearch = value.toHttpUriOrNull() == null &&
            !(value.none(Char::isWhitespace) && ("." in value || value.startsWith("[") || value.startsWith("localhost")) &&
                "https://$value".toHttpUriOrNull() != null)
        val target = when {
            value.toHttpUriOrNull() != null -> value
            !isSearch -> "https://$value"
            else -> "https://www.google.com/search?q=${Uri.encode(value)}"
        }
        if (isSearch) history.recordSearch(value, target, incognito)
        val mime = BrowserNetwork.mediaMime(target, null)
        if (mime != null) {
            media = MediaTarget(target, mime)
            return
        }
        finishHomeLoading()
        modeChangeUrl = null
        modeHandler.removeCallbacksAndMessages(null)
        mobilePageUrl = null
        firstDesktopPage = null
        pageCertificate = null
        rejectedCertificate = null
        nativeHome = false
        media = null
        failure = null
        security = null
        url = target
        if (extensionReady) session.loadUri(target) else pendingUrl = target
    }

    fun beginHomeLoading() {
        homeLoading = true
    }

    fun finishHomeLoading() {
        homeLoading = false
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

    fun showNativeHome() {
        nativeHome = true
        replaceWebSession()
    }

    private fun replaceWebSession() {
        modeHandler.removeCallbacksAndMessages(null)
        modePageStarted = false
        pendingUrl = null
        modeChangeUrl = null
        pageCertificate = null
        rejectedCertificate = null
        val previous = session
        session = createSession()
        configureSession()
        PlatformPip.configure(session, runtime, onEnter = { cloned ->
            clearPipSession()
            pipSession = cloned
        }, onExit = {
            clearPipSession()
        })
        clearPipSession()
        PlatformPip.configure(session, runtime, onEnter = { cloned ->
            clearPipSession()
            pipSession = cloned
        }, onExit = {
            clearPipSession()
        })
        if (previous.isOpen) previous.close()
        fullscreen = false
        webPlaying = false
        webMediaSession = null
        webFeatures = MediaSession.Feature.NONE
        videoPort = null
        webPipRequested = false
        mediaChoice = null
        downloadChoice = null
        canGoBack = false
        progress = 100
        session.open(runtime)
        if (extensionReady) startInspector(inspector)
    }

    fun home() {
        nativeHome = false
        finishHomeLoading()
        media = null
        pendingUrl = null
        session.stop()
        modeChangeUrl = null
        pageCertificate = null
        rejectedCertificate = null
        url = ""
        title = ""
        thumbnail = null
        failure = null
        security = null
    }
}

private enum class Screen { Browser, Settings, Theme, HttpVersions, TlsCiphers, Cookies, Connection, Certificate, Downloads, History }

@Composable
private fun UpdateRequiredScreen() {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = scheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.35f))
            UpdateEmblem(
                primary = scheme.primary,
                container = scheme.surfaceContainerHigh,
                modifier = Modifier.size(120.dp)
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Update BrowserEngine",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "This version is no longer supported. Download the latest release to keep browsing with fixes and security updates.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp)
            )
            Spacer(Modifier.weight(0.65f))
            Button(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/x1colegal/BrowserEngine/releases")
                    )
                    runCatching { context.startActivity(intent) }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .padding(bottom = 36.dp)
                    .height(48.dp)
                    .widthIn(min = 160.dp)
            ) {
                Text("Update", fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
            }
        }
    }
}

@Composable
private fun UpdateEmblem(primary: Color, container: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawCircle(color = container, radius = w * 0.46f, center = Offset(w * 0.5f, h * 0.5f))
        drawCircle(
            color = primary.copy(alpha = 0.25f),
            radius = w * 0.46f,
            center = Offset(w * 0.5f, h * 0.5f),
            style = Stroke(width = w * 0.025f)
        )
        // Stylized download / update arrow
        val stroke = w * 0.075f
        drawLine(
            color = primary,
            start = Offset(w * 0.5f, h * 0.28f),
            end = Offset(w * 0.5f, h * 0.58f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        val chevron = Path().apply {
            moveTo(w * 0.36f, h * 0.47f)
            lineTo(w * 0.5f, h * 0.61f)
            lineTo(w * 0.64f, h * 0.47f)
        }
        drawPath(
            path = chevron,
            color = primary,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = primary,
            start = Offset(w * 0.33f, h * 0.70f),
            end = Offset(w * 0.67f, h * 0.70f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserScreen(
    tabs: BrowserTabs,
    lifecycleOwner: LifecycleOwner,
    supportsPip: Boolean,
    inPip: Boolean,
    enterPip: (Rational) -> Boolean,
    onNativePlayer: (Player?) -> Unit,
    setVideoFullscreen: (Boolean) -> Unit,
    activeExperiments: Set<String>,
    pendingExperiments: Set<String>,
    discontinued: Boolean,
    deprecatedPrompt: Boolean,
    onDismissDeprecated: () -> Unit,
    restart: () -> Unit,
    experimentPreferences: SharedPreferences
) {
    val colors = if (tabs.dark) darkColorScheme(
        primary = Color(0xFFBFC7FF), background = Color.Black, surface = Color.Black,
        surfaceContainer = Color(0xFF17181F), surfaceContainerHigh = Color(0xFF21222B)
    ) else lightColorScheme(
        primary = Color(0xFF394EB3), background = Color.White, surface = Color.White
    )
    var tabsVisible by rememberSaveable { mutableStateOf(false) }
    val browser = tabs.current
    var restartPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(pendingExperiments, inPip, discontinued) {
        if (!discontinued && !inPip && pendingExperiments.isNotEmpty() &&
            !experimentPreferences.getBoolean(ServerExperiments.PROMPTED, false)
        ) {
            experimentPreferences.edit().putBoolean(ServerExperiments.PROMPTED, true).apply()
            restartPrompt = true
        }
    }
    MaterialTheme(colorScheme = colors) {
        if (discontinued) {
            UpdateRequiredScreen()
            return@MaterialTheme
        }
        if (deprecatedPrompt && !inPip) {
            val context = LocalContext.current
            AlertDialog(
                onDismissRequest = onDismissDeprecated,
                title = { Text("Update recommended") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This version of BrowserEngine is deprecated and may stop working soon.")
                        TextButton(
                            onClick = {
                                ServerExperiments.suppressDeprecatedForCurrentVersion(context)
                                onDismissDeprecated()
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) { Text("Don't show again for this version") }
                        TextButton(
                            onClick = {
                                ServerExperiments.suppressDeprecatedForever(context)
                                onDismissDeprecated()
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) { Text("Don't show again") }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        onDismissDeprecated()
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/x1colegal/BrowserEngine/releases")
                                )
                            )
                        }
                    }) { Text("Update") }
                },
                dismissButton = {
                    TextButton(onClick = onDismissDeprecated) { Text("OK") }
                }
            )
        }
        if (restartPrompt && !inPip) {
            AlertDialog(
                onDismissRequest = { restartPrompt = false },
                title = { Text("Server Experiments") },
                text = { Text("New experiments are ready. Restart BrowserEngine to try them now.") },
                confirmButton = {
                    TextButton(onClick = { restartPrompt = false; restart() }) { Text("Restart") }
                },
                dismissButton = {
                    TextButton(onClick = { restartPrompt = false }) { Text("Later") }
                }
            )
        }
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
                    TabList(tabs, activeExperiments) {
                        tabs.select(it)
                        tabsVisible = false
                    }
                }
            }
        } else {
            BrowserContent(
                tabs, browser, lifecycleOwner, supportsPip, inPip, enterPip, onNativePlayer,
                setVideoFullscreen, activeExperiments, openTabs = {
                    browser.captureThumbnail()
                    tabsVisible = true
                }
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
    activeExperiments: Set<String>,
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
    var addressFocused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val initialContentFocus = remember { FocusRequester() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(Unit) {
        focus.clearFocus(force = true)
        keyboardController?.hide()
        runCatching { initialContentFocus.requestFocus() }
    }
    val submit = {
        focus.clearFocus()
        browser.navigate(address.text.toString())
    }
    val webFullscreen = browser.fullscreen && screen == Screen.Browser && media == null
    LaunchedEffect(screen, browser) {
        if (screen != Screen.Browser) browser.captureThumbnail()
    }
    LaunchedEffect(browser, browser.videoPort) {
        browser.updateWebPip(false, false)
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
            screen == Screen.Theme || screen == Screen.HttpVersions || screen == Screen.TlsCiphers || screen == Screen.Cookies -> screen = Screen.Settings
            screen == Screen.Certificate -> screen = Screen.Connection
            screen != Screen.Browser -> screen = Screen.Browser
            media != null -> browser.media = null
            browser.canGoBack -> browser.session.goBack()
            browser.closeOnBack && tabs.tabs.size > 1 -> tabs.closeTab(browser)
            else -> tabs.openHome(browser)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    DisposableEffect(tabs.downloads) {
        fun showDownloadBanner(message: String) {
            uiScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = "See Downloads",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    screen = Screen.Downloads
                }
            }
        }
        tabs.downloads.onDownloadStarted = { showDownloadBanner("Download Started.") }
        tabs.downloads.onDownloadFinished = { showDownloadBanner("Download Finished.") }
        onDispose {
            tabs.downloads.onDownloadStarted = null
            tabs.downloads.onDownloadFinished = null
        }
    }

    BackHandler(!inPip && (browser.media != null || webFullscreen ||
        screen != Screen.Browser || browser.url.isNotEmpty() || (browser.closeOnBack && tabs.tabs.size > 1)), onBack = back)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
        when {
            inPip || webFullscreen || nativeFullscreen -> Unit
            screen != Screen.Browser -> PageBar(
                when (screen) {
                    Screen.Settings -> "Settings"
                    Screen.Theme -> "Theme"
                    Screen.HttpVersions -> "HTTP versions"
                    Screen.TlsCiphers -> "TLS cipher suites"
                    Screen.Cookies -> "Cookies"
                    Screen.Downloads -> "Downloads"
                    Screen.History -> "History"
                    Screen.Certificate -> "Certificate Details"
                    else -> "Connection details"
                }, back
            )
            else -> Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavigationIcon(Icons.Default.Home, "Home") {
                    focus.clearFocus()
                    tabs.openHome(browser)
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
                        modifier = Modifier.weight(1f)
                            .onFocusChanged { addressFocused = it.isFocused }
                            .semantics { contentDescription = "Search or enter address" },
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
                    val reloadEnabled = edited || tabs.canReloadHome(browser)
                    NavigationIcon(
                        if (edited) Icons.AutoMirrored.Filled.ArrowForward else Icons.Default.Refresh,
                        if (edited) "Go" else "Reload",
                        enabled = reloadEnabled
                    ) { if (edited) submit() else tabs.reload(browser) }
                }
                TabCounter(tabs.tabs.size) {
                    focus.clearFocus()
                    openTabs()
                }
                NavigationIcon(
                    if (ServerExperiment.CONNECTION_INFO_ICON.key in activeExperiments) Icons.Default.Info
                    else Icons.Default.MoreVert,
                    "Connection details"
                ) { screen = Screen.Connection }
                Box {
                    NavigationIcon(Icons.Default.Settings, "Options") { options = true }
                    DropdownMenu(
                        expanded = options,
                        onDismissRequest = { options = false },
                        properties = androidx.compose.ui.window.PopupProperties(focusable = false)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Settings") }, leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { options = false; screen = Screen.Settings }
                        )
                        DropdownMenuItem(
                            text = { Text("Downloads") }, leadingIcon = { Icon(Icons.Default.Download, null) },
                            onClick = { options = false; screen = Screen.Downloads }
                        )
                        DropdownMenuItem(
                            text = { Text("History") }, leadingIcon = { Icon(Icons.Default.History, null) },
                            onClick = { options = false; screen = Screen.History }
                        )
                        if (ServerExperiment.INCOGNITO_MODE.key in activeExperiments) {
                            DropdownMenuItem(
                                text = { Text("Incognito") }, leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
                                trailingIcon = { Checkbox(tabs.incognito, onCheckedChange = null) },
                                onClick = { options = false; tabs.toggleIncognito() }
                            )
                        }
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
        val isSettingsScreen = screen in setOf(
            Screen.Settings, Screen.Theme, Screen.HttpVersions, Screen.TlsCiphers, Screen.Cookies
        )
        Box(
            Modifier.fillMaxSize().padding(padding).pointerInput(addressFocused, imeVisible) {
                if (!addressFocused || imeVisible) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (event.changes.any { !it.isConsumed && it.changedToDownIgnoreConsumed() }) {
                            focus.clearFocus(force = true)
                        }
                    }
                }
            }
        ) {
            Box(Modifier.size(1.dp).focusRequester(initialContentFocus).focusable())
            if (media != null && !isSettingsScreen) {
                key(browser.id, browser.mediaReload) {
                    VideoPlayer(
                        media, browser.desktop, tabs.executor, lifecycleOwner,
                        supportsPip, inPip, nativeFullscreen, enterPip,
                        { nativeFullscreen = !nativeFullscreen }, onNativePlayer
                    )
                }
            } else if (!browser.nativeHome && !isSettingsScreen) {
                key(browser.session) {
                    AndroidView(
                        factory = { context ->
                            val attachedSession = browser.session
                            GeckoView(context).apply {
                                setSession(attachedSession)
                                doOnAttach { post { browser.onWebViewAttached(attachedSession, this) } }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = {
                            browser.detachWebView(it)
                            it.releaseSession()
                        },
                        update = {
                            val showSource = screen == Screen.Browser && browser.url.isNotEmpty() && browser.failure == null
                            it.visibility = if (showSource) View.VISIBLE else View.INVISIBLE
                        }
                    )
                }
            }
            when {
                media != null && screen == Screen.Browser -> Unit
                screen == Screen.Theme -> Column {
                    ThemeRow("Dark Theme", tabs.dark) { tabs.setTheme(true) }
                    ThemeRow("Light Theme", !tabs.dark) { tabs.setTheme(false) }
                }
                screen == Screen.TlsCiphers -> CipherSettings(tabs)
                screen == Screen.Cookies -> CookieSettings(tabs)
                screen == Screen.HttpVersions -> Column {
                    ListItem(
                        headlineContent = { Text("HTTP/1.1") },
                        supportingContent = { Text("Always available") },
                        trailingContent = { Checkbox(checked = true, onCheckedChange = null, enabled = false) }
                    )
                    ListItem(
                        headlineContent = { Text("HTTP/2") },
                        trailingContent = { Checkbox(tabs.http2Enabled, onCheckedChange = null) },
                        modifier = Modifier.toggleable(tabs.http2Enabled, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = tabs::setHttp2)
                    )
                    ListItem(
                        headlineContent = { Text("HTTP/3") },
                        supportingContent = { Text("QUIC") },
                        trailingContent = { Checkbox(tabs.http3Enabled, onCheckedChange = null) },
                        modifier = Modifier.toggleable(tabs.http3Enabled, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = tabs::setHttp3)
                    )
                    if (tabs.httpChangePending) {
                        Text("Restart BrowserEngine to apply this change.", Modifier.padding(16.dp))
                    }
                }
                screen == Screen.Downloads -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DownloadList(
                        tabs.downloads,
                        enhanced = ServerExperiment.ENHANCED_DOWNLOADS.key in activeExperiments
                    )
                }
                screen == Screen.History -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HistoryList(tabs.history) { url ->
                        screen = Screen.Browser
                        browser.navigate(url)
                    }
                }
                screen == Screen.Settings -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    ListItem(
                        headlineContent = { Text("Theme") }, supportingContent = { Text(if (tabs.dark) "Dark Theme" else "Light Theme") },
                        leadingContent = { Icon(Icons.Default.Palette, null) },
                        modifier = Modifier.clickable { screen = Screen.Theme }
                    )
                    ListItem(
                        headlineContent = { Text("HTTP versions") },
                        supportingContent = {
                            Text(buildList {
                                add("HTTP/1.1")
                                if (tabs.http2Enabled) add("HTTP/2")
                                if (tabs.http3Enabled) add("HTTP/3")
                            }.joinToString(", "))
                        },
                        leadingContent = { Icon(Icons.Default.Language, null) },
                        modifier = Modifier.clickable { screen = Screen.HttpVersions }
                    )
                    ListItem(
                        headlineContent = { Text("TLS cipher suites") },
                        leadingContent = { Icon(Icons.Default.Lock, null) },
                        modifier = Modifier.clickable { screen = Screen.TlsCiphers }
                    )
                    ListItem(
                        headlineContent = { Text("Cookies") },
                        supportingContent = { Text(if (tabs.cookiesAllowed) "Enabled" else "Blocked") },
                        leadingContent = { Icon(Icons.Default.Cookie, null) },
                        modifier = Modifier.clickable { screen = Screen.Cookies }
                    )
                    ListItem(
                        headlineContent = { Text("Disable website cache") },
                        supportingContent = {
                            Text(if (tabs.cacheChangePending) "Restart BrowserEngine to apply this change."
                                else "Control GeckoView’s disk and memory cache for websites.")
                        },
                        trailingContent = { Switch(tabs.websiteCacheDisabled, tabs::updateWebsiteCache) }
                    )
                    ServerExperimentSettings(activeExperiments)
                    val geckoVersion = remember {
                        GeckoSession.getDefaultUserAgent().substringAfter("rv:", "").substringBefore(")")
                            .ifBlank { BuildConfig.GECKOVIEW_MAJOR }
                    }
                    ListItem(headlineContent = { Text("About BrowserEngine") },
                        supportingContent = { Text("Version ${BuildConfig.VERSION_NAME} · GeckoView $geckoVersion") })
                }
                screen == Screen.Connection -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ConnectionScreen(
                        browser,
                        showCertificate = if (ServerExperiment.CERTIFICATE_DETAILS.key in activeExperiments &&
                            browser.displayUrl.toHttpUriOrNull()?.scheme.equals("https", true)
                        ) ({ screen = Screen.Certificate }) else null,
                        reload = { screen = Screen.Browser; browser.reload(bypassCache = true) }
                    )
                }
                screen == Screen.Certificate -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CertificateDetails(browser.certificate, browser.certificateRejected)
                }
                else -> {
                    when {
                        browser.nativeHome -> NativeHomeScreen(
                            tabs.nativeHomeSections,
                            tabs.nativeHomeWeather,
                            tabs.savedWeatherLocation,
                            tabs.weatherUpdating,
                            tabs.weatherError,
                            ServerExperiment.WEATHER_LOADING_PLACEHOLDER.key in activeExperiments,
                            tabs.homeBackoffSeconds,
                            browser.homeLoading,
                            tabs.incognito,
                            tabs.executor,
                            tabs.homeClient,
                            search = { query ->
                                focus.clearFocus()
                                val searchUrl = "https://www.google.com/search?q=${Uri.encode(query)}"
                                tabs.history.recordSearch(query, searchUrl, tabs.incognito)
                                browser.navigate(searchUrl)
                            },
                            navigate = browser::navigate,
                            searchLocations = tabs::searchWeatherLocations,
                            updateWeather = tabs::updateWeather,
                            refreshWeather = tabs::refreshWeather,
                            retry = { tabs.openHome(browser) }
                        )
                        browser.homeLoading -> Column(
                            Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("Loading")
                        }
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
                                    if (tabs.incognito) {
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.VisibilityOff,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                "Incognito Mode",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
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
                    AnimatedVisibility(
                        visible = !inPip && !webFullscreen && !browser.homeLoading &&
                            browser.url.isNotEmpty() && browser.progress < 100,
                        enter = fadeIn(), exit = fadeOut()
                    ) {
                        LinearProgressIndicator(progress = { browser.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    val typed = address.text.toString().trim()
                    if (addressFocused && typed.isNotEmpty() && typed != displayedUrl) {
                        val matches = tabs.history.entries.filter {
                            it.title.contains(typed, ignoreCase = true) || it.url.contains(typed, ignoreCase = true)
                        }.take(6)
                        if (matches.isNotEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                tonalElevation = 6.dp,
                                shadowElevation = 6.dp
                            ) {
                                Column {
                                    matches.forEach { item ->
                                        val favicon = if (item.type == HistoryType.SITE) tabs.history.iconFor(item.host) else null
                                        ListItem(
                                            headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            supportingContent = { Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            leadingContent = {
                                                when {
                                                    item.type == HistoryType.SEARCH -> Icon(Icons.Default.Search, contentDescription = null)
                                                    favicon != null -> Image(
                                                        bitmap = favicon,
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.size(24.dp).clip(MaterialTheme.shapes.extraSmall)
                                                    )
                                                    else -> Icon(Icons.Default.Public, contentDescription = null)
                                                }
                                            },
                                            modifier = Modifier.clickable {
                                                focus.clearFocus()
                                                browser.navigate(item.url)
                                            }
                                        )
                                    }
                                }
                            }
                        }
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
            dismissButton = {
                TextButton(onClick = {
                    browser.mediaChoice = null
                    tabs.downloads.fetch(tabs.executor, target.url, browser.desktop, tabs.incognito)
                }) { Text("Download") }
            }
        )
    }
    if (!inPip) browser.downloadChoice?.let { target ->
        if (ServerExperiment.CONTEXT_MENU_ACTIONS.key in activeExperiments) {
            val context = LocalContext.current
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            @OptIn(ExperimentalMaterial3Api::class)
            ModalBottomSheet(
                onDismissRequest = { browser.downloadChoice = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                val previewUrl = target.imageUrl ?: target.linkUrl.orEmpty()
                Column(
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = previewUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    HorizontalDivider(Modifier.padding(bottom = 4.dp))
                    @Composable
                    fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
                        ListItem(
                            headlineContent = { Text(label) },
                            leadingContent = { Icon(icon, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
                        )
                    }
                    target.imageUrl?.let { img ->
                        ActionRow(Icons.Default.Image, "Open image") {
                            browser.downloadChoice = null
                            browser.navigate(img)
                        }
                        ActionRow(Icons.Default.OpenInNew, "Open image in new tab") {
                            browser.downloadChoice = null
                            tabs.newTab(img, parentTabId = browser.id)
                        }
                        ActionRow(Icons.Default.Download, "Download image") {
                            browser.downloadChoice = null
                            tabs.downloads.fetch(tabs.executor, img, browser.desktop, tabs.incognito)
                        }
                        ActionRow(Icons.Default.ContentCopy, "Copy image") {
                            browser.downloadChoice = null
                            uiScope.launch {
                                val uri = withContext(Dispatchers.IO) {
                                    copyImageToCacheUri(context, tabs.executor, img)
                                }
                                if (uri != null) {
                                    clipboard?.setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, "Image", uri))
                                    android.widget.Toast.makeText(context, "Image copied", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "Could not copy image", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        ActionRow(Icons.Default.Link, "Copy image link") {
                            browser.downloadChoice = null
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Image URL", img))
                            android.widget.Toast.makeText(context, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (target.imageUrl != null && target.linkUrl != null) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                    target.linkUrl?.let { link ->
                        ActionRow(Icons.Default.OpenInNew, "Open link in new tab") {
                            browser.downloadChoice = null
                            tabs.newTab(link, parentTabId = browser.id)
                        }
                        ActionRow(Icons.Default.Download, "Download link") {
                            browser.downloadChoice = null
                            tabs.downloads.fetch(tabs.executor, link, browser.desktop, tabs.incognito)
                        }
                        ActionRow(Icons.Default.Link, "Copy link") {
                            browser.downloadChoice = null
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("URL", link))
                            android.widget.Toast.makeText(context, "Link copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else {
            val fallbackUrl = target.linkUrl ?: target.imageUrl ?: return@let
            AlertDialog(
                onDismissRequest = { browser.downloadChoice = null },
                title = { Text("Download file?") },
                text = { Text(fallbackUrl, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                confirmButton = {
                    TextButton(onClick = {
                        browser.downloadChoice = null
                        tabs.downloads.fetch(tabs.executor, fallbackUrl, browser.desktop, tabs.incognito)
                    }) { Text("Download") }
                },
                dismissButton = { TextButton(onClick = { browser.downloadChoice = null }) { Text("Cancel") } }
            )
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
private fun TabList(
    tabs: BrowserTabs,
    experiments: Set<String>,
    open: (BrowserSession) -> Unit
) {
    val compact = ServerExperiment.COMPACT_TAB_CARDS.key in experiments
    val showThumbnails = ServerExperiment.TAB_THUMBNAILS.key in experiments
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = if (compact && !showThumbnails) GridCells.Fixed(1) else GridCells.Adaptive(156.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tabs.tabs, key = { it.id }) { tab ->
                    val selected = tab === tabs.current
                    val host = tab.displayUrl.toHttpUriOrNull()?.host.orEmpty()
                    val title = tab.title.takeIf { tab.media == null }.orEmpty().ifBlank { host.ifBlank { "New tab" } }
                    val thumb = if (showThumbnails && !tab.nativeHome) tab.thumbnail else null
                    OutlinedCard(
                        onClick = { open(tab) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(200), placementSpec = tween(240), fadeOutSpec = tween(200)
                        ).fillMaxWidth().heightIn(
                            min = if (compact && !showThumbnails) 52.dp else if (showThumbnails) 200.dp else 112.dp
                        ),
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = if (selected && !showThumbnails) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 10.dp, top = 2.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showThumbnails) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp).padding(end = 2.dp),
                                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                if (compact && !showThumbnails && host.isNotEmpty() && host != title) {
                                    Text(host, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            NavigationIcon(Icons.Default.Close, "Close $title") { tabs.closeTab(tab) }
                        }
                        if (showThumbnails) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.85f)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = title,
                                        contentScale = ContentScale.Crop,
                                        alignment = Alignment.TopCenter,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Icon(
                                            if (tab.nativeHome) Icons.Default.Home else Icons.Default.Language,
                                            contentDescription = null,
                                            modifier = Modifier.size(28.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            if (tab.nativeHome) "Home" else host.ifEmpty { "New tab" },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else if (!compact && host.isNotEmpty()) {
                            Text(
                                host, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
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
}

@Composable
private fun CipherSettings(tabs: BrowserTabs) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text("Cipher suites offered in ClientHello") },
                supportingContent = {
                    Text(if (tabs.cipherChangePending) "Restart BrowserEngine to apply your selection."
                    else "Select the cipher suites to offer on new TLS connections over TCP. HTTP/3 uses GeckoView’s QUIC cipher list.")
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
private fun NavigationIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    action: () -> Unit
) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(42.dp)) {
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

@Composable
private fun CertificateDetails(certificate: java.security.cert.X509Certificate?, rejected: Boolean) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (certificate == null) {
            Text("No certificate details are available for this page.")
            return@Column
        }
        if (rejected) {
            Text("The browser rejected this certificate.", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
        }
        val names = remember(certificate) { android.net.http.SslCertificate(certificate) }
        val formatter = remember { java.text.DateFormat.getDateTimeInstance() }
        Text("Issued to", style = MaterialTheme.typography.titleMedium)
        CertificateName(names.issuedTo)
        val alternativeNames = remember(certificate) {
            runCatching {
                certificate.subjectAlternativeNames.orEmpty().mapNotNull { entry ->
                    if (entry.firstOrNull() in listOf(2, 7)) entry.getOrNull(1) as? String else null
                }.distinct()
            }.getOrDefault(emptyList())
        }
        if (alternativeNames.isNotEmpty()) {
            ListItem(
                headlineContent = { Text("Subject Alternative Names (SAN)") },
                supportingContent = {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(alternativeNames.joinToString("\n"))
                    }
                }
            )
        }
        Text("Issued by", style = MaterialTheme.typography.titleMedium)
        CertificateName(names.issuedBy)
        Text("Validity period", style = MaterialTheme.typography.titleMedium)
        ListItem(headlineContent = { Text("Valid from") }, supportingContent = { Text(formatter.format(certificate.notBefore)) })
        ListItem(headlineContent = { Text("Valid until") }, supportingContent = { Text(formatter.format(certificate.notAfter)) })
        ListItem(headlineContent = { Text("Serial number") }, supportingContent = { Text(certificate.serialNumber.toString(16).uppercase()) })
    }
}

@Composable
private fun CertificateName(name: android.net.http.SslCertificate.DName) {
    val unavailable = "Not specified"
    ListItem(headlineContent = { Text("Common Name (CN)") }, supportingContent = { Text(name.cName.orEmpty().ifBlank { unavailable }) })
    ListItem(headlineContent = { Text("Organization (O)") }, supportingContent = { Text(name.oName.orEmpty().ifBlank { unavailable }) })
    ListItem(headlineContent = { Text("Organizational Unit (OU)") }, supportingContent = { Text(name.uName.orEmpty().ifBlank { unavailable }) })
}

@Composable private fun ConnectionScreen(
    browser: BrowserSession,
    showCertificate: (() -> Unit)?,
    reload: () -> Unit
) {
    val url = browser.displayUrl.toHttpUriOrNull()
    val info = browser.security?.takeIf { it.optString("url").sameOriginAs(browser.displayUrl) }
    fun field(name: String) = info?.optString(name).orEmpty()
    fun show(value: String) = value.ifBlank { "Unavailable" }
    val httpDisplay = field("http").ifBlank {
        when (field("navProtocol").lowercase()) {
            "h3" -> "HTTP/3"
            "h2" -> "HTTP/2"
            "http/1.1" -> "HTTP/1.1"
            else -> ""
        }
    }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (url == null) {
            Text("Open a website to see its connection details.")
            return@Column
        }
        Text(url.host.orEmpty(), style = MaterialTheme.typography.titleLarge)
        if (showCertificate != null) {
            ListItem(
                headlineContent = { Text("Certificate Details") },
                leadingContent = { Icon(Icons.Default.Lock, null) },
                modifier = Modifier.clickable(onClick = showCertificate)
            )
        }
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
        ListItem(headlineContent = { Text("HTTP version") }, supportingContent = { Text(show(httpVersionLabel(httpDisplay))) })
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

@Composable
private fun ServerExperimentSettings(active: Set<String>) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    ListItem(
        headlineContent = { Text("Server Experiments") },
        supportingContent = {
            Column {
                Text("Explore New Functions without updating the app.")
                status?.let { Text(it) }
            }
        },
        leadingContent = { Icon(Icons.Default.Science, null) }
    )
    TextButton(
        enabled = !checking,
        modifier = Modifier.padding(horizontal = 8.dp),
        onClick = {
            checking = true
            status = "Checking for experiments…"
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    runCatching { ServerExperiments.fetch(context) }.isSuccess
                }
                status = if (!success) "Couldn't check for experiments." else
                    if (ServerExperiments.pending(context) == active) "Experiments are up to date."
                    else "Changes are ready for the next launch."
                checking = false
            }
        }
    ) { Text("Try to get new Experiments now") }
}

@Composable
private fun DownloadList(downloads: BrowserDownloads, enhanced: Boolean) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf<BrowserDownload?>(null) }
    var renameInput by remember { mutableStateOf("") }

    if (downloads.entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No downloads yet") }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(downloads.entries, key = { it.id }) { download ->
            val subtitle = if (enhanced && download.state == DownloadState.DOWNLOADING) {
                val speed = BrowserDownloads.formatSpeed(download.speedBytesPerSec)
                val eta = BrowserDownloads.formatEta(download.etaSeconds)
                "$speed · $eta"
            } else {
                download.state.label
            }
            Column {
                ListItem(
                    headlineContent = { Text(download.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(subtitle) },
                    leadingContent = { Icon(Icons.Default.InsertDriveFile, null) },
                    trailingContent = when {
                        download.state == DownloadState.DOWNLOADING -> {
                            {
                                IconButton(onClick = { downloads.cancelAndDelete(download) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel download")
                                }
                            }
                        }
                        enhanced -> {
                            {
                                Row {
                                    IconButton(onClick = {
                                        renaming = download
                                        renameInput = download.name
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Rename")
                                    }
                                    IconButton(onClick = { downloads.delete(download) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                        else -> null
                    },
                    modifier = Modifier.clickable(enabled = download.state == DownloadState.COMPLETE) {
                        downloads.open(context, download)
                    }
                )
                if (enhanced && download.state == DownloadState.DOWNLOADING) {
                    if (download.totalBytes > 0) {
                        val ratio = (download.bytesDownloaded.toFloat() / download.totalBytes.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename file") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameInput.isNotBlank(),
                    onClick = {
                        downloads.rename(target, renameInput)
                        renaming = null
                    }
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HistoryList(history: BrowserHistory, onOpen: (String) -> Unit) {
    if (history.entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No history yet") }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = history::clear) { Text("Clear history") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(history.entries, key = { it.id }) { item ->
                val favicon = if (item.type == HistoryType.SITE) history.iconFor(item.host) else null
                ListItem(
                    headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        when {
                            item.type == HistoryType.SEARCH -> Icon(Icons.Default.Search, contentDescription = null)
                            favicon != null -> Image(
                                bitmap = favicon,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(24.dp).clip(MaterialTheme.shapes.extraSmall)
                            )
                            else -> Icon(Icons.Default.Public, contentDescription = null)
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { history.remove(item.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove")
                        }
                    },
                    modifier = Modifier.clickable { onOpen(item.url) }
                )
            }
        }
    }
}

@Composable
private fun CookieSettings(tabs: BrowserTabs) {
    var confirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        ListItem(
            headlineContent = { Text("Allow cookies") },
            supportingContent = { Text("Allow websites to save and read cookies.") },
            trailingContent = { Switch(tabs.cookiesAllowed, tabs::updateCookiePolicy) }
        )
        ListItem(
            headlineContent = { Text("Third-party cookies") },
            supportingContent = { Text("Allow cookies from embedded content on websites.") },
            trailingContent = {
                Switch(
                    checked = tabs.cookiesAllowed && tabs.thirdPartyCookiesAllowed,
                    onCheckedChange = tabs::updateThirdPartyCookiePolicy,
                    enabled = tabs.cookiesAllowed
                )
            }
        )
        TextButton(
            onClick = { confirm = true },
            enabled = !clearing,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) { Text("Clear cookies") }
        result?.let { Text(it, Modifier.padding(16.dp)) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear all cookies?") },
            text = { Text("This can sign you out of websites.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    clearing = true
                    result = null
                    tabs.clearCookies { success ->
                        clearing = false
                        result = if (success) "Cookies cleared." else "Couldn't clear cookies."
                    }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}

private fun copyImageToCacheUri(context: Context, executor: GeckoWebExecutor, url: String): Uri? = runCatching {
    val request = org.mozilla.geckoview.WebRequest.Builder(url).header("User-Agent", BrowserNetwork.agent(false)).build()
    val pending = executor.fetch(request)
    val response = pending.poll(10_000) ?: return@runCatching null
    if (response.statusCode !in 200..299 || response.body == null) {
        response.body?.let { runCatching { it.close() } }
        return@runCatching null
    }
    val bytes = response.body!!.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > 5 * 1024 * 1024) return@use null
            out.write(buf, 0, n)
        }
        out.toByteArray()
    } ?: return@runCatching null
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
    val dir = context.cacheDir.resolve("clipboard").apply { mkdirs() }
    val file = dir.resolve("copied_image.png")
    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.downloads", file)
}.getOrNull()

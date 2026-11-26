package com.x1colegal.browserengine

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal const val HOME_URL = "https://home.browserengine.x1colegal.com/"
private const val API_BASE = "https://api.browserengine.x1colegal.com"
private val CP1252 = Charset.forName("windows-1252")

internal class ApiStatus(val code: Int) : IOException("BrowserEngine API returned HTTP $code")

private fun apiExecutor(context: Context): GeckoWebExecutor {
    val runtimeTask = FutureTask { GeckoWebExecutor(BrowserRuntime.get(context.applicationContext)) }
    if (Looper.myLooper() == Looper.getMainLooper()) runtimeTask.run()
    else Handler(Looper.getMainLooper()).post(runtimeTask)
    return runtimeTask.get(12, TimeUnit.SECONDS)
}

private fun apiRequest(
    context: Context,
    executor: GeckoWebExecutor,
    path: String,
    body: JSONObject? = null,
    headers: Map<String, String> = emptyMap(),
    onBackoff: ((Int) -> Unit)? = null
): JSONObject {
    var backoffRounds = 0
    var violationRetries = 0
    while (true) {
        val request = WebRequest.Builder(API_BASE + path)
            .header("Accept", "application/json")
            .header("ClientVersion", BuildConfig.VERSION_NAME)
            .header("User-Agent", BrowserNetwork.agent(false))
        headers.forEach { (key, value) -> request.header(key, value) }
        if (body != null) {
            val payload = JSONObject(body.toString()).put("clientVersion", BuildConfig.VERSION_NAME)
            val bytes = payload.toString(2).toByteArray(CP1252)
            request.method("POST")
                .header("Content-Type", "application/json; charset=windows-1252")
                .body(ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() })
        }
        val pending = executor.fetch(request.build(), GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS)
        val response = try {
            pending.poll(15_000) ?: throw IOException("No BrowserEngine API response")
        } catch (error: TimeoutException) {
            pending.withHandler(Handler(Looper.getMainLooper()))
                .accept({ it?.body?.let { input -> runCatching { input.close() } } }, {})
            throw IOException("BrowserEngine API timed out", error)
        }
        val input = response.body
        if (response.statusCode == 471 && violationRetries < 8) {
            input?.close()
            violationRetries++
            try {
                onBackoff?.invoke(1)
                Thread.sleep(1000L)
            } finally {
                onBackoff?.invoke(0)
            }
            continue
        }
        if (response.statusCode !in 200..299) {
            input?.close()
            if (response.statusCode == 469) ServerExperiments.recordVersionResponse(context, 469)
            throw ApiStatus(response.statusCode)
        }
        val json = (input ?: throw IOException("Missing BrowserEngine API response body")).use {
            val result = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val count = it.read(chunk)
                if (count < 0) break
                if (result.size() + count > 512 * 1024) throw IOException("API response too large")
                result.write(chunk, 0, count)
            }
            JSONObject(String(result.toByteArray(), CP1252)).also { parsed ->
                ServerExperiments.recordVersionResponse(context, response.statusCode, parsed)
            }
        }
        val backoff = json.optInt("backoff", 0)
        if (backoff > 0 && backoffRounds < 4) {
            backoffRounds++
            try {
                for (remaining in backoff downTo 1) {
                    onBackoff?.invoke(remaining)
                    Thread.sleep(1000L)
                }
            } finally {
                onBackoff?.invoke(0)
            }
            continue
        }
        return json
    }
}

internal object HomeImageLoader {
    private val cache = LruCache<String, ImageBitmap>(32)

    fun cached(url: String): ImageBitmap? = cache.get(url)

    fun fetch(executor: GeckoWebExecutor, url: String, sessionClient: HomeSessionClient? = null): ImageBitmap? {
        cache.get(url)?.let { return it }
        if (url.toHttpUriOrNull() == null) return null
        return runCatching {
            val response = if (url.startsWith("$API_BASE/api/img/") && sessionClient != null) {
                sessionClient.fetchServerImageResponse(url)
            } else {
                val request = WebRequest.Builder(url).header("User-Agent", BrowserNetwork.agent(false)).build()
                val pending = executor.fetch(request)
                try {
                    pending.poll(10_000)
                } catch (_: TimeoutException) {
                    pending.withHandler(Handler(Looper.getMainLooper()))
                        .accept({ it?.body?.let { s -> runCatching { s.close() } } }, {})
                    null
                }
            }
            if (response == null || response.statusCode !in 200..299 || response.body == null) {
                response?.body?.let { runCatching { it.close() } }
                return@runCatching null
            }
            val bytes = response.body!!.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > 1024 * 1024) return@use null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()?.also {
                cache.put(url, it)
            }
        }.getOrNull()
    }
}

internal data class HomeCredentials(val id: String, val token: String, val expiry: Long)
internal data class WeatherLocationResult(val name: String, val display: String, val coords: String)

internal class HomeSessionClient(context: Context, private val executor: GeckoWebExecutor) {
    private val appContext = context.applicationContext
    private val preferences = AppPrefs.get(appContext, "home_session")

    fun savedWeatherLocation(): String = preferences.getString("weather_location", "").orEmpty()
    fun savedWeatherLabel(): String = preferences.getString("weather_label", "").orEmpty()

    private fun cachedSession(): HomeCredentials? {
        val cachedId = preferences.getString("id", null)
        val cachedToken = preferences.getString("token", null)
        val cachedExpiry = preferences.getLong("expires_at_epoch", 0)
        if (cachedId != null && cachedToken != null && cachedExpiry > System.currentTimeMillis() / 1000) {
            return HomeCredentials(cachedId, cachedToken, cachedExpiry)
        }
        return null
    }

    @Synchronized
    private fun getOrCreate(onBackoff: ((Int) -> Unit)? = null): HomeCredentials {
        cachedSession()?.let { return it }
        val created = apiRequest(appContext, executor, "/api/create/session", onBackoff = onBackoff)
        val id = created.getString("id")
        val token = apiRequest(
            appContext,
            executor,
            "/api/generate/token",
            headers = mapOf("X-Home-Session" to id),
            onBackoff = onBackoff
        )
        val bearer = token.getString("token")
        val expires = token.getLong("expires_at_epoch")
        if (!id.matches(Regex("[a-f0-9]{32}")) || !bearer.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            throw IOException("Invalid HomeSession response")
        }
        preferences.edit()
            .putString("id", id)
            .putString("token", bearer)
            .putString("expires_at", token.getString("expires_at"))
            .putLong("expires_at_epoch", expires)
            .commit()
        return HomeCredentials(id, bearer, expires)
    }

    suspend fun loadNativeHome(onBackoff: (Int) -> Unit = {}): HomeFeedData {
        fun fetchWithSessionRefresh(): Pair<HomeCredentials, JSONObject> {
            var session = getOrCreate(onBackoff)
            val feed = try {
                fetchHomeFeed(session, onBackoff)
            } catch (error: ApiStatus) {
                if (error.code != 467 && error.code != 401) throw error
                clearSessionOnly()
                session = getOrCreate(onBackoff)
                fetchHomeFeed(session, onBackoff)
            }
            return session to feed
        }

        var (_, feed) = try {
            fetchWithSessionRefresh()
        } catch (error: IOException) {
            if (error is ApiStatus) throw error
            fetchWithSessionRefresh()
        }
        val savedLocation = savedWeatherLocation()
        if (feed.optJSONObject("weather") == null && savedLocation.isNotBlank()) {
            runCatching {
                val active = getOrCreate(onBackoff)
                postWeather(active, savedLocation, savedWeatherLabel().takeIf { it.isNotBlank() })
                feed = fetchHomeFeed(active, onBackoff)
            }
        }
        return feed.homeFeedData(ServerExperiments.pending(appContext).contains(ServerExperiment.HOME_TABS.key))
    }

    @Synchronized
    fun searchWeatherLocations(query: String): List<WeatherLocationResult> {
        val clean = query.trim()
        if (clean.length < 2) return emptyList()
        val session = getOrCreate()
        val body = JSONObject().put("id", session.id).put("token", session.token).put("query", clean)
        val response = try {
            apiRequest(appContext, executor, "/api/weather/search", body)
        } catch (error: ApiStatus) {
            if (error.code != 467 && error.code != 401) throw error
            clearSessionOnly()
            val fresh = getOrCreate()
            apiRequest(appContext, executor, "/api/weather/search", JSONObject().put("id", fresh.id).put("token", fresh.token).put("query", clean))
        }
        val array = response.optJSONArray("results") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val display = obj.optString("display").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val coords = obj.optString("coords").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            WeatherLocationResult(obj.optString("name", display), display, coords)
        }
    }

    @Synchronized
    fun updateWeather(location: String, label: String? = null): WeatherInfo {
        val clean = location.trim()
        if (clean.isEmpty()) throw IOException("Location required")
        val cleanLabel = label?.trim()?.takeIf { it.isNotEmpty() }
        val session = getOrCreate()
        val response = try {
            postWeather(session, clean, cleanLabel)
        } catch (error: ApiStatus) {
            if (error.code != 467 && error.code != 401) throw error
            clearSessionOnly()
            postWeather(getOrCreate(), clean, cleanLabel)
        }
        preferences.edit()
            .putString("weather_location", clean)
            .putString("weather_label", cleanLabel ?: clean)
            .apply()
        return response.getJSONObject("weather").toWeatherInfo()
            ?: throw IOException("Invalid weather response")
    }

    private fun clearSessionOnly() {
        preferences.edit()
            .remove("id")
            .remove("token")
            .remove("expires_at")
            .remove("expires_at_epoch")
            .commit()
    }

    fun fetchServerImageResponse(url: String): org.mozilla.geckoview.WebResponse? {
        fun postImage(session: HomeCredentials): org.mozilla.geckoview.WebResponse? {
            val bytes = JSONObject()
                .put("id", session.id)
                .put("token", session.token)
                .put("clientVersion", BuildConfig.VERSION_NAME)
                .toString(2)
                .toByteArray(CP1252)
            val request = WebRequest.Builder(url)
                .method("POST")
                .header("Accept", "image/*")
                .header("ClientVersion", BuildConfig.VERSION_NAME)
                .header("User-Agent", BrowserNetwork.agent(false))
                .header("Content-Type", "application/json; charset=windows-1252")
                .body(ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() })
                .build()
            val pending = executor.fetch(request, GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS)
            return try {
                pending.poll(10_000)
            } catch (_: TimeoutException) {
                pending.withHandler(Handler(Looper.getMainLooper()))
                    .accept({ it?.body?.let { s -> runCatching { s.close() } } }, {})
                null
            }
        }
        val first = postImage(getOrCreate()) ?: return null
        if (first.statusCode == 467 || first.statusCode == 401) {
            first.body?.let { runCatching { it.close() } }
            clearSessionOnly()
            return postImage(getOrCreate())
        }
        return first
    }

    private fun fetchHomeFeed(session: HomeCredentials, onBackoff: ((Int) -> Unit)? = null) = apiRequest(
        appContext,
        executor,
        "/api/home",
        JSONObject().put("id", session.id).put("token", session.token),
        onBackoff = onBackoff
    )

    private fun postWeather(session: HomeCredentials, location: String, label: String?) = apiRequest(
        appContext,
        executor,
        "/api/weather",
        JSONObject().put("id", session.id).put("token", session.token).put("location", location).apply {
            if (label != null) put("label", label)
        }
    )
}

internal enum class ServerExperiment(val key: String) {
    NATIVE_HOME("native_home"),
    CERTIFICATE_DETAILS("certificate_details"),
    CERTIFICATE_WARNING("certificate_warning"),
    COMPACT_TAB_CARDS("compact_tab_cards"),
    INCOGNITO_MODE("incognito_mode"),
    ENHANCED_DOWNLOADS("enhanced_downloads"),
    CONNECTION_INFO_ICON("connection_info_icon"),
    WEATHER_LOADING_PLACEHOLDER("weather_loading_placeholder"),
    TAB_THUMBNAILS("tab_thumbnails"),
    CONTEXT_MENU_ACTIONS("context_menu_actions"),
    HOME_TABS("home_tabs")
}

internal data class HomeTabConfig(val uiName: String, val internalName: String)

internal object ServerExperiments {
    const val FLAGS = "pending_flags"
    const val PROMPTED = "prompted"
    const val LAST_CHECK = "last_check"
    const val LAST_VERSION_CODE = "last_version_code"
    const val DISCONTINUED_VERSION = "discontinued_version"
    const val DEPRECATED_VERSION = "deprecated_version"
    private const val SUPPRESS_DEPRECATED_FOREVER = "suppress_deprecated_forever"
    private const val SUPPRESS_DEPRECATED_VERSION = "suppress_deprecated_version"
    private const val CHECK_INTERVAL_MINUTES = 30L
    private val fetchLock = Any()

    fun preferences(context: Context) = AppPrefs.get(context, "server_experiments")

    fun isDiscontinued(context: Context): Boolean =
        preferences(context).getString(DISCONTINUED_VERSION, null) == BuildConfig.VERSION_NAME

    fun isDeprecated(context: Context): Boolean =
        !isDiscontinued(context) &&
            preferences(context).getString(DEPRECATED_VERSION, null) == BuildConfig.VERSION_NAME

    fun shouldShowDeprecatedPrompt(context: Context): Boolean {
        if (!isDeprecated(context)) return false
        val settings = preferences(context)
        if (settings.getBoolean(SUPPRESS_DEPRECATED_FOREVER, false)) return false
        return settings.getString(SUPPRESS_DEPRECATED_VERSION, null) != BuildConfig.VERSION_NAME
    }

    fun suppressDeprecatedForCurrentVersion(context: Context) {
        preferences(context).edit().putString(SUPPRESS_DEPRECATED_VERSION, BuildConfig.VERSION_NAME).apply()
    }

    fun suppressDeprecatedForever(context: Context) {
        preferences(context).edit().putBoolean(SUPPRESS_DEPRECATED_FOREVER, true).apply()
    }

    internal fun recordVersionResponse(context: Context, statusCode: Int, json: JSONObject? = null) {
        val editor = preferences(context).edit()
        if (statusCode == 469) {
            editor.putString(DISCONTINUED_VERSION, BuildConfig.VERSION_NAME)
                .remove(DEPRECATED_VERSION)
                .apply()
            return
        }
        if (json != null) {
            editor.remove(DISCONTINUED_VERSION)
            if (json.optBoolean("version_deprecated", false)) {
                editor.putString(DEPRECATED_VERSION, BuildConfig.VERSION_NAME)
            } else {
                editor.remove(DEPRECATED_VERSION)
            }
            editor.apply()
        }
    }

    fun pending(context: Context): Set<String> =
        preferences(context).getStringSet(FLAGS, emptySet()).orEmpty().toSet()

    fun fetch(
        context: Context,
        onlyIfDue: Boolean = false,
        onBackoff: ((Int) -> Unit)? = null
    ) = synchronized(fetchLock) {
        val settings = preferences(context)
        val versionUpdated = settings.getInt(LAST_VERSION_CODE, -1) != BuildConfig.VERSION_CODE
        if (onlyIfDue && !versionUpdated) {
            val lastCheck = settings.getLong(LAST_CHECK, 0)
            val elapsed = System.currentTimeMillis() - lastCheck
            if (lastCheck > 0 && elapsed in 0 until TimeUnit.MINUTES.toMillis(CHECK_INTERVAL_MINUTES)) {
                return@synchronized
            }
        }
        val executor = apiExecutor(context)
        val token = settings.getString("permanent_token", null) ?: run {
            val generated = apiRequest(
                context,
                executor,
                "/server/experiments/gen/token",
                onBackoff = onBackoff
            ).getString("token")
            if (!generated.matches(Regex("[a-f0-9]{64}"))) throw IOException("Invalid experiment token")
            if (!settings.edit().putString("permanent_token", generated).commit()) {
                throw IOException("Could not save experiment token")
            }
            generated
        }
        val request = JSONObject().put("token", token)
        val response = apiRequest(context, executor, "/server/experiments/get", request, onBackoff = onBackoff)
        val flags = response.getJSONObject("experiments")
        val enabled = ServerExperiment.entries.filter { flags.opt(it.key) == true }.map { it.key }.toSet()
        settings.edit()
            .remove("home_tabs")
            .putStringSet(FLAGS, enabled)
            .putLong(LAST_CHECK, System.currentTimeMillis())
            .putInt(LAST_VERSION_CODE, BuildConfig.VERSION_CODE)
            .apply()
    }

    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<ServerExperimentsWorker>(CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "browserengine-server-experiments", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }
}

class ServerExperimentsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        ServerExperiments.fetch(applicationContext, onlyIfDue = true)
        Result.success()
    } catch (error: ApiStatus) {
        if (error.code >= 500) Result.retry() else Result.failure()
    } catch (_: Exception) {
        Result.retry()
    }
}

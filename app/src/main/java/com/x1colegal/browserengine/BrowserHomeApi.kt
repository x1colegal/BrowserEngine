package com.x1colegal.browserengine

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.nio.ByteBuffer
import java.util.concurrent.FutureTask
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

internal const val HOME_URL = "https://home.browserengine.x1colegal.com/"
private const val API_BASE = "https://api.browserengine.x1colegal.com"
private val CP1252 = Charset.forName("windows-1252")

private class ApiStatus(val code: Int) : IOException("BrowserEngine API returned HTTP $code")

private fun apiExecutor(context: Context): GeckoWebExecutor {
    val runtimeTask = FutureTask { GeckoWebExecutor(BrowserRuntime.get(context.applicationContext)) }
    if (Looper.myLooper() == Looper.getMainLooper()) runtimeTask.run()
    else Handler(Looper.getMainLooper()).post(runtimeTask)
    return runtimeTask.get(12, TimeUnit.SECONDS)
}

private fun apiRequest(
    executor: GeckoWebExecutor,
    path: String,
    body: JSONObject? = null,
    headers: Map<String, String> = emptyMap()
): JSONObject {
    val request = WebRequest.Builder(API_BASE + path)
        .header("Accept", "application/json")
        .header("ClientVersion", BuildConfig.VERSION_NAME)
        .header("User-Agent", BrowserNetwork.agent(false))
    headers.forEach { (key, value) -> request.header(key, value) }
    if (body != null) {
        val bytes = body.put("clientVersion", BuildConfig.VERSION_NAME).toString().toByteArray(CP1252)
        request.method("POST")
            .header("Content-Type", "application/json; charset=windows-1252")
            .body(ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() })
    }
    val pending = executor.fetch(request.build(), GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS)
    val response = try {
        pending.poll(15_000) ?: throw IOException("No BrowserEngine API response")
    } catch (error: java.util.concurrent.TimeoutException) {
        pending.withHandler(Handler(Looper.getMainLooper()))
            .accept({ it?.body?.let { input -> runCatching { input.close() } } }, {})
        throw IOException("BrowserEngine API timed out", error)
    }
    val input = response.body
    if (response.statusCode !in 200..299) {
        input?.close()
        throw ApiStatus(response.statusCode)
    }
    return (input ?: throw IOException("Missing BrowserEngine API response body")).use {
        val result = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val count = it.read(chunk)
            if (count < 0) break
            if (result.size() + count > 128 * 1024) throw IOException("API response too large")
            result.write(chunk, 0, count)
        }
        JSONObject(String(result.toByteArray(), CP1252))
    }
}

internal data class HomeCredentials(val id: String, val token: String, val expiry: Long)

internal class HomeSessionClient(context: Context, private val executor: GeckoWebExecutor) {
    private val preferences = context.getSharedPreferences("home_session", Context.MODE_PRIVATE)

    data class ThemeSync(val credentials: HomeCredentials, val updated: Boolean)

    @Synchronized
    private fun applyTheme(dark: Boolean): ThemeSync {
        val session = getOrCreate()
        if (preferences.contains("dark") && preferences.getBoolean("dark", dark) == dark) {
            return ThemeSync(session, updated = false)
        }
        return try {
            sendTheme(session, dark)
            ThemeSync(session, updated = true)
        } catch (error: ApiStatus) {
            if (error.code != 467 && error.code != 401) throw error
            preferences.edit().clear().commit()
            val fresh = getOrCreate()
            sendTheme(fresh, dark)
            ThemeSync(fresh, updated = true)
        }
    }

    private fun cachedSession(): HomeCredentials? {
        val cachedId = preferences.getString("id", null)
        val cachedToken = preferences.getString("token", null)
        val cachedExpiry = preferences.getLong("expires_at_epoch", 0)
        if (cachedId != null && cachedToken != null && cachedExpiry > System.currentTimeMillis() / 1000) {
            return HomeCredentials(cachedId, cachedToken, cachedExpiry)
        }
        return null
    }

    private fun getOrCreate(): HomeCredentials {
        cachedSession()?.let { return it }
        val created = apiRequest(executor, "/api/create/session")
        val id = created.getString("id")
        val token = apiRequest(executor, "/api/generate/token", headers = mapOf("X-Home-Session" to id))
        val bearer = token.getString("token")
        val expires = token.getLong("expires_at_epoch")
        if (!id.matches(Regex("[a-f0-9]{32}")) || !bearer.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            throw IOException("Invalid HomeSession response")
        }
        preferences.edit()
            .remove("dark")
            .putString("id", id)
            .putString("token", bearer)
            .putString("expires_at", token.getString("expires_at"))
            .putLong("expires_at_epoch", expires)
            .commit()
        return HomeCredentials(id, bearer, expires)
    }

    @Synchronized
    fun loadNativeHome(dark: Boolean): List<HomeSection> {
        val sync = applyTheme(dark)
        val feed = try {
            fetchHomeFeed(sync.credentials)
        } catch (error: ApiStatus) {
            if (error.code != 467 && error.code != 401) throw error
            preferences.edit().clear().commit()
            fetchHomeFeed(applyTheme(dark).credentials)
        }
        return feed.homeSections()
    }

    private fun fetchHomeFeed(session: HomeCredentials) = apiRequest(
        executor, "/api/home",
        headers = mapOf("X-Home-Session" to session.id, "Authorization" to "Bearer ${session.token}")
    )

    private fun sendTheme(session: HomeCredentials, dark: Boolean) {
        apiRequest(executor, "/api/session/theme", JSONObject().put("theme", if (dark) "dark" else "light"),
            headers = mapOf("X-Home-Session" to session.id, "Authorization" to "Bearer ${session.token}"))
        preferences.edit().putBoolean("dark", dark).apply()
    }
}

internal enum class ServerExperiment(val key: String) {
    NATIVE_HOME("native_home"),
    CERTIFICATE_DETAILS("certificate_details"),
    CERTIFICATE_WARNING("certificate_warning"),
    COMPACT_TAB_CARDS("compact_tab_cards")
}

internal object ServerExperiments {
    const val FLAGS = "pending_flags"
    const val PROMPTED = "prompted"
    const val LAST_CHECK = "last_check"
    private const val CHECK_INTERVAL_MINUTES = 30L
    private val fetchLock = Any()

    fun preferences(context: Context) = context.getSharedPreferences("server_experiments", Context.MODE_PRIVATE)

    fun pending(context: Context): Set<String> =
        preferences(context).getStringSet(FLAGS, emptySet()).orEmpty().toSet()

    fun fetch(context: Context, onlyIfDue: Boolean = false) = synchronized(fetchLock) {
        val settings = preferences(context)
        if (onlyIfDue) {
            val lastCheck = settings.getLong(LAST_CHECK, 0)
            val elapsed = System.currentTimeMillis() - lastCheck
            if (lastCheck > 0 && elapsed in 0 until TimeUnit.MINUTES.toMillis(CHECK_INTERVAL_MINUTES)) {
                return@synchronized
            }
        }
        val executor = apiExecutor(context)
        val token = settings.getString("permanent_token", null) ?: run {
            val generated = apiRequest(executor, "/srvr/experiments/gen/token").getString("token")
            if (!generated.matches(Regex("[a-f0-9]{64}"))) throw IOException("Invalid experiment token")
            if (!settings.edit().putString("permanent_token", generated).commit()) {
                throw IOException("Could not save experiment token")
            }
            generated
        }
        val request = JSONObject().put("token", token).put(
            "supportedExperiments", org.json.JSONArray(ServerExperiment.entries.map { it.key })
        )
        val flags = apiRequest(executor, "/srvr/experiments/get", request).getJSONObject("experiments")
        val enabled = ServerExperiment.entries.filter { flags.opt(it.key) == true }.map { it.key }.toSet()
        settings.edit().putStringSet(FLAGS, enabled)
            .putLong(LAST_CHECK, System.currentTimeMillis()).apply()
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
        if (error.code == 429 || error.code >= 500) Result.retry() else Result.failure()
    } catch (_: Exception) {
        Result.retry()
    }
}

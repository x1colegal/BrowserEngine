package com.x1colegal.browserengine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.mozilla.gecko.GeckoAppShell
import org.mozilla.gecko.GeckoNetworkManager
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal object GeckoNetworkChangeMonitor {
    private const val TAG = "BrowserNetMonitor"
    private const val DEBOUNCE_MS = 300L

    private var started = false
    private var lastSignature: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingReset: Runnable? = null

    fun start(context: Context, runtime: GeckoRuntime) {
        if (started) return
        started = true
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        lastSignature = currentSignature(cm)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleCheck(cm, runtime, "available")
            override fun onLost(network: Network) = scheduleCheck(cm, runtime, "lost")
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                scheduleCheck(cm, runtime, "capabilities")
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                scheduleCheck(cm, runtime, "link_properties")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { cm.registerDefaultNetworkCallback(callback) }
                .onFailure { Log.w(TAG, "registerDefaultNetworkCallback failed", it) }
        }

        val vpnRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(vpnRequest, callback) }
            .onFailure { Log.w(TAG, "registerNetworkCallback(VPN) failed", it) }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            val anyRequest = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            runCatching { cm.registerNetworkCallback(anyRequest, callback) }
            @Suppress("DEPRECATION")
            runCatching {
                app.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        scheduleCheck(cm, runtime, "connectivity_broadcast")
                    }
                }, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            }
        }
    }

    private fun currentSignature(cm: ConnectivityManager): String = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = cm.activeNetwork
            val activeCaps = active?.let(cm::getNetworkCapabilities)
            val activeLink = active?.let(cm::getLinkProperties)
            val vpnNetworks = cm.allNetworks.mapNotNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    val lp = cm.getLinkProperties(net)
                    val dns = lp?.dnsServers?.map { it.hostAddress }.orEmpty().sorted()
                    val addrs = lp?.linkAddresses?.map { it.toString() }.orEmpty().sorted()
                    "${net}:${lp?.interfaceName}:$dns:$addrs"
                } else null
            }.sorted()
            val activeDns = activeLink?.dnsServers?.map { it.hostAddress }.orEmpty().sorted()
            val activeAddrs = activeLink?.linkAddresses?.map { it.toString() }.orEmpty().sorted()
            val hasVpn = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            "active=$active|iface=${activeLink?.interfaceName}|vpn=$hasVpn|dns=$activeDns|addrs=$activeAddrs|vpns=$vpnNetworks"
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            "legacy=${info?.type}:${info?.subtype}:${info?.isConnected}:${info?.extraInfo}"
        }
    }.getOrDefault("unknown")

    private fun scheduleCheck(cm: ConnectivityManager, runtime: GeckoRuntime, reason: String) {
        pendingReset?.let(mainHandler::removeCallbacks)
        val task = Runnable {
            val next = currentSignature(cm)
            val prev = lastSignature
            if (prev != null && next != prev) {
                lastSignature = next
                Log.i(TAG, "Network/VPN changed ($reason); invalidating GeckoView network state")
                invalidateGeckoNetworkState(runtime)
            } else if (prev == null) {
                lastSignature = next
            }
        }
        pendingReset = task
        mainHandler.postDelayed(task, DEBOUNCE_MS)
    }

    private fun invalidateGeckoNetworkState(runtime: GeckoRuntime) {
        runCatching {
            GeckoAppShell.notifyObservers("network:link-status-changed", "changed")
        }.onFailure { Log.w(TAG, "notifyObservers(link-status-changed) failed", it) }

        runCatching {
            GeckoAppShell.notifyObservers("net:prune-all-connections", "")
        }.onFailure { Log.w(TAG, "notifyObservers(prune-all-connections) failed", it) }

        runCatching {
            val method = GeckoNetworkManager::class.java.getDeclaredMethod("onStatusChanged", String::class.java)
            method.isAccessible = true
            method.invoke(null, "changed")
        }.onFailure { Log.w(TAG, "GeckoNetworkManager.onStatusChanged failed", it) }

        runCatching {
            runtime.storageController.clearData(StorageController.ClearFlags.NETWORK_CACHE)
        }.onFailure { Log.w(TAG, "clearData(NETWORK_CACHE) failed", it) }
    }
}

internal object BrowserNetwork {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Fetches [request] via [executor] and blocks up to [timeoutMs].
     * Always creates the underlying [GeckoResult] on a Looper thread so timeout
     * cleanup listeners never attach to a null dispatcher.
     */
    fun fetchBlocking(
        executor: GeckoWebExecutor,
        request: WebRequest,
        flags: Int = 0,
        timeoutMs: Long = 10_000L
    ): WebResponse {
        val pending = if (Looper.myLooper() != null) {
            executor.fetch(request, flags)
        } else {
            val resultRef = AtomicReference<GeckoResult<WebResponse>?>(null)
            val errorRef = AtomicReference<Throwable?>(null)
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    resultRef.set(executor.fetch(request, flags))
                } catch (t: Throwable) {
                    errorRef.set(t)
                } finally {
                    latch.countDown()
                }
            }
            latch.await()
            errorRef.get()?.let { throw IOException("GeckoWebExecutor.fetch failed", it) }
            resultRef.get() ?: throw IOException("No GeckoResult returned")
        }
        return try {
            pending.poll(timeoutMs) ?: throw IOException("No response")
        } catch (error: TimeoutException) {
            pending.accept({ it?.body?.let { body -> runCatching { body.close() } } }, {})
            throw error
        }
    }

    fun agent(desktop: Boolean): String {
        val mobileAgent = Regex("""(rv:|Gecko/|Firefox/)(\d+)\.0\b""")
            .replace(GeckoSession.getDefaultUserAgent(), "$1$2")
        val agent = if (desktop) {
            "Mozilla/5.0 (X11; Desktop; rv:${mobileAgent.substringAfter("; rv:")}"
        } else {
            mobileAgent
        }
        return "$agent BrowserEngine/${BuildConfig.VERSION_NAME}"
    }

    fun mediaMime(url: String, contentType: String?): String? {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        val path = url.toHttpUriOrNull()?.encodedPath?.lowercase().orEmpty()
        return when {
            path.endsWith(".m3u8") || mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl") ->
                "application/x-mpegURL"
            path.endsWith(".mpd") || mime == "application/dash+xml" -> "application/dash+xml"
            mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true -> mime
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".m4a") -> "audio/mp4"
            path.endsWith(".aac") -> "audio/aac"
            path.endsWith(".ogg") || path.endsWith(".oga") -> "audio/ogg"
            path.endsWith(".opus") -> "audio/opus"
            path.endsWith(".wav") -> "audio/wav"
            path.endsWith(".flac") -> "audio/flac"
            else -> null
        }
    }
}

internal fun String.toHttpUriOrNull(): Uri? = Uri.parse(this).takeIf { uri ->
    (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        !uri.host.isNullOrBlank()
}

internal fun String.sameOriginAs(other: String): Boolean {
    val a = toHttpUriOrNull() ?: return false
    val b = other.toHttpUriOrNull() ?: return false
    return a.scheme.equals(b.scheme, ignoreCase = true) &&
        a.host.equals(b.host, ignoreCase = true) &&
        a.port == b.port
}

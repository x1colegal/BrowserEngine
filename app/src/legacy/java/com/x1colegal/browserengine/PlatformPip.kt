package com.x1colegal.browserengine

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

internal object PlatformPip {
    const val isSupported: Boolean = false

    fun configure(
        session: GeckoSession,
        runtime: GeckoRuntime,
        onEnter: (GeckoSession) -> Unit,
        onExit: () -> Unit
    ) = Unit

    fun request(session: GeckoSession, onFallback: () -> Unit = {}) {
        onFallback()
    }

    fun exit(session: GeckoSession, onComplete: () -> Unit = {}) {
        onComplete()
    }
}

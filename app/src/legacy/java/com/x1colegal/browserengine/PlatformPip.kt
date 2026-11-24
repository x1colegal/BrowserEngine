package com.x1colegal.browserengine

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession

internal object PlatformPip {
    fun configure(
        session: GeckoSession,
        runtime: GeckoRuntime,
        onEnter: (GeckoSession) -> Unit,
        onExit: () -> Unit
    ) = Unit

    fun request(session: GeckoSession) = Unit

    fun exit(session: GeckoSession) = Unit
}

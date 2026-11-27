package com.x1colegal.browserengine

import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.PictureInPictureController

internal object PlatformPip {
    const val isSupported: Boolean = true

    fun configure(
        session: GeckoSession,
        runtime: GeckoRuntime,
        onEnter: (GeckoSession) -> Unit,
        onExit: () -> Unit
    ) {
        session.setPictureInPictureDelegate(object :
            PictureInPictureController.SessionPictureInPicture.Delegate {
            override fun onEnterPictureInPicture(source: GeckoSession, token: String) {
                val clonedSettings = GeckoSessionSettings.Builder()
                    .usePrivateMode(source.settings.usePrivateMode)
                    .pictureInPictureToken(token)
                    .build()
                val cloned = GeckoSession(clonedSettings)
                cloned.open(runtime)
                source.sessionPictureInPicture.setKeepActive(true)
                onEnter(cloned)
            }

            override fun onExitPictureInPicture(source: GeckoSession) {
                source.sessionPictureInPicture.setKeepActive(false)
                source.setActive(true)
                source.setFocused(true)
                onExit()
            }
        })
    }

    fun request(session: GeckoSession, onFallback: () -> Unit = {}) {
        val result: GeckoResult<Void> = session.sessionPictureInPicture.request()
        result.accept(
            { /* onEnterPictureInPicture will fire when cloning is ready */ },
            { onFallback() }
        )
    }

    fun exit(session: GeckoSession, onComplete: () -> Unit = {}) {
        val finish = {
            runCatching {
                session.sessionPictureInPicture.setKeepActive(false)
                session.setActive(true)
                session.setFocused(true)
            }
            onComplete()
        }
        val result = runCatching { session.sessionPictureInPicture.exit() }.getOrNull()
        if (result != null) {
            result.accept({ finish() }, { finish() })
        } else {
            finish()
        }
    }
}

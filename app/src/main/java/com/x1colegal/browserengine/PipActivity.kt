package com.x1colegal.browserengine

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

internal object PipSessionHost {
    var session: GeckoSession? = null
    var aspect: Rational = Rational(16, 9)
    var playing: Boolean = true
    var onAction: ((Boolean) -> Unit)? = null
    var onClosed: ((Boolean) -> Unit)? = null
    var closePip: (() -> Unit)? = null
}

class PipActivity : Activity() {
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var geckoView: GeckoView? = null
    private var attachedSession: GeckoSession? = null
    private var mediaSession: MediaSession? = null
    private var enteredPip = false
    private var sessionPlaying = true
    private var closedByUser = true

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd")
            val exo = player
            if (exo != null) {
                when (cmd) {
                    "play" -> exo.play()
                    "pause" -> exo.pause()
                }
            } else {
                when (cmd) {
                    "play" -> {
                        sessionPlaying = true
                        PipSessionHost.onAction?.invoke(true)
                    }
                    "pause" -> {
                        sessionPlaying = false
                        PipSessionHost.onAction?.invoke(false)
                    }
                }
            }
            updateParams()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url")?.takeIf { it.isNotBlank() }
        val hostSession = if (url == null) PipSessionHost.session else null

        if (url == null && hostSession == null) {
            finish()
            return
        }

        if (url != null) {
            val positionMs = intent.getLongExtra("positionMs", 0L).coerceAtLeast(0L)
            val view = PlayerView(this).apply { useController = false }
            playerView = view
            setContentView(view)

            val exo = ExoPlayer.Builder(this).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                if (positionMs > 0L) seekTo(positionMs)
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateParams()
                    }
                })
                prepare()
            }
            player = exo
            view.player = exo
        } else if (hostSession != null) {
            attachedSession = hostSession
            sessionPlaying = PipSessionHost.playing
            val gv = GeckoView(this)
            geckoView = gv
            setContentView(gv)
            gv.setSession(hostSession)
            hostSession.setActive(true)
            hostSession.setFocused(true)
            hostSession.setPriorityHint(GeckoSession.PRIORITY_HIGH)
            PipSessionHost.closePip = {
                closedByUser = false
                finish()
            }
        }

        mediaSession = MediaSession(this, "BrowserEnginePip").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    player?.play() ?: run {
                        sessionPlaying = true
                        PipSessionHost.onAction?.invoke(true)
                    }
                    updateParams()
                }
                override fun onPause() {
                    player?.pause() ?: run {
                        sessionPlaying = false
                        PipSessionHost.onAction?.invoke(false)
                    }
                    updateParams()
                }
            })
            isActive = true
        }
        updateMediaSessionState()

        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.registerReceiver(
                this,
                pipReceiver,
                IntentFilter("com.x1colegal.browserengine.PIP_ACTIVITY_CONTROL"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            enteredPip = runCatching { enterPictureInPictureMode(buildParams()) }.getOrDefault(false)
        }
    }

    @RequiresApi(26)
    private fun buildParams(): PictureInPictureParams {
        val exo = player
        val playing = if (exo != null) (exo.isPlaying || exo.playWhenReady) else sessionPlaying
        val cmd = if (playing) "pause" else "play"
        val iconRes = if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play
        val label = if (playing) "Pause" else "Play"
        val pi = PendingIntent.getBroadcast(
            this,
            if (playing) 1 else 2,
            Intent("com.x1colegal.browserengine.PIP_ACTIVITY_CONTROL")
                .setPackage(packageName)
                .putExtra("cmd", cmd),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val action = RemoteAction(Icon.createWithResource(this, iconRes), label, label, pi)
        val size = exo?.videoSize
        val aspect = if (size != null && size.width > 0 && size.height > 0) {
            Rational(size.width, size.height)
        } else {
            PipSessionHost.aspect
        }
        return PictureInPictureParams.Builder()
            .setAspectRatio(aspect)
            .setActions(listOf(action))
            .build()
    }

    private fun updateMediaSessionState() {
        val exo = player
        val playing = if (exo != null) (exo.isPlaying || exo.playWhenReady) else sessionPlaying
        val pos = exo?.currentPosition?.coerceAtLeast(0L) ?: 0L
        val state = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                pos,
                1f
            )
            .build()
        mediaSession?.setPlaybackState(state)
    }

    private fun updateParams() {
        updateMediaSessionState()
        if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode && !isDestroyed && !isFinishing) {
            runCatching { setPictureInPictureParams(buildParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration?) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            updateParams()
        } else if (enteredPip) {
            closedByUser = false
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode) {
            attachedSession?.setActive(true)
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= 24 && !isInPictureInPictureMode && !isChangingConfigurations) {
            closedByUser = true
            finish()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching { unregisterReceiver(pipReceiver) }
        }
        val onClosed = PipSessionHost.onClosed
        PipSessionHost.session = null
        PipSessionHost.onAction = null
        PipSessionHost.onClosed = null
        PipSessionHost.closePip = null
        attachedSession?.let { s ->
            s.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
            geckoView?.releaseSession()
            onClosed?.invoke(closedByUser)
        }
        attachedSession = null
        geckoView = null
        playerView?.player = null
        player?.release()
        player = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}

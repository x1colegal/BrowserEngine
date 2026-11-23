package com.x1colegal.browserengine

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Rational
import androidx.annotation.RequiresApi
import com.google.android.exoplayer2.Player
import org.mozilla.geckoview.MediaSession

internal const val PIP_CONTROL = "com.x1colegal.browserengine.PIP_CONTROL"
internal const val PIP_COMMAND = "command"

internal enum class PipCommand(val label: String, val icon: Int) {
    BACK("Rewind", android.R.drawable.ic_media_rew),
    PLAY("Play", android.R.drawable.ic_media_play),
    PAUSE("Pause", android.R.drawable.ic_media_pause),
    FORWARD("Fast-forward", android.R.drawable.ic_media_ff);

    fun run(player: Player) {
        if (!available(player)) return
        when (this) {
            BACK -> player.seekBack()
            PLAY -> player.play()
            PAUSE -> player.pause()
            FORWARD -> player.seekForward()
        }
    }

    fun run(session: MediaSession, features: Long) {
        if (!available(features)) return
        when (this) {
            BACK -> session.seekBackward()
            PLAY -> session.play()
            PAUSE -> session.pause()
            FORWARD -> session.seekForward()
        }
    }

    fun available(player: Player): Boolean = player.isCommandAvailable(when (this) {
        BACK -> Player.COMMAND_SEEK_BACK
        PLAY, PAUSE -> Player.COMMAND_PLAY_PAUSE
        FORWARD -> Player.COMMAND_SEEK_FORWARD
    })

    fun available(features: Long): Boolean = (features and when (this) {
        BACK -> MediaSession.Feature.SEEK_BACKWARD
        PLAY -> MediaSession.Feature.PLAY
        PAUSE -> MediaSession.Feature.PAUSE
        FORWARD -> MediaSession.Feature.SEEK_FORWARD
    }) != 0L
}

@RequiresApi(26)
internal fun pipParams(
    context: Context,
    aspect: Rational,
    playing: Boolean,
    available: (PipCommand) -> Boolean
): PictureInPictureParams {
    val playback = if (playing) PipCommand.PAUSE else PipCommand.PLAY
    val actions = listOf(PipCommand.BACK, playback, PipCommand.FORWARD).filter(available).map { command ->
        val intent = Intent(PIP_CONTROL).setPackage(context.packageName).putExtra(PIP_COMMAND, command.name)
        val callback = PendingIntent.getBroadcast(context, command.ordinal, intent, PendingIntent.FLAG_IMMUTABLE)
        RemoteAction(Icon.createWithResource(context, command.icon), command.label, command.label, callback)
    }
    return PictureInPictureParams.Builder().setAspectRatio(aspect).setActions(actions).build()
}

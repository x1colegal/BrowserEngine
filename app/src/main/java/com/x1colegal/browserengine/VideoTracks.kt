package com.x1colegal.browserengine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import java.util.Locale

@Suppress("DEPRECATION")
@Composable
internal fun VideoTracksDialog(player: Player, tracks: Tracks, dismiss: () -> Unit) {
    var type by remember { mutableIntStateOf(C.TRACK_TYPE_AUDIO) }
    val params = player.trackSelectionParameters
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Playback tracks") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, name) in listOf(
                        C.TRACK_TYPE_AUDIO to "Audio", C.TRACK_TYPE_VIDEO to "Video", C.TRACK_TYPE_TEXT to "Subtitles"
                    )) {
                        FilterChip(selected = type == id, onClick = { type = id }, label = { Text(name) })
                    }
                }
                val subtitles = type == C.TRACK_TYPE_TEXT
                val options = tracks.groups.filter { it.type == type }.flatMap { group ->
                    (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
                }
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    TrackRow(
                        if (subtitles) "Off" else "Automatic",
                        selected = if (subtitles) type in params.disabledTrackTypes
                        else params.overrides.values.none { it.type == type }
                    ) {
                        player.trackSelectionParameters = params.buildUpon()
                            .clearOverridesOfType(type)
                            .setTrackTypeDisabled(type, subtitles)
                            .build()
                        dismiss()
                    }
                    options.forEachIndexed { number, (group, index) ->
                        val selected = type !in params.disabledTrackTypes &&
                            params.overrides[group.mediaTrackGroup]?.trackIndices?.contains(index) == true
                        TrackRow(trackLabel(group.getTrackFormat(index), type, number + 1), selected) {
                            player.trackSelectionParameters = params.buildUpon()
                                .setTrackTypeDisabled(type, false)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                                .build()
                            dismiss()
                        }
                    }
                    if (options.isEmpty()) Text("No compatible tracks found.")
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } }
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, choose: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected, onClick = null) },
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = choose)
    )
}

private fun trackLabel(format: Format, type: Int, number: Int): String = buildList {
    format.label?.takeIf { it.isNotBlank() }?.let(::add)
    if (type != C.TRACK_TYPE_VIDEO) {
        format.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            val locale = Locale.forLanguageTag(it)
            val name = locale.getDisplayName(Locale.ENGLISH).ifBlank { it }
            if (name !in this) add(name)
        }
    }
    if (type == C.TRACK_TYPE_VIDEO && format.height > 0) add("${format.height}p")
    val codec = format.sampleMimeType?.substringAfter('/') ?: format.codecs
    codec?.uppercase(Locale.ROOT)?.let(::add)
    if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 0) add("${format.channelCount} ch")
    if (format.bitrate > 0) add("${format.bitrate / 1000} kbps")
}.ifEmpty { listOf("Track $number") }.joinToString(" · ")

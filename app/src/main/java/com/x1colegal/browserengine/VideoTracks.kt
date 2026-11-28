package com.x1colegal.browserengine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.Tracks
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.audio.AudioRendererEventListener
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer
import com.google.android.exoplayer2.decoder.DecoderInputBuffer
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.source.LoadEventInfo
import com.google.android.exoplayer2.source.MediaLoadData
import com.google.android.exoplayer2.trackselection.TrackSelectionOverride
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer
import com.google.android.exoplayer2.video.VideoRendererEventListener
import java.util.Locale
import kotlin.math.roundToInt

internal class ExoStreamStats {
    private val mainHandler = Handler(Looper.getMainLooper())
    var measuredVideoBps by mutableIntStateOf(0)
    var measuredAudioBps by mutableIntStateOf(0)
    var measuredTotalBps by mutableIntStateOf(0)
    var bandwidthEstimateBps by mutableLongStateOf(0L)
    var videoDecoderName by mutableStateOf<String?>(null)
    var audioDecoderName by mutableStateOf<String?>(null)

    private var videoWindowStartUs = C.TIME_UNSET
    private var videoAccumBytes = 0L
    private var audioWindowStartUs = C.TIME_UNSET
    private var audioAccumBytes = 0L

    fun recordSample(isVideo: Boolean, buffer: DecoderInputBuffer) {
        if (buffer.isEndOfStream) return
        val ptsUs = buffer.timeUs
        if (ptsUs == C.TIME_UNSET || ptsUs < 0L) return
        val bytes = buffer.data?.remaining()?.toLong() ?: 0L
        if (bytes <= 0L) return
        if (isVideo) {
            if (videoWindowStartUs == C.TIME_UNSET || ptsUs < videoWindowStartUs) {
                videoWindowStartUs = ptsUs
                videoAccumBytes = bytes
                return
            }
            videoAccumBytes += bytes
            val spanUs = ptsUs - videoWindowStartUs
            if (spanUs >= 1_000_000L) {
                val bps = ((videoAccumBytes * 8_000_000L) / spanUs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                videoWindowStartUs = ptsUs
                videoAccumBytes = 0L
                mainHandler.post { measuredVideoBps = bps }
            }
        } else {
            if (audioWindowStartUs == C.TIME_UNSET || ptsUs < audioWindowStartUs) {
                audioWindowStartUs = ptsUs
                audioAccumBytes = bytes
                return
            }
            audioAccumBytes += bytes
            val spanUs = ptsUs - audioWindowStartUs
            if (spanUs >= 1_000_000L) {
                val bps = ((audioAccumBytes * 8_000_000L) / spanUs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                audioWindowStartUs = ptsUs
                audioAccumBytes = 0L
                mainHandler.post { measuredAudioBps = bps }
            }
        }
    }

    fun attach(player: ExoPlayer) {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                videoDecoderName = decoderName
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                audioDecoderName = decoderName
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                if (bitrateEstimate > 0L) bandwidthEstimateBps = bitrateEstimate
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                val bytes = loadEventInfo.bytesLoaded
                val segMs = mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs
                val durMs = if (segMs > 0L) segMs else player.duration.takeIf { it > 0L && mediaLoadData.dataType == C.DATA_TYPE_MEDIA } ?: 0L
                if (bytes <= 0L || durMs <= 0L) return
                val bps = ((bytes * 8_000L) / durMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                when (mediaLoadData.trackType) {
                    C.TRACK_TYPE_VIDEO -> measuredVideoBps = bps
                    C.TRACK_TYPE_AUDIO -> measuredAudioBps = bps
                    else -> measuredTotalBps = bps
                }
            }
        })
    }
}

internal enum class DecoderMode(val label: String) {
    AUTO("Auto"),
    HARDWARE("Force HW"),
    SOFTWARE("Force SW")
}

internal object PlaybackPreferences {
    private const val PREF_FILE = "browser"
    private const val KEY_VIDEO_DECODER = "video_decoder_mode"
    private const val KEY_AUDIO_DECODER = "audio_decoder_mode"

    @Volatile var videoDecoderMode = DecoderMode.AUTO
    @Volatile var audioDecoderMode = DecoderMode.AUTO

    fun load(context: Context) {
        val prefs = AppPrefs.get(context.applicationContext, PREF_FILE)
        videoDecoderMode = runCatching {
            DecoderMode.valueOf(prefs.getString(KEY_VIDEO_DECODER, "AUTO")!!)
        }.getOrDefault(DecoderMode.AUTO)
        audioDecoderMode = runCatching {
            DecoderMode.valueOf(prefs.getString(KEY_AUDIO_DECODER, "AUTO")!!)
        }.getOrDefault(DecoderMode.AUTO)
    }

    fun saveVideo(context: Context, mode: DecoderMode) {
        videoDecoderMode = mode
        AppPrefs.get(context.applicationContext, PREF_FILE)
            .edit().putString(KEY_VIDEO_DECODER, mode.name).apply()
    }

    fun saveAudio(context: Context, mode: DecoderMode) {
        audioDecoderMode = mode
        AppPrefs.get(context.applicationContext, PREF_FILE)
            .edit().putString(KEY_AUDIO_DECODER, mode.name).apply()
    }

    @Suppress("DEPRECATION")
    fun createRenderersFactory(context: Context, stats: ExoStreamStats? = null): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                ctx: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                if (stats == null) {
                    super.buildVideoRenderers(
                        ctx, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                        eventHandler, eventListener, allowedVideoJoiningTimeMs, out
                    )
                    return
                }
                out.add(
                    object : MediaCodecVideoRenderer(
                        ctx, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
                        enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
                    ) {
                        override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
                            stats.recordSample(true, buffer)
                            super.onQueueInputBuffer(buffer)
                        }
                    }
                )
            }

            override fun buildAudioRenderers(
                ctx: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>
            ) {
                if (stats == null) {
                    super.buildAudioRenderers(
                        ctx, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                        audioSink, eventHandler, eventListener, out
                    )
                    return
                }
                out.add(
                    object : MediaCodecAudioRenderer(
                        ctx, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback,
                        eventHandler, eventListener, audioSink
                    ) {
                        override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
                            stats.recordSample(false, buffer)
                            super.onQueueInputBuffer(buffer)
                        }
                    }
                )
            }
        }
            .setEnableDecoderFallback(videoDecoderMode == DecoderMode.AUTO || audioDecoderMode == DecoderMode.AUTO)
            .setMediaCodecSelector(MediaCodecSelector { mimeType, secure, tunneling ->
                val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                val mode = if (mimeType.startsWith("audio/")) audioDecoderMode else videoDecoderMode
                when (mode) {
                    DecoderMode.AUTO -> decoders.sortedBy { it.softwareOnly }
                    DecoderMode.HARDWARE -> decoders.filter { it.hardwareAccelerated && !it.softwareOnly }
                    DecoderMode.SOFTWARE -> decoders.filter { it.softwareOnly || !it.hardwareAccelerated }
                }
            })
}

@Composable
internal fun <T> DecoderChoiceSection(
    title: String,
    values: Iterable<T>,
    selected: T,
    label: (T) -> String,
    select: (T) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(values.toList()) { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { select(value) },
                    label = { Text(label(value), maxLines = 1) }
                )
            }
        }
    }
}

@Suppress("DEPRECATION")
internal fun selectBestTracks(player: Player, tracks: Tracks) {
    val params = player.trackSelectionParameters
    var builder: com.google.android.exoplayer2.trackselection.TrackSelectionParameters.Builder? = null

    fun ensureBuilder() = builder ?: params.buildUpon().also { builder = it }

    if (params.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO }) {
        val bestVideo = tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
            .maxWithOrNull(compareBy<Pair<Tracks.Group, Int>>(
                { it.first.getTrackFormat(it.second).height },
                { effectiveBitrate(it.first.getTrackFormat(it.second)) },
                { it.first.getTrackFormat(it.second).frameRate }
            ))
        if (bestVideo != null) {
            ensureBuilder()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .setOverrideForType(TrackSelectionOverride(bestVideo.first.mediaTrackGroup, bestVideo.second))
        }
    }

    if (params.overrides.values.none { it.type == C.TRACK_TYPE_AUDIO }) {
        val bestAudio = tracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it } }
            .maxWithOrNull(compareBy<Pair<Tracks.Group, Int>>(
                { effectiveBitrate(it.first.getTrackFormat(it.second)) },
                { it.first.getTrackFormat(it.second).channelCount },
                { it.first.getTrackFormat(it.second).sampleRate }
            ))
        if (bestAudio != null) {
            ensureBuilder()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(TrackSelectionOverride(bestAudio.first.mediaTrackGroup, bestAudio.second))
        }
    }

    builder?.let { player.trackSelectionParameters = it.build() }
}

@Suppress("DEPRECATION")
@Composable
internal fun VideoTracksDialog(
    player: Player,
    tracks: Tracks,
    stats: ExoStreamStats = remember { ExoStreamStats() },
    dismiss: () -> Unit
) {
    val hasVideo = remember(tracks) { tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO } }
    val hasAudio = remember(tracks) { tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO } }
    var type by remember(hasVideo) { mutableIntStateOf(if (hasVideo) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO) }
    val params = player.trackSelectionParameters
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Playback tracks") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((id, name) in listOf(
                        C.TRACK_TYPE_VIDEO to "Video", C.TRACK_TYPE_AUDIO to "Audio", C.TRACK_TYPE_TEXT to "Subtitles"
                    )) {
                        FilterChip(selected = type == id, onClick = { type = id }, label = { Text(name) })
                    }
                }
                val subtitles = type == C.TRACK_TYPE_TEXT
                val options = tracks.groups.filter { it.type == type }.flatMap { group ->
                    (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
                }
                val hasOverride = params.overrides.values.any { it.type == type }
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    if (subtitles) {
                        TrackRow("Off", selected = type in params.disabledTrackTypes) {
                            player.trackSelectionParameters = params.buildUpon()
                                .clearOverridesOfType(type)
                                .setTrackTypeDisabled(type, true)
                                .build()
                            dismiss()
                        }
                    }
                    options.forEachIndexed { number, (group, index) ->
                        val selected = type !in params.disabledTrackTypes && (
                            params.overrides[group.mediaTrackGroup]?.trackIndices?.contains(index) == true ||
                                (!hasOverride && group.isTrackSelected(index))
                            )
                        val fallbackBps = if (selected || options.size == 1) {
                            when (type) {
                                C.TRACK_TYPE_VIDEO -> stats.measuredVideoBps.takeIf { it > 0 }
                                    ?: if (!hasAudio) stats.measuredTotalBps else 0
                                C.TRACK_TYPE_AUDIO -> stats.measuredAudioBps.takeIf { it > 0 }
                                    ?: if (!hasVideo) stats.measuredTotalBps else 0
                                else -> 0
                            }
                        } else 0
                        TrackRow(trackLabel(group.getTrackFormat(index), type, number + 1, fallbackBps), selected) {
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

private fun effectiveBitrate(format: Format): Int =
    format.bitrate.takeIf { it > 0 }
        ?: format.averageBitrate.takeIf { it > 0 }
        ?: format.peakBitrate.takeIf { it > 0 }
        ?: Format.NO_VALUE

private fun formatBitrateLabel(bitrate: Int, isVideo: Boolean): String? = when {
    bitrate <= 0 -> null
    isVideo && bitrate >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bitrate / 1_000_000.0)
    else -> "${bitrate / 1000} kbps"
}

private fun trackLabel(format: Format, type: Int, number: Int, fallbackBps: Int = 0): String = buildList {
    format.label?.takeIf { it.isNotBlank() }?.let(::add)
    if (type != C.TRACK_TYPE_VIDEO) {
        format.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            val locale = Locale.forLanguageTag(it)
            val name = locale.getDisplayName(Locale.ENGLISH).ifBlank { it }
            if (name !in this) add(name)
        }
    }
    if (type == C.TRACK_TYPE_VIDEO) {
        if (format.height > 0) add("${format.height}p")
        if (format.frameRate > 0f) add("${format.frameRate.roundToInt()} FPS")
    }
    val codec = format.codecs?.takeIf { it.isNotBlank() }
        ?: format.sampleMimeType?.substringAfter('/')
    codec?.uppercase(Locale.ROOT)?.let(::add)
    if (type == C.TRACK_TYPE_AUDIO) {
        if (format.sampleRate > 0) add("${format.sampleRate} Hz")
        if (format.channelCount > 0) add("${format.channelCount} ch")
    }
    val knownBps = effectiveBitrate(format)
    val bps = knownBps.takeIf { it > 0 } ?: fallbackBps
    formatBitrateLabel(bps, type == C.TRACK_TYPE_VIDEO)?.let {
        add(if (knownBps > 0) it else "~$it")
    }
}.ifEmpty { listOf("Track $number") }.joinToString(" · ")

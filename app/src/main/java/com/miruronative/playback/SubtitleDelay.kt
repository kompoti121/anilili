package com.miruronative.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How far the subtitles are shifted against the picture, in milliseconds: positive holds each line
 * back, negative brings it forward.
 *
 * Providers sometimes hand out a subtitle file cut for a different encode of the same episode —
 * MegaPlay serves the sub encode's file alongside its dub streams for shows it has no dub-timed
 * subtitles for, and those encodes carry different amounts of head footage — which lands every line
 * seconds early. The loader seeds the measured offset here; the viewer can trim it by hand from the
 * player settings, which is also the way out of any desync we did not detect.
 *
 * [delayUs] is read by the text renderer on the playback thread on every pass, hence the plain
 * volatile field beside the flow the UI collects.
 */
object SubtitleDelay {
    const val MAX_MS = 30_000L
    const val STEP_MS = 250L

    private val _delayMs = MutableStateFlow(0L)
    val delayMs: StateFlow<Long> = _delayMs.asStateFlow()

    @Volatile
    var delayUs: Long = 0L
        private set

    /** Whether the current value was measured by the loader rather than chosen by the viewer. */
    @Volatile
    var isAutomatic: Boolean = false
        private set

    fun set(ms: Long, automatic: Boolean = false) {
        val clamped = ms.coerceIn(-MAX_MS, MAX_MS)
        delayUs = clamped * 1_000L
        isAutomatic = automatic && clamped != 0L
        _delayMs.value = clamped
    }

    fun nudge(deltaMs: Long) = set(_delayMs.value + deltaMs)

    fun reset() = set(0L)
}

/**
 * Shifts the position handed to the text renderer, so subtitles can be nudged against the video
 * without reloading anything: the renderer is asked for the cue at `position - delay`, so a line
 * timed at T reaches the screen at T + delay. Media3's `TextRenderer` is final, so the shift rides
 * on a [ForwardingRenderer] wrapped around it and every other renderer keeps the stock behaviour.
 */
@UnstableApi
internal class SubtitleDelayRenderer(renderer: Renderer) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(shift(positionUs), elapsedRealtimeUs)
    }

    // Left exactly alone while no delay is set, so the untouched case stays the stock path. The
    // shifted value may go negative early in an episode; that simply means "before the first cue",
    // which is the honest answer when the first line is not due yet.
    private fun shift(positionUs: Long): Long {
        val delayUs = SubtitleDelay.delayUs
        return if (delayUs == 0L) positionUs else positionUs - delayUs
    }
}

/** [DefaultRenderersFactory] whose text renderers honour [SubtitleDelay]. */
@UnstableApi
class SubtitleDelayRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        val first = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (i in first until out.size) out[i] = SubtitleDelayRenderer(out[i])
    }
}

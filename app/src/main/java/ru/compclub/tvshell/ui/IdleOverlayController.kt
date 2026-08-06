package ru.compclub.tvshell.ui

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.compclub.tvshell.data.ShellApi
import java.net.HttpURLConnection
import java.net.URL

/**
 * PC-shell style: 6 side blocks with image/video + text.
 * Videos: ExoPlayer + SurfaceView, staggered open, full release on stop.
 */
class IdleOverlayController(
    private val slots: Map<String, OverlaySlotView>,
    private val scope: LifecycleCoroutineScope,
    private val api: () -> ShellApi,
) {
    data class OverlaySlotView(
        val root: View,
        val title: TextView,
        val image: ImageView,
        val video: PlayerView,
        val text: TextView,
        val label: String,
        val openDelayMs: Int = 0,
    )

    private data class PlayerSlot(
        var player: ExoPlayer? = null,
        var url: String? = null,
        var startJob: Job? = null,
    )

    private var pollJob: Job? = null
    private val players = mutableMapOf<String, PlayerSlot>()
    private var playingAllowed = false

    fun start() {
        stop()
        playingAllowed = true
        slots.values.forEach { slot ->
            slot.title.text = slot.label
            slot.root.alpha = 0.08f
        }
        pollJob = scope.launch {
            while (isActive && playingAllowed) {
                val blocks = withContext(Dispatchers.IO) {
                    runCatching { api().getOverlayBlocks() }.getOrDefault(emptyMap())
                }
                if (!playingAllowed) break
                apply(blocks)
                delay(45_000)
            }
        }
    }

    fun stop() {
        playingAllowed = false
        pollJob?.cancel()
        pollJob = null
        // Cancel pending staggered starts first, then tear down decoders.
        players.values.forEach { it.startJob?.cancel(); it.startJob = null }
        slots.keys.toList().forEach { releasePlayer(it) }
        players.clear()
        slots.values.forEach { clearVisual(it) }
    }

    /** Alias: after login we must destroy decoders, not just hide views. */
    fun kill() = stop()

    private suspend fun apply(blocks: Map<String, ShellApi.OverlayBlock>) {
        for ((pos, slot) in slots) {
            if (!playingAllowed) return
            val block = blocks[pos]
            if (block == null || !block.active) {
                releasePlayer(pos)
                clearVisual(slot)
                slot.root.alpha = 0.08f
                continue
            }
            slot.root.alpha = 1f
            val imageLayer = block.layers.firstOrNull { it.kind == "image" }
            val videoLayer = block.layers.firstOrNull { it.kind == "video" }
            val textLayer = block.layers.firstOrNull { it.kind == "text" }

            if (videoLayer != null) {
                slot.image.visibility = View.GONE
                scheduleVideo(pos, slot, videoLayer.value)
            } else {
                releasePlayer(pos)
                slot.video.visibility = View.GONE
                if (imageLayer != null) {
                    val bmp = withContext(Dispatchers.IO) { loadBitmap(imageLayer.value) }
                    if (!playingAllowed) return
                    if (bmp != null) {
                        slot.image.setImageBitmap(bmp)
                        slot.image.visibility = View.VISIBLE
                    } else {
                        slot.image.visibility = View.GONE
                    }
                } else {
                    slot.image.setImageDrawable(null)
                    slot.image.visibility = View.GONE
                }
            }

            if (textLayer != null && textLayer.value.isNotBlank()) {
                slot.text.text = textLayer.value
                slot.text.setTextColor(parseColor(textLayer.color, Color.WHITE))
                val sp = (textLayer.size.takeIf { it > 0 } ?: 22).coerceIn(14, 36)
                slot.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
                slot.text.visibility = View.VISIBLE
            } else {
                slot.text.visibility = View.GONE
            }
        }
    }

    private fun scheduleVideo(pos: String, slot: OverlaySlotView, url: String) {
        val state = players.getOrPut(pos) { PlayerSlot() }
        if (state.url == url && state.player != null) {
            slot.video.visibility = View.VISIBLE
            state.player?.playWhenReady = true
            return
        }
        state.startJob?.cancel()
        state.startJob = scope.launch {
            delay(slot.openDelayMs.toLong())
            if (!playingAllowed) return@launch
            attachPlayer(pos, slot, url)
        }
    }

    private fun attachPlayer(pos: String, slot: OverlaySlotView, url: String) {
        val state = players.getOrPut(pos) { PlayerSlot() }
        if (state.url == url && state.player != null) {
            slot.video.visibility = View.VISIBLE
            return
        }
        releasePlayer(pos, keepSlot = true)

        val player = runCatching {
            ExoPlayer.Builder(slot.root.context).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                prepare()
                playWhenReady = true
            }
        }.onFailure {
            Log.w(TAG, "ExoPlayer create failed [$pos]: ${it.message}")
        }.getOrNull() ?: return

        slot.video.player = player
        slot.video.visibility = View.VISIBLE
        state.player = player
        state.url = url
        Log.i(TAG, "play[$pos] delay=${slot.openDelayMs}ms $url")
    }

    private fun releasePlayer(pos: String, keepSlot: Boolean = false) {
        val state = players[pos] ?: return
        state.startJob?.cancel()
        state.startJob = null
        val slot = slots[pos]
        val player = state.player
        state.player = null
        state.url = null
        // Detach surface before release so TV compositor drops the stream.
        slot?.video?.player = null
        if (player != null) {
            runCatching {
                player.playWhenReady = false
                player.pause()
                player.stop()
                player.clearMediaItems()
                player.release()
            }.onFailure { Log.w(TAG, "release[$pos]: ${it.message}") }
            Log.i(TAG, "killed player[$pos]")
        }
        if (!keepSlot) {
            players.remove(pos)
            slot?.video?.visibility = View.GONE
        }
    }

    private fun clearVisual(slot: OverlaySlotView) {
        slot.image.setImageDrawable(null)
        slot.image.visibility = View.GONE
        slot.video.player = null
        slot.video.visibility = View.GONE
        slot.text.visibility = View.GONE
        slot.text.text = ""
    }

    private fun loadBitmap(url: String) = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
        }
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun parseColor(raw: String?, fallback: Int): Int {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { Color.parseColor(raw) }.getOrDefault(fallback)
    }

    companion object {
        private const val TAG = "IdleOverlay"

        /** Same stagger order as PC OverlayBlock openDelayMs. */
        val SLOT_META = listOf(
            Triple("top_left", "CAM_01 / TOP_LEFT", 0),
            Triple("mid_left", "DAT_02 / MID_LEFT", 500),
            Triple("bottom_left", "INF_03 / BOTTOM_LEFT", 1000),
            Triple("top_right", "CAM_04 / TOP_RIGHT", 250),
            Triple("mid_right", "DAT_05 / MID_RIGHT", 750),
            Triple("bottom_right", "INF_06 / BOTTOM_RIGHT", 1250),
        )
    }
}

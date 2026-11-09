package com.nova.watchparty.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.nova.watchparty.net.RoomState
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Synced YouTube playback through YouTube's official IFrame player. Host has controls; guests follow. */
@Composable
fun YouTubeSync(room: RoomState, onControl: (String, Double) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val isHost = room.isHost
    val hostNow by rememberUpdatedState(isHost)
    val send by rememberUpdatedState(onControl)
    var yt by remember { mutableStateOf<YouTubePlayer?>(null) }
    var loadedId by remember { mutableStateOf("") }
    var curSec by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }

    val view = remember(isHost) {
        yt = null; loadedId = ""
        YouTubePlayerView(ctx).apply {
            enableAutomaticInitialization = false
            val options = IFramePlayerOptions.Builder().controls(if (isHost) 1 else 0).build()
            initialize(object : AbstractYouTubePlayerListener() {
                override fun onReady(youTubePlayer: YouTubePlayer) { yt = youTubePlayer }
                override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                    if (hostNow && abs(second - curSec) > 2.5f) send("seek", second.toDouble())
                    curSec = second
                }
                override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                    when (state) {
                        PlayerConstants.PlayerState.PLAYING -> { playing = true; if (hostNow) send("play", curSec.toDouble()) }
                        PlayerConstants.PlayerState.PAUSED -> { playing = false; if (hostNow) send("pause", curSec.toDouble()) }
                        PlayerConstants.PlayerState.ENDED -> { playing = false; if (hostNow) send("ended", 0.0) }
                        else -> {}
                    }
                }
            }, options)
        }
    }
    DisposableEffect(view) { onDispose { view.release() } }

    // Apply server state
    LaunchedEffect(room.version, room.url, yt) {
        val p = yt ?: return@LaunchedEffect
        val id = room.url.removePrefix("yt:")
        if (id != loadedId) {
            loadedId = id
            curSec = room.position.toFloat()
            p.loadVideo(id, room.position.toFloat())
            if (!room.playing) p.pause()
        } else if (!hostNow) {
            if (room.playing) p.play() else p.pause()
            if (abs(curSec - room.position) > 2.0) p.seekTo(room.position.toFloat())
        }
    }

    // Host heartbeat
    LaunchedEffect(isHost) {
        while (isHost) {
            delay(4000)
            if (loadedId.isNotBlank()) send(if (playing) "play" else "pause", curSec.toDouble())
        }
    }

    AndroidView(factory = { view }, modifier = modifier)
}

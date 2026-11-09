package com.nova.watchparty.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nova.watchparty.net.Backend
import com.nova.watchparty.net.RoomState
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Bridge from the player page to Kotlin. Calls arrive on a WebView thread, so they are posted to the main thread.
 * Player states follow the YouTube IFrame API: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued.
 * We add 100 = "loaded; held paused on purpose" (used when the room is paused at load time).
 */
class YtBridge(
    private val onReady: () -> Unit,
    private val onState: (Int) -> Unit,
    private val onTime: (Double, Double, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun ready() { main.post { onReady() } }
    @JavascriptInterface fun state(code: Int) { main.post { onState(code) } }
    @JavascriptInterface fun time(sec: Double, dur: Double, state: Int) { main.post { onTime(sec, dur, state) } }
    @JavascriptInterface fun error(code: String) { main.post { onError(code) } }
}

/**
 * Why the IFrame player is hosted here instead of through a wrapper library: YouTube only plays embedded videos when
 * the embedding page has a real HTTPS origin / Referer. A page loaded with a missing or wrong base URL gets the generic
 * "An error occurred. Please try again later (Playback ID: ...)" screen or error 153. We load the page with a proper
 * origin and, if YouTube still refuses, retry automatically with the next origin before telling the user.
 */
private fun playerHtml(origin: String, controls: Int) = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;top:0;left:0;width:100%;height:100%}</style>
</head><body><div id="p"></div>
<script>
var P=null, ok=false, pend=null, holdPause=false, ORIGIN="${origin}", CTRL=${controls};
function fail(c){ try{ NovaYT.error(String(c)); }catch(e){} }
window.onYouTubeIframeAPIReady=function(){
  P=new YT.Player('p',{width:'100%',height:'100%',
    playerVars:{playsinline:1,controls:CTRL,rel:0,modestbranding:1,fs:0,disablekb:1,iv_load_policy:3,enablejsapi:1,origin:ORIGIN,widget_referrer:ORIGIN},
    events:{
      onReady:function(){ ok=true; try{ NovaYT.ready(); }catch(e){} if(pend){ var a=pend; pend=null; nova.load(a[0],a[1],a[2]); } },
      onStateChange:function(e){
        if(e.data===1 && holdPause){ holdPause=false; P.pauseVideo(); try{ NovaYT.state(100); }catch(x){} return; }
        try{ NovaYT.state(e.data); }catch(x){}
      },
      onError:function(e){ fail(e.data); }
    }});
};
var s=document.createElement('script'); s.src='https://www.youtube.com/iframe_api'; s.onerror=function(){ fail('api'); }; document.head.appendChild(s);
window.nova={
  load:function(id,start,play){ if(!ok){ pend=[id,start,play]; return; } holdPause=!play; P.loadVideoById({videoId:id,startSeconds:start||0}); },
  play:function(){ if(ok){ holdPause=false; P.playVideo(); } },
  pause:function(){ if(ok){ holdPause=false; P.pauseVideo(); } },
  seek:function(t){ if(ok) P.seekTo(t,true); }
};
setInterval(function(){ if(!ok) return; try{ NovaYT.time(P.getCurrentTime()||0, P.getDuration()||0, P.getPlayerState()); }catch(e){} },500);
</script></body></html>
"""

private fun errorText(code: String) = when (code) {
    "100" -> "This video was removed or is private."
    "101", "150", "152" -> "The video's owner doesn't allow it to be played outside YouTube."
    "2" -> "This video link isn't valid."
    else -> "YouTube wouldn't play this video here (code $code)."
}

/** Synced YouTube playback through YouTube's official IFrame player. Host has controls; guests follow. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSync(
    room: RoomState,
    onControl: (String, Double) -> Unit,
    onUnplayable: (String, String) -> Unit,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    val isHost = room.isHost
    val hostNow by rememberUpdatedState(isHost)
    val send by rememberUpdatedState(onControl)
    val report by rememberUpdatedState(onUnplayable)
    val roomNow by rememberUpdatedState(room)
    val videoId = room.url.removePrefix("yt:")

    // Origins to try, best first. The app-id origin is what YouTube documents for Android embedders.
    val origins = remember {
        listOfNotNull("https://${ctx.packageName}", Backend.base.takeIf { it.startsWith("https://") }, "https://www.youtube.com")
    }
    var originIdx by remember { mutableIntStateOf(0) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var loadedId by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var curSec by remember { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }

    fun fail(code: String) {
        val retryable = code == "153" || code == "5" || code == "api" || code == "timeout"
        if (retryable && originIdx < origins.lastIndex) { originIdx++; return }   // try the next origin
        if (error == null) { error = code; report(roomNow.url, code) }
    }

    // (Re)load the player page whenever the origin or the host/guest role changes
    LaunchedEffect(web, originIdx, isHost, reloadKey) {
        val w = web ?: return@LaunchedEffect
        ready = false; loadedId = ""; started = false
        val origin = origins[originIdx]
        w.loadDataWithBaseURL("$origin/", playerHtml(origin, if (isHost) 1 else 0), "text/html", "utf-8", null)
    }

    // New video: clear any previous error
    LaunchedEffect(videoId) { error = null; started = false }

    // Apply server state
    LaunchedEffect(room.version, room.url, ready) {
        val w = web ?: return@LaunchedEffect
        if (!ready || videoId.isBlank() || error != null) return@LaunchedEffect
        if (videoId != loadedId) {
            loadedId = videoId; started = false
            curSec = room.position.toFloat()
            w.evaluateJavascript("nova.load('${videoId.replace("'", "")}', ${room.position}, ${room.playing})", null)
        } else if (!hostNow) {
            w.evaluateJavascript(if (room.playing) "nova.play()" else "nova.pause()", null)
            if (abs(curSec - room.position) > 2.0) w.evaluateJavascript("nova.seek(${room.position})", null)
        }
    }

    // Watchdog: YouTube's generic "An error occurred" screen sends no error event, so detect "never started"
    LaunchedEffect(videoId, originIdx, ready, error) {
        if (!ready || videoId.isBlank() || error != null) return@LaunchedEffect
        delay(15000)
        if (!started && error == null) fail("timeout")
    }

    // Host heartbeat so late joiners and drifting guests stay aligned
    LaunchedEffect(isHost) {
        while (isHost) {
            delay(4000)
            if (loadedId.isNotBlank() && started && error == null) send(if (playing) "play" else "pause", curSec.toDouble())
        }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            WebView(c).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
                configureWebView(this)
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                addJavascriptInterface(YtBridge(
                    onReady = { ready = true },
                    onState = { st ->
                        when (st) {
                            1 -> { started = true; playing = true; if (hostNow) send("play", curSec.toDouble()) }
                            2 -> { started = true; playing = false; if (hostNow) send("pause", curSec.toDouble()) }
                            3, 100 -> started = true
                            0 -> { playing = false; if (hostNow) send("ended", 0.0) }
                        }
                    },
                    onTime = { sec, _, _ ->
                        val s = sec.toFloat()
                        if (hostNow && started && abs(s - curSec) > 2.5f) send("seek", sec)   // host scrubbed
                        curSec = s
                    },
                    onError = { code -> fail(code) },
                ), "NovaYT")
                web = this
            }
        }, onRelease = { it.removeJavascriptInterface("NovaYT"); it.destroy() })

        error?.let { code ->
            Column(Modifier.fillMaxSize().background(Color(0xE6000000)).padding(16.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(errorText(code), color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Open in YouTube") }
                    TextButton({ error = null; originIdx = 0; reloadKey++ }) { Text("Retry") }
                    if (isHost) TextButton({ send("ended", 0.0) }) { Text("Skip") }
                }
            }
        }
    }
}

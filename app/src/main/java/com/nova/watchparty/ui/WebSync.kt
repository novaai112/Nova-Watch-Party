package com.nova.watchparty.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.nova.watchparty.net.RoomState
import kotlinx.coroutines.delay

/** JS -> Kotlin bridge. The page script reports the main <video> element's state; only the host's reports are forwarded. */
class WebBridge(private val onReport: (Boolean, Double) -> Unit, private val onEnded: () -> Unit) {
    @JavascriptInterface @Suppress("unused") fun report(playing: Boolean, time: Double) = onReport(playing, time)
    @JavascriptInterface @Suppress("unused") fun ended() = onEnded()
}

private val INJECT = """
(function(){
  if (window.__nova) return; window.__nova = 1;
  function pick(){ var best=null; document.querySelectorAll('video').forEach(function(v){ if(!best||(v.duration||0)>(best.duration||0)||!v.paused) best=v; }); return best; }
  window.__novaApply = function(playing, pos){
    var v = pick(); if(!v) return;
    if (Math.abs(v.currentTime - pos) > 2) v.currentTime = pos;
    if (playing && v.paused) { var p = v.play(); if (p && p.catch) p.catch(function(){}); }
    if (!playing && !v.paused) v.pause();
  };
  var lastP = null, lastT = 0, bound = null, n = 0;
  setInterval(function(){
    var v = pick(); if(!v || !window.NovaBridge) return;
    if (v !== bound) { bound = v; v.addEventListener('ended', function(){ NovaBridge.ended(); }); }
    var p = !v.paused, t = v.currentTime;
    if (p !== lastP || Math.abs(t - lastT - (p ? 0.5 : 0)) > 2 || (++n % 8 === 0)) NovaBridge.report(p, t);
    lastP = p; lastT = t;
  }, 500);
})();
"""

@SuppressLint("SetJavaScriptEnabled")
fun configureWebView(w: WebView) {
    w.settings.apply {
        javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
        loadWithOverviewMode = true; useWideViewPort = true; setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
}

/** Syncs the <video> element of any web page (non-DRM-locked control is via the page's own player). Everyone loads the same page. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSync(room: RoomState, onControl: (String, Double) -> Unit, modifier: Modifier) {
    val isHost = room.isHost
    val hostNow by rememberUpdatedState(isHost)
    val send by rememberUpdatedState(onControl)
    val pageUrl = room.url.removePrefix("web:")
    var view by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }

    AndroidView(modifier = modifier, factory = { c ->
        WebView(c).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            configureWebView(this)
            addJavascriptInterface(WebBridge(
                onReport = { p, t -> if (hostNow) send(if (p) "play" else "pause", t) },
                onEnded = { if (hostNow) send("ended", 0.0) },
            ), "NovaBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { ready = false }
                override fun onPageFinished(v: WebView, url: String) { v.evaluateJavascript(INJECT, null); ready = true }
            }
            webChromeClient = WebChromeClient()
            loadUrl(pageUrl)
            view = this
        }
    }, onRelease = { it.destroy() })

    // Load a new page when the host changes video
    LaunchedEffect(pageUrl) { view?.let { if (it.url != pageUrl) it.loadUrl(pageUrl) } }
    // Guests follow the host; also re-apply periodically so late starters catch up
    LaunchedEffect(room.version, ready) {
        if (!ready || hostNow) return@LaunchedEffect
        repeat(4) {
            view?.evaluateJavascript("window.__novaApply && window.__novaApply(${room.playing}, ${room.position})", null)
            delay(2500)
        }
    }
}

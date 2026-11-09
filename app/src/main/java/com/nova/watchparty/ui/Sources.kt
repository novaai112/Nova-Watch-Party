package com.nova.watchparty.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.nova.watchparty.*
import com.nova.watchparty.net.*
import kotlinx.coroutines.launch

private enum class Kind { Grid, Videos, Live, Playlists, Karaoke, Trailers, Web, Likes, History }
private data class Page(val kind: Kind = Kind.Grid, val arg: String = "", val title: String = "")

private data class Tile(val label: String, val icon: ImageVector?, val family: FontFamily, val weight: FontWeight, val italic: Boolean, val size: Int, val page: Page)

private val TILES = listOf(
    Tile("YouTube", Icons.Default.PlayCircle, FontFamily.SansSerif, FontWeight.Bold, false, 26, Page(Kind.Web, "https://m.youtube.com", "YouTube")),
    Tile("X", null, FontFamily.SansSerif, FontWeight.Light, false, 44, Page(Kind.Web, "https://x.com", "X")),
    Tile("NETFLIX", null, FontFamily.Serif, FontWeight.Black, false, 26, Page(Kind.Web, "https://www.netflix.com", "Netflix")),
    Tile("prime", null, FontFamily.SansSerif, FontWeight.Bold, true, 30, Page(Kind.Web, "https://www.primevideo.com", "Prime Video")),
    Tile("Crunchyroll", Icons.Default.Animation, FontFamily.SansSerif, FontWeight.Medium, false, 22, Page(Kind.Web, "https://www.crunchyroll.com", "Crunchyroll")),
    Tile("twitch", null, FontFamily.SansSerif, FontWeight.Black, true, 30, Page(Kind.Web, "https://m.twitch.tv", "Twitch")),
    Tile("LIVE", Icons.Default.PlayCircle, FontFamily.SansSerif, FontWeight.Bold, false, 26, Page(Kind.Live, "live", "Live")),
    Tile("Playlist", null, FontFamily.Cursive, FontWeight.Bold, true, 30, Page(Kind.Playlists, "", "Playlist")),
    Tile("Drive", Icons.Default.Cloud, FontFamily.SansSerif, FontWeight.Medium, false, 26, Page(Kind.Web, "https://drive.google.com", "Drive")),
    Tile("Photos", Icons.Default.PhotoLibrary, FontFamily.SansSerif, FontWeight.Medium, false, 26, Page(Kind.Web, "https://photos.google.com", "Photos")),
    Tile("WEB", Icons.Default.Language, FontFamily.SansSerif, FontWeight.Black, false, 26, Page(Kind.Web, "https://www.google.com", "Web")),
    Tile("Trailers", Icons.Default.Movie, FontFamily.Serif, FontWeight.Bold, true, 26, Page(Kind.Trailers, "", "Trailers")),
    Tile("Karaoke", null, FontFamily.Cursive, FontWeight.Bold, true, 30, Page(Kind.Karaoke, "karaoke", "Karaoke")),
    Tile("Likes", Icons.Default.Favorite, FontFamily.Cursive, FontWeight.Bold, true, 28, Page(Kind.Likes, "likes", "Likes")),
    Tile("History", Icons.Default.History, FontFamily.Serif, FontWeight.Normal, false, 26, Page(Kind.History, "history", "History")),
)

@Composable
fun SourcesScreen(vm: AppViewModel, mode: SourceMode) {
    var page by remember { mutableStateOf(Page()) }
    Api.strict = vm.user?.flags?.hideMature != false

    fun finish(list: List<Pick>) {
        if (list.isEmpty()) return
        when {
            mode == SourceMode.Start -> { vm.pendingQueue = list.drop(1); vm.createRoom(list.first()) }
            else -> { vm.pickMany(list); vm.back() }
        }
    }
    val pick: (Pick) -> Unit = { finish(listOf(it)) }

    BackHandler(enabled = page.kind != Kind.Grid) { page = Page() }

    Column(Modifier.fillMaxSize()) {
        if (page.kind != Kind.Web) {
            TopBarBack({ if (page.kind == Kind.Grid) vm.back() else page = Page() }, title = {
                if (page.kind == Kind.Grid) NovaLogo(30.dp) else Text(page.title.ifBlank { "Search" }, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light)
            })
        }
        when (page.kind) {
            Kind.Grid -> GridPage(onSearch = { q ->
                page = if (BuildConfig.YOUTUBE_API_KEY.isBlank()) Page(Kind.Web, "https://m.youtube.com/results?search_query=" + Uri.encode(q), "YouTube")
                else Page(Kind.Videos, q, "Search")
            }, onTile = { page = it })
            Kind.Videos -> VideoSearch(initial = page.arg, live = false, onPick = pick)
            Kind.Live -> VideoSearch(initial = "", live = true, onPick = pick)
            Kind.Karaoke -> VideoSearch(initial = "karaoke", live = false, prefix = "karaoke ", onPick = pick)
            Kind.Playlists -> PlaylistSearch(onPickAll = ::finish)
            Kind.Trailers -> TrailersPane(pick)
            Kind.Likes, Kind.History -> SavedList(page.arg, vm, pick)
            Kind.Web -> WebBrowser(page.arg, page.title, onBack = { page = Page() }, onWatch = { url, title -> pick(pickFromUrl(url, title)) })
        }
    }
}

private typealias Uri = android.net.Uri

@Composable
private fun GridPage(onSearch: (String) -> Unit, onTile: (Page) -> Unit) {
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(46.dp).glass(RoundedCornerShape(10.dp), 0.18f).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            BasicTextField(q, { q = it }, singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) onSearch(q) }),
                decorationBox = { inner -> if (q.isEmpty()) Text("search video, series, or film…", color = Color.White.copy(0.55f), fontSize = 15.sp); inner() })
        }
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TILES) { t ->
                Row(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(12.dp)).clickable { onTile(t.page) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    t.icon?.let { Icon(it, null, tint = if (t.label == "YouTube" || t.label == "LIVE") Color(0xFFFF4B4B) else Color.White, modifier = Modifier.size((t.size * 1.1f).dp)); Spacer(Modifier.width(6.dp)) }
                    Text(t.label, color = Color.White, fontFamily = t.family, fontWeight = t.weight, fontStyle = if (t.italic) FontStyle.Italic else FontStyle.Normal, fontSize = t.size.sp)
                }
            }
        }
    }
}

@Composable
private fun VideoSearch(initial: String, live: Boolean, prefix: String = "", onPick: (Pick) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf(initial) }
    var state by remember { mutableStateOf<Load<List<VideoHit>>>(Load.Idle) }
    fun run() = scope.launch {
        if (q.isBlank() && !live) return@launch
        state = Load.Busy
        state = try { Load.Ok(Api.youtubeSearch(prefix + q, live)) } catch (e: Exception) { Load.Fail(e.message ?: "Search failed") }
    }
    LaunchedEffect(Unit) { run() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(46.dp).glass(RoundedCornerShape(10.dp), 0.18f).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = Color.White); Spacer(Modifier.width(10.dp))
            BasicTextField(q, { q = it }, singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }))
            if (q.isNotEmpty()) Icon(Icons.Default.Close, "Clear", tint = Color.White, modifier = Modifier.clickable { q = "" })
        }
        Results(state) { list ->
            if (list.isEmpty()) Text("No videos found", color = SubtleText, modifier = Modifier.padding(24.dp))
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                items(list, key = { it.id }) { v ->
                    VideoRow(v.thumb, v.title, v.channel) { onPick(Pick("yt:${v.id}", v.title, v.thumb)) }
                }
            }
        }
    }
}

@Composable
private fun VideoRow(thumb: String, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(thumb, null, Modifier.width(100.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(3.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, color = SubtleText, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun PlaylistSearch(onPickAll: (List<Pick>) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<Load<List<PlaylistHit>>>(Load.Idle) }
    var loading by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun run() = scope.launch {
        if (q.isBlank()) return@launch
        state = Load.Busy
        state = try { Load.Ok(Api.playlistSearch(q)) } catch (e: Exception) { Load.Fail(e.message ?: "Search failed") }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(46.dp).glass(RoundedCornerShape(10.dp), 0.18f).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = Color.White); Spacer(Modifier.width(10.dp))
            BasicTextField(q, { q = it }, singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }),
                decorationBox = { inner -> if (q.isEmpty()) Text("search YouTube playlists", color = Color.White.copy(0.55f), fontSize = 16.sp); inner() })
        }
        if (loading) Spinner()
        Results(state) { list ->
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                items(list, key = { it.id }) { p ->
                    VideoRow(p.thumb, p.title, p.channel) {
                        scope.launch {
                            loading = true
                            try {
                                val vids = Api.playlistVideos(p.id)
                                if (vids.isEmpty()) android.widget.Toast.makeText(ctx, "That playlist is empty", android.widget.Toast.LENGTH_SHORT).show()
                                else onPickAll(vids.map { Pick("yt:${it.id}", it.title, it.thumb) })
                            } catch (e: Exception) { android.widget.Toast.makeText(ctx, e.message ?: "Failed", android.widget.Toast.LENGTH_SHORT).show() }
                            loading = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedList(kind: String, vm: AppViewModel, onPick: (Pick) -> Unit) {
    var state by remember { mutableStateOf<Load<List<SavedItem>>>(Load.Busy) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(kind) { state = try { Load.Ok(Backend.saved(kind)) } catch (e: ApiError) { Load.Fail(e.message ?: "Failed") } }
    Results(state) { list ->
        if (list.isEmpty()) Column(Modifier.fillMaxSize(), Alignment.CenterHorizontally, Arrangement.Center) {
            Icon(Icons.Default.VideocamOff, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(90.dp))
            Text("No videos found", color = Color.White.copy(0.85f))
        } else LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.url }) { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { VideoRow(s.thumb, s.title, "") { onPick(Pick(s.url, s.title, s.thumb)) } }
                    IconButton({ scope.launch { try { Backend.removeSaved(kind, s.url); state = Load.Ok(list - s) } catch (_: ApiError) {} } }) {
                        Icon(Icons.Default.Close, "Remove", tint = SubtleText)
                    }
                }
            }
        }
    }
}

@Composable
private fun WebBrowser(startUrl: String, title: String, onBack: () -> Unit, onWatch: (String, String) -> Unit) {
    var view by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    BackHandler(enabled = canBack) { view?.goBack() }
    Column(Modifier.fillMaxSize()) {
        TopBarBack(onBack, title = { Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light) }, trailing = {
            IconButton({ view?.let { onWatch(it.url ?: startUrl, it.title ?: title) } }) { Icon(Icons.Default.OpenInNew, "Watch together", tint = Color.White) }
        })
        Text("Open the video you want, then tap the icon at the top right to watch it together.", color = SubtleText, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            WebView(c).apply {
                configureWebView(this)
                webViewClient = object : WebViewClient() { override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) { canBack = v.canGoBack() } }
                loadUrl(startUrl); view = this
            }
        }, onRelease = { it.destroy() })
    }
}

package com.nova.watchparty.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nova.watchparty.Pick
import com.nova.watchparty.net.*
import kotlinx.coroutines.launch

private val YT_REGEX = Regex("""(?:youtu\.be/|youtube\.com/(?:watch\?(?:[^#]*&)?v=|shorts/|embed/|live/))([A-Za-z0-9_-]{11})""")

fun ytThumb(id: String) = "https://i.ytimg.com/vi/$id/mqdefault.jpg"

/** YouTube links become "yt:<id>"; http(s) pages become "web:<url>" (in-page video sync); anything else is a direct media URL. */
fun parseVideoInput(s: String): String {
    val t = s.trim()
    YT_REGEX.find(t)?.let { return "yt:${it.groupValues[1]}" }
    return t
}

fun pickFromUrl(url: String, title: String): Pick {
    val parsed = parseVideoInput(url)
    return when {
        parsed.startsWith("yt:") -> Pick(parsed, title.ifBlank { "YouTube video" }, ytThumb(parsed.removePrefix("yt:")))
        parsed.startsWith("http") && !Regex("""\.(mp4|webm|m3u8|mkv)(\?|$)""").containsMatchIn(parsed) -> Pick("web:$parsed", title.ifBlank { Uri.parse(parsed).host ?: "Web video" }, "")
        else -> Pick(parsed, title.ifBlank { "Video" }, "")
    }
}

private fun isGiphy(u: String): Boolean {
    val uri = Uri.parse(u)
    return uri.scheme == "https" && (uri.host ?: "").endsWith(".giphy.com")
}

fun isGifMessage(text: String) = text.startsWith("gif:") && isGiphy(text.removePrefix("gif:"))
fun isImageMessage(text: String) = text.startsWith("img:/chat/")

@Composable
fun ChatText(text: String, color: Color = Color.White) {
    if (isGifMessage(text)) {
        AsyncImage(
            model = text.removePrefix("gif:"), contentDescription = "GIF",
            modifier = Modifier.width(180.dp).heightIn(max = 180.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit,
        )
    } else if (isImageMessage(text)) {
        AsyncImage(
            model = Backend.absolute(text.removePrefix("img:")), contentDescription = "Photo",
            modifier = Modifier.width(200.dp).heightIn(max = 240.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit,
        )
    } else Text(text, color = color, fontSize = 14.sp)
}

sealed interface Load<out T> {
    object Idle : Load<Nothing>
    object Busy : Load<Nothing>
    data class Ok<T>(val v: T) : Load<T>
    data class Fail(val msg: String) : Load<Nothing>
}

@Composable
fun <T> Results(state: Load<T>, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Idle -> {}
        Load.Busy -> Spinner()
        is Load.Fail -> Text(state.msg, color = Color(0xFFFF8A8A), modifier = Modifier.padding(16.dp), fontSize = 13.sp)
        is Load.Ok -> content(state.v)
    }
}

@Composable
fun GifPane(onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<List<GifHit>>>(Load.Idle) }
    var q by remember { mutableStateOf("") }
    fun load() = scope.launch {
        state = Load.Busy
        state = try { Load.Ok(Api.gifs(q)) } catch (e: Exception) { Load.Fail(e.message ?: "Failed") }
    }
    LaunchedEffect(Unit) { load() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search GIFs") },
            trailingIcon = { IconButton({ load() }) { Icon(Icons.Default.Search, "Search") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { load() }))
        Results(state) { list ->
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.url }) { g ->
                    AsyncImage(g.url, "GIF", Modifier.fillMaxWidth().heightIn(min = 90.dp).clip(RoundedCornerShape(10.dp)).clickable { onPick(g.url) }, contentScale = ContentScale.Crop)
                }
            }
        }
        Text("Powered by GIPHY", fontSize = 10.sp, color = SubtleText)
    }
}

@Composable
fun TrailersPane(onPick: (Pick) -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Load<List<MovieHit>>>(Load.Idle) }
    var error by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    fun load() = scope.launch {
        state = Load.Busy
        state = try { Load.Ok(Api.movies(q)) } catch (e: Exception) { Load.Fail(e.message ?: "Failed") }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search movies (empty = trending)") },
            trailingIcon = { IconButton({ load() }) { Icon(Icons.Default.Search, "Search") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { load() }))
        error?.let { Text(it, color = Color(0xFFFF8A8A), fontSize = 12.sp) }
        Results(state) { list ->
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { m ->
                    AsyncImage(m.poster, m.title, Modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).clickable {
                        scope.launch {
                            error = null
                            try { val k = Api.trailerKey(m.id); onPick(Pick("yt:$k", m.title + " (trailer)", ytThumb(k))) } catch (e: Exception) { error = e.message }
                        }
                    }, contentScale = ContentScale.Crop)
                }
            }
        }
        Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", fontSize = 10.sp, color = SubtleText)
    }
}

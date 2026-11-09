package com.nova.watchparty.net

import android.text.Html
import com.nova.watchparty.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class VideoHit(val id: String, val title: String, val channel: String, val thumb: String)
data class PlaylistHit(val id: String, val title: String, val channel: String, val thumb: String)
data class MovieHit(val id: Int, val title: String, val poster: String)
data class GifHit(val url: String)

object Api {
    /** true = YouTube safeSearch strict (Settings > Hide Mature Content) */
    @Volatile var strict: Boolean = true
    private val http = OkHttpClient()

    private suspend fun get(base: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val url = base.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) {
                val hint = if (r.code == 403 || r.code == 401 || r.code == 429) " (check key, restrictions or daily quota)" else ""
                error("Request failed: HTTP ${r.code}$hint")
            }
            JSONObject(r.body!!.string())
        }
    }

    private fun need(key: String, name: String) {
        if (key.isBlank()) error("$name is not set. Add it to local.properties (see README) and rebuild.")
    }

    suspend fun youtubeSearch(q: String, live: Boolean = false): List<VideoHit> {
        need(BuildConfig.YOUTUBE_API_KEY, "YOUTUBE_API_KEY")
        val o = get("https://www.googleapis.com/youtube/v3/search", mapOf(
            "part" to "snippet", "type" to "video", "maxResults" to "15", "q" to q,
            "videoEmbeddable" to "true", "safeSearch" to (if (strict) "strict" else "moderate"), "key" to BuildConfig.YOUTUBE_API_KEY)
            + (if (live) mapOf("eventType" to "live") else emptyMap()))
        val a = o.optJSONArray("items") ?: return emptyList()
        return List(a.length()) {
            val i = a.getJSONObject(it); val sn = i.getJSONObject("snippet")
            VideoHit(
                id = i.getJSONObject("id").getString("videoId"),
                title = Html.fromHtml(sn.getString("title"), Html.FROM_HTML_MODE_LEGACY).toString(),
                channel = sn.optString("channelTitle"),
                thumb = sn.getJSONObject("thumbnails").getJSONObject("medium").getString("url"),
            )
        }
    }

    /** Empty query = trending this week. */
    suspend fun movies(q: String): List<MovieHit> {
        need(BuildConfig.TMDB_API_KEY, "TMDB_API_KEY")
        val o = if (q.isBlank()) get("https://api.themoviedb.org/3/trending/movie/week", mapOf("api_key" to BuildConfig.TMDB_API_KEY))
        else get("https://api.themoviedb.org/3/search/movie", mapOf("api_key" to BuildConfig.TMDB_API_KEY, "query" to q))
        val a = o.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.isNull("poster_path") }.map {
            MovieHit(it.getInt("id"), it.getString("title"), "https://image.tmdb.org/t/p/w342" + it.getString("poster_path"))
        }
    }

    /** Returns the YouTube video key of the best trailer. */
    suspend fun trailerKey(movieId: Int): String {
        need(BuildConfig.TMDB_API_KEY, "TMDB_API_KEY")
        val o = get("https://api.themoviedb.org/3/movie/$movieId/videos", mapOf("api_key" to BuildConfig.TMDB_API_KEY))
        val a = o.optJSONArray("results") ?: error("No trailer found")
        val vids = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("site") == "YouTube" }
        val best = vids.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
            ?: vids.firstOrNull { it.optString("type") == "Trailer" } ?: vids.firstOrNull()
            ?: error("No trailer found for this title")
        return best.getString("key")
    }

    /** Empty query = trending. */
    suspend fun gifs(q: String): List<GifHit> {
        need(BuildConfig.GIPHY_API_KEY, "GIPHY_API_KEY")
        val base = mapOf("api_key" to BuildConfig.GIPHY_API_KEY, "limit" to "24", "rating" to "pg-13")
        val o = if (q.isBlank()) get("https://api.giphy.com/v1/gifs/trending", base)
        else get("https://api.giphy.com/v1/gifs/search", base + ("q" to q))
        val a = o.optJSONArray("data") ?: return emptyList()
        return List(a.length()) {
            GifHit(a.getJSONObject(it).getJSONObject("images").getJSONObject("fixed_height").getString("url"))
        }
    }

    suspend fun playlistSearch(q: String): List<PlaylistHit> {
        need(BuildConfig.YOUTUBE_API_KEY, "YOUTUBE_API_KEY")
        val o = get("https://www.googleapis.com/youtube/v3/search", mapOf("part" to "snippet", "type" to "playlist",
            "maxResults" to "15", "q" to q, "key" to BuildConfig.YOUTUBE_API_KEY))
        val a = o.optJSONArray("items") ?: return emptyList()
        return List(a.length()) {
            val i = a.getJSONObject(it); val sn = i.getJSONObject("snippet")
            PlaylistHit(i.getJSONObject("id").getString("playlistId"),
                Html.fromHtml(sn.getString("title"), Html.FROM_HTML_MODE_LEGACY).toString(), sn.optString("channelTitle"),
                sn.getJSONObject("thumbnails").getJSONObject("medium").getString("url"))
        }
    }

    suspend fun playlistVideos(id: String): List<VideoHit> {
        need(BuildConfig.YOUTUBE_API_KEY, "YOUTUBE_API_KEY")
        val o = get("https://www.googleapis.com/youtube/v3/playlistItems", mapOf("part" to "snippet", "maxResults" to "25",
            "playlistId" to id, "key" to BuildConfig.YOUTUBE_API_KEY))
        val a = o.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull {
            val sn = a.getJSONObject(it).getJSONObject("snippet")
            val vid = sn.optJSONObject("resourceId")?.optString("videoId") ?: return@mapNotNull null
            val th = sn.optJSONObject("thumbnails")?.optJSONObject("medium")?.optString("url") ?: return@mapNotNull null
            VideoHit(vid, sn.optString("title"), sn.optString("videoOwnerChannelTitle"), th)
        }
    }
}

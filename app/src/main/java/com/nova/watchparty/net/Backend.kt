package com.nova.watchparty.net

import com.nova.watchparty.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiError(message: String) : Exception(message)
data class AuthResult(val token: String, val user: User, val newUser: Boolean, val devCode: String? = null)

/** REST client for the Nova server (accounts, profile, public rooms, history, likes, friends). */
object Backend {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    var wsUrl: String = ""
    var token: String = ""
    val base: String get() = wsUrl.trim().replaceFirst("ws", "http").trimEnd('/')
    fun absolute(path: String): String = when {
        path.isBlank() -> ""
        path.startsWith("http") -> path
        else -> base + path
    }

    private fun jb(o: JSONObject): RequestBody = o.toString().toRequestBody(JSON)

    private suspend fun call(method: String, path: String, body: RequestBody? = null, auth: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            val rb = Request.Builder().url(base + path)
            if (auth && token.isNotBlank()) rb.header("Authorization", "Bearer $token")
            rb.method(method, if (method == "GET") null else (body ?: "".toRequestBody(null)))
            try {
                http.newCall(rb.build()).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    val o = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
                    if (!r.isSuccessful) throw ApiError(o.optString("error", "Server error (${r.code})"))
                    o
                }
            } catch (e: ApiError) { throw e
            } catch (e: IllegalArgumentException) { throw ApiError("Invalid server address")
            } catch (e: IOException) { throw ApiError("Can't reach the server. Check your connection.") }
        }

    private fun auth(o: JSONObject) = AuthResult(o.getString("token"), o.getJSONObject("user").toUser(), o.optBoolean("newUser"), o.optString("devCode").ifBlank { null })

    suspend fun requestCode(email: String): String? =
        call("POST", "/auth/email", jb(JSONObject().put("email", email)), auth = false).optString("devCode").ifBlank { null }
    suspend fun verify(email: String, code: String) =
        auth(call("POST", "/auth/verify", jb(JSONObject().put("email", email).put("code", code)), auth = false))
    suspend fun guest(name: String) = auth(call("POST", "/auth/guest", jb(JSONObject().put("name", name)), auth = false))
    suspend fun google(idToken: String) = auth(call("POST", "/auth/google", jb(JSONObject().put("idToken", idToken)), auth = false))

    suspend fun me(): User = call("GET", "/me").toUser()
    suspend fun updateMe(patch: JSONObject): User = call("POST", "/me", jb(patch)).toUser()
    suspend fun uploadAvatar(jpeg: ByteArray): User = call("POST", "/me/avatar", jpeg.toRequestBody("image/jpeg".toMediaType())).toUser()
    suspend fun deleteMe() { call("DELETE", "/me") }
    suspend fun suggestHandles(name: String): List<String> {
        val a = call("GET", "/handles/suggest?name=" + java.net.URLEncoder.encode(name, "UTF-8"), auth = false).optJSONArray("handles")
        return if (a == null) emptyList() else List(a.length()) { a.getString(it) }
    }

    suspend fun publicRooms(q: String): List<RoomCard> =
        call("GET", "/rooms/public?q=" + java.net.URLEncoder.encode(q, "UTF-8")).optJSONArray("rooms").mapObjects { it.toRoomCard() }

    suspend fun saved(kind: String): List<SavedItem> = call("GET", "/$kind").optJSONArray("items").mapObjects { it.toSaved() }
    suspend fun addSaved(kind: String, p: Pick) {
        call("POST", "/$kind", jb(JSONObject().put("url", p.url).put("title", p.title).put("thumb", p.thumb)))
    }
    suspend fun removeSaved(kind: String, url: String) { call("DELETE", "/$kind?url=" + java.net.URLEncoder.encode(url, "UTF-8")) }

    suspend fun sendLocation(lat: Double?, lon: Double?) {
        call("POST", "/me/location", jb(JSONObject().apply { if (lat != null && lon != null) { put("lat", lat); put("lon", lon) } }))
    }
    suspend fun registerPush(token: String) { call("POST", "/me/push", jb(JSONObject().put("token", token))) }
    suspend fun inviteFriend(code: String, friendId: String): Boolean =
        call("POST", "/rooms/invite", jb(JSONObject().put("code", code).put("friendId", friendId))).optInt("sent") > 0
    suspend fun setInterests(list: List<String>): User = call("POST", "/me", jb(JSONObject().put("interests", org.json.JSONArray(list)))).toUser()

    suspend fun friendsState(): FriendsState {
        val o = call("GET", "/friends")
        return FriendsState(o.optJSONArray("friends").mapObjects { it.toFriend() }, o.optJSONArray("incoming").mapObjects { it.toFriend() }, o.optJSONArray("outgoing").mapObjects { it.toFriend() })
    }
    suspend fun friends(): List<Friend> = friendsState().friends
    /** Returns "requested" or "friends" (if they had already asked you). */
    suspend fun requestFriend(handle: String): String =
        call("POST", "/friends/request", jb(JSONObject().put("handle", handle))).optString("status")
    suspend fun acceptFriend(id: String) { call("POST", "/friends/accept", jb(JSONObject().put("id", id))) }
    suspend fun declineFriend(id: String) { call("POST", "/friends/decline", jb(JSONObject().put("id", id))) }
    suspend fun profile(handle: String): Friend = call("GET", "/users/lookup?handle=" + java.net.URLEncoder.encode(handle, "UTF-8")).toFriend()
    suspend fun blocks(): List<Friend> = call("GET", "/blocks").optJSONArray("users").mapObjects { it.toFriend() }
    suspend fun block(id: String) { call("POST", "/blocks", jb(JSONObject().put("id", id))) }
    suspend fun unblock(id: String) { call("DELETE", "/blocks?id=" + java.net.URLEncoder.encode(id, "UTF-8")) }
    suspend fun report(target: String, room: String?, reason: String) {
        call("POST", "/reports", jb(JSONObject().put("target", target).put("room", room ?: "").put("reason", reason)))
    }
    suspend fun uploadGallery(jpeg: ByteArray): User = call("POST", "/me/gallery", jpeg.toRequestBody("image/jpeg".toMediaType())).toUser()
    suspend fun deleteGallery(path: String): User = call("DELETE", "/me/gallery?path=" + java.net.URLEncoder.encode(path, "UTF-8")).toUser()
    suspend fun uploadChatImage(jpeg: ByteArray): String = call("POST", "/chat/image", jpeg.toRequestBody("image/jpeg".toMediaType())).getString("path")
    suspend fun updateExtras(channels: List<String>, socials: Map<String, String>): User =
        call("POST", "/me", jb(JSONObject().put("channels", org.json.JSONArray(channels)).put("socials", JSONObject(socials)))).toUser()
    suspend fun removeFriend(id: String) { call("POST", "/friends/remove", jb(JSONObject().put("id", id))) }
}

package com.nova.watchparty

import org.json.JSONArray
import org.json.JSONObject

data class Flags(
    val quickReaction: String = "❤️", val restrictInvites: Boolean = false, val hideMature: Boolean = true,
    val floatingPlayer: Boolean = false, val autoTranslate: Boolean = false, val hideLocation: Boolean = false,
)

data class User(
    val id: String, val name: String, val handle: String, val bio: String, val avatar: String, val interests: List<String> = emptyList(),
    val gallery: List<String> = emptyList(), val channels: List<String> = emptyList(), val socials: Map<String, String> = emptyMap(),
    val email: String, val guest: Boolean, val joined: Long, val minutes: Int, val flags: Flags,
)

data class Friend(
    val id: String, val name: String, val handle: String, val avatar: String, val bio: String,
    val interests: List<String> = emptyList(), val gallery: List<String> = emptyList(),
    val channels: List<String> = emptyList(), val socials: Map<String, String> = emptyMap(),
)
data class FriendsState(val friends: List<Friend>, val incoming: List<Friend>, val outgoing: List<Friend>)
data class Pick(val url: String, val title: String, val thumb: String)
data class MemberLite(val name: String, val avatar: String)
data class RoomCard(val code: String, val title: String, val thumb: String, val count: Int, val members: List<MemberLite>)
data class Member(val id: String, val uid: String, val name: String, val handle: String, val avatar: String, val host: Boolean, val mic: Boolean = false)
data class ChatMsg(val from: String, val uid: String, val handle: String, val avatar: String, val text: String, val ts: Long, val system: Boolean = false)
data class QueueItem(val id: String, val url: String, val title: String, val thumb: String, val by: String, val votes: Int)
data class RoomSettings(val privacy: String = "public", val playback: String = "vote", val voice: Boolean = true)
data class SavedItem(val url: String, val title: String, val thumb: String)

fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else List(length()) { f(getJSONObject(it)) }

fun JSONObject.toFlags(): Flags {
    val d = Flags()
    return Flags(
        quickReaction = optString("quickReaction", d.quickReaction), restrictInvites = optBoolean("restrictInvites", d.restrictInvites),
        hideMature = optBoolean("hideMature", d.hideMature), floatingPlayer = optBoolean("floatingPlayer", d.floatingPlayer),
        autoTranslate = optBoolean("autoTranslate", d.autoTranslate), hideLocation = optBoolean("hideLocation", d.hideLocation),
    )
}

fun JSONObject.toUser() = User(
    id = getString("id"), name = optString("name"), handle = optString("handle"), bio = optString("bio"),
    avatar = optString("avatar"), interests = strList("interests"), gallery = strList("gallery"), channels = strList("channels"), socials = strMap("socials"), email = optString("email"), guest = optBoolean("guest"),
    joined = optLong("joined"), minutes = optInt("minutes"), flags = (optJSONObject("flags") ?: JSONObject()).toFlags(),
)

fun JSONObject.strList(key: String): List<String> = optJSONArray(key).let { a -> if (a == null) emptyList() else List(a.length()) { a.getString(it) } }
fun JSONObject.strMap(key: String): Map<String, String> = optJSONObject(key).let { o -> if (o == null) emptyMap() else o.keys().asSequence().associateWith { k -> o.optString(k) } }
fun JSONObject.toFriend() = Friend(getString("id"), optString("name"), optString("handle"), optString("avatar"), optString("bio"),
    strList("interests"), strList("gallery"), strList("channels"), strMap("socials"))
fun JSONObject.toSaved() = SavedItem(getString("url"), optString("title"), optString("thumb"))
fun JSONObject.toRoomCard() = RoomCard(
    getString("code"), optString("title"), optString("thumb"), optInt("count"),
    optJSONArray("members").mapObjects { MemberLite(it.optString("name"), it.optString("avatar")) },
)

package com.nova.watchparty.net

import com.nova.watchparty.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RoomState(
    val code: String, val myId: String, val hostId: String,
    val url: String, val title: String, val thumb: String, val playing: Boolean, val position: Double,
    val members: List<Member>, val settings: RoomSettings, val version: Long = 0,
) {
    val isHost get() = myId == hostId
}

data class Reaction(val from: String, val emoji: String, val key: Long)

/** Realtime room connection: sync, chat, queue/votes, settings. */
class RoomClient {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null

    private val _room = MutableStateFlow<RoomState?>(null)
    val room: StateFlow<RoomState?> = _room
    private val _chat = MutableStateFlow<List<ChatMsg>>(emptyList())
    val chat: StateFlow<List<ChatMsg>> = _chat
    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue
    private val _requests = MutableStateFlow<List<JoinRequest>>(emptyList())
    /** People waiting for the host to let them in (only the host receives these). */
    val requests: StateFlow<List<JoinRequest>> = _requests
    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting
    val reactions = MutableSharedFlow<Reaction>(extraBufferCapacity = 32)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Emitted when the room being joined needs (or rejected) a passcode. */
    val passcodeRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val rtc = MutableSharedFlow<Pair<String, JSONObject>>(extraBufferCapacity = 64)
    private var counter = 0L

    private var lastServer = ""
    private var lastToken = ""
    private var attempts = 0
    private var intentionalClose = false
    private var keepChat = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun open(serverUrl: String, hello: JSONObject) {
        close()
        intentionalClose = false; attempts = 0; keepChat = false
        lastServer = serverUrl; lastToken = hello.optString("token")
        _connecting.value = true
        connect(serverUrl, hello)
    }

    private fun connect(serverUrl: String, hello: JSONObject) {
        val req = try { Request.Builder().url(serverUrl.trim()).build() } catch (_: Exception) {
            _connecting.value = false; errors.tryEmit("Invalid server address"); return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(hello.toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { handle(JSONObject(text)) } catch (_: Exception) {}
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws || intentionalClose) return
                val code = _room.value?.code
                if (code != null && attempts < 5) {            // dropped while in a room: try to get back in
                    attempts++; keepChat = true
                    _connecting.value = true
                    errors.tryEmit("Reconnecting…")
                    handler.postDelayed({
                        if (!intentionalClose) connect(lastServer, JSONObject().put("type", "join").put("token", lastToken).put("code", code))
                    }, (1500L * attempts).coerceAtMost(8000L))
                    return
                }
                val wasInRoom = code != null
                _connecting.value = false; _room.value = null
                errors.tryEmit(if (wasInRoom) "Connection lost" else "Could not connect to server")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws || intentionalClose) return
                _connecting.value = false; _room.value = null
            }
        })
    }

    private fun members(a: org.json.JSONArray?) = a.mapObjects {
        Member(it.getString("id"), it.optString("uid"), it.optString("name"), it.optString("handle"), it.optString("avatar"), it.optBoolean("host"), it.optBoolean("mic"))
    }
    private fun settings(o: JSONObject?) = RoomSettings(o?.optString("privacy", "public") ?: "public",
        o?.optString("playback", "vote") ?: "vote", o?.optBoolean("voice", true) ?: true,
        o?.optBoolean("locked", false) ?: false, o?.optBoolean("approval", false) ?: false, o?.optBoolean("chat", true) ?: true, o?.optInt("max", 100) ?: 100, o?.optBoolean("passcode", false) ?: false)
    private fun queue(a: org.json.JSONArray?) = a.mapObjects {
        QueueItem(it.getString("id"), it.optString("url"), it.optString("title"), it.optString("thumb"), it.optString("by"), it.optInt("votes"))
    }

    private fun handle(o: JSONObject) {
        when (o.getString("type")) {
            "joined" -> {
                val st = o.getJSONObject("state")
                if (!keepChat) _chat.value = emptyList()
                keepChat = false; attempts = 0
                _queue.value = queue(o.optJSONArray("queue"))
                _room.value = RoomState(
                    code = o.getString("code"), myId = o.getString("id"), hostId = o.getString("hostId"),
                    url = st.optString("url"), title = st.optString("title"), thumb = st.optString("thumb"),
                    playing = st.optBoolean("playing"), position = st.optDouble("position", 0.0),
                    members = members(o.optJSONArray("members")), settings = settings(o.optJSONObject("settings")), version = 1,
                )
                _connecting.value = false
            }
            "state" -> _room.update {
                it?.copy(url = o.optString("url"), title = o.optString("title"), thumb = o.optString("thumb"),
                    playing = o.optBoolean("playing"), position = o.optDouble("position", 0.0), version = it.version + 1)
            }
            "members" -> _room.update { it?.copy(hostId = o.getString("hostId"), members = members(o.optJSONArray("members"))) }
            "settings" -> _room.update { it?.copy(settings = settings(o.optJSONObject("settings"))) }
            "queue" -> _queue.value = queue(o.optJSONArray("queue"))
            "chat" -> _chat.update { (it + ChatMsg(o.optString("from"), o.optString("uid"), o.optString("handle"), o.optString("avatar"), o.getString("text"), o.optLong("ts"))).takeLast(300) }
            "system" -> _chat.update { (it + ChatMsg("", "", "", "", o.getString("text"), System.currentTimeMillis(), true)).takeLast(300) }
            "reaction" -> reactions.tryEmit(Reaction(o.optString("from"), o.getString("emoji"), counter++))
            "rtc" -> rtc.tryEmit(o.getString("from") to o.getJSONObject("data"))
            "pending" -> errors.tryEmit("Waiting for the host to let you in…")
            "join_request" -> _requests.update { l -> l.filter { it.rid != o.getString("rid") } + JoinRequest(o.getString("rid"), o.optString("name"), o.optString("handle"), o.optString("avatar")) }
            "join_request_cancel" -> _requests.update { l -> l.filter { it.rid != o.optString("rid") } }
            "closed" -> { errors.tryEmit("The host ended this room"); close() }
            "kicked" -> { errors.tryEmit("You were removed from the room"); close() }
            "error" -> {
                errors.tryEmit(o.optString("message", "Error"))
                if (o.optString("code") == "passcode") passcodeRequired.tryEmit(Unit)
                _connecting.value = false
                if (_room.value == null) { ws?.close(1000, null); ws = null }
            }
        }
    }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    /** Host playback reports: play / pause / seek / ended. */
    fun control(action: String, position: Double) =
        if (action == "ended") send(JSONObject().put("type", "ended"))
        else send(JSONObject().put("type", "control").put("action", action).put("position", position))
    fun pick(p: Pick, enqueue: Boolean = false) = send(JSONObject().put("type", "pick").put("url", p.url)
        .put("title", p.title).put("thumb", p.thumb).put("enqueue", enqueue))
    fun vote(id: String) = send(JSONObject().put("type", "vote").put("id", id))
    fun settings(privacy: String? = null, playback: String? = null, voice: Boolean? = null,
                 locked: Boolean? = null, approval: Boolean? = null, chat: Boolean? = null, max: Int? = null, passcode: String? = null) = send(JSONObject().apply {
        put("type", "settings"); privacy?.let { put("privacy", it) }; playback?.let { put("playback", it) }; voice?.let { put("voice", it) }
        locked?.let { put("locked", it) }; approval?.let { put("approval", it) }; chat?.let { put("chat", it) }; max?.let { put("max", it) }; passcode?.let { put("passcode", it) }
    })
    fun approve(rid: String) { send(JSONObject().put("type", "approve").put("rid", rid)); _requests.update { l -> l.filter { it.rid != rid } } }
    fun deny(rid: String) { send(JSONObject().put("type", "deny").put("rid", rid)); _requests.update { l -> l.filter { it.rid != rid } } }
    fun mute(id: String, minutes: Int) = send(JSONObject().put("type", "mute").put("id", id).put("minutes", minutes))
    fun transfer(id: String) = send(JSONObject().put("type", "transfer").put("id", id))
    fun closeRoom() = send(JSONObject().put("type", "close"))
    fun chat(text: String) = send(JSONObject().put("type", "chat").put("text", text))
    fun react(emoji: String) = send(JSONObject().put("type", "reaction").put("emoji", emoji))
    fun mic(on: Boolean) = send(JSONObject().put("type", "mic").put("on", on))
    fun rtc(to: String, data: JSONObject) = send(JSONObject().put("type", "rtc").put("to", to).put("data", data))
    fun unplayable(url: String, reason: String) = send(JSONObject().put("type", "unplayable").put("url", url).put("reason", reason))
    fun kick(id: String) = send(JSONObject().put("type", "kick").put("id", id))

    fun close() {
        intentionalClose = true; handler.removeCallbacksAndMessages(null)
        val w = ws; ws = null
        w?.close(1000, null)
        _room.value = null; _chat.value = emptyList(); _queue.value = emptyList(); _requests.value = emptyList(); _connecting.value = false
    }
}

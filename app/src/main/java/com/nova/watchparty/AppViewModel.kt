package com.nova.watchparty

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nova.watchparty.net.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

enum class SourceMode { Start, Pick }

sealed interface Screen {
    object Splash : Screen
    object Onboarding : Screen
    object SignIn : Screen
    object EmailEntry : Screen
    data class EmailCode(val email: String, val devCode: String?) : Screen
    object Terms : Screen
    object Setup : Screen
    object Home : Screen
    data class Sources(val mode: SourceMode) : Screen
    object Room : Screen
    object Profile : Screen
    object Settings : Screen
    object Friends : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("nova", Context.MODE_PRIVATE)
    val client = RoomClient()
    val room = client.room
    val chat = client.chat
    val queue = client.queue
    val connecting = client.connecting
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val stack = mutableStateListOf<Screen>(Screen.Splash)
    val screen: Screen get() = stack.last()
    var user by mutableStateOf<User?>(null)
    var busy by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    var rooms by mutableStateOf<List<RoomCard>>(emptyList())
    var roomsLoaded by mutableStateOf(false)
    var micOn by mutableStateOf(false)
    var pendingRequests by mutableIntStateOf(0)
    /** Set when a room asked for a passcode; the Home screen shows the prompt. */
    var passcodeFor by mutableStateOf<String?>(null)
    private var lastJoin = ""
    private var voice: VoiceChat? = null
    var pendingJoin: String? = null
    var pendingQueue: List<Pick> = emptyList()

    var server: String
        get() = prefs.getString("server", BuildConfig.DEFAULT_SERVER_URL) ?: BuildConfig.DEFAULT_SERVER_URL
        set(v) { prefs.edit().putString("server", v.trim()).apply(); Backend.wsUrl = v.trim() }
    private var seenOnboarding: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) { prefs.edit().putBoolean("onboarded", v).apply() }
    private var acceptedTerms: Boolean
        get() = prefs.getBoolean("terms", false)
        set(v) { prefs.edit().putBoolean("terms", v).apply() }

    init {
        Backend.wsUrl = server
        Backend.token = prefs.getString("token", "") ?: ""
        viewModelScope.launch {
            val start = System.currentTimeMillis()
            var u: User? = null
            if (Backend.token.isNotBlank()) u = try { Backend.me() } catch (e: ApiError) {
                if (e.message == "Not signed in") Backend.token = ""
                null
            }
            delay((1000 - (System.currentTimeMillis() - start)).coerceAtLeast(0))
            if (u != null) { user = u; reset(Screen.Home); registerPushIfNeeded(); consumePendingJoin() }
            else reset(if (seenOnboarding) Screen.SignIn else Screen.Onboarding)
        }
        viewModelScope.launch {
            var inRoom = false
            room.collect { r ->
                if (r != null && !inRoom) {
                    inRoom = true
                    stack.add(Screen.Room)
                    if (pendingQueue.isNotEmpty()) { val q = pendingQueue; pendingQueue = emptyList(); q.forEach { client.pick(it, enqueue = true) } }
                } else if (r == null && inRoom) {
                    inRoom = false; stopVoice()
                    stack.removeAll { it is Screen.Room || it is Screen.Sources }
                    if (stack.isEmpty()) stack.add(Screen.Home)
                    refreshRooms("")
                }
            }
        }
        viewModelScope.launch { client.errors.collect { toasts.emit(it) } }
        viewModelScope.launch { client.passcodeRequired.collect { if (lastJoin.isNotBlank()) passcodeFor = lastJoin } }
        // voice: keep WebRTC connections in step with the member list, and route signaling
        viewModelScope.launch {
            room.collect { r ->
                val v = voice ?: return@collect
                if (r == null) return@collect
                v.sync(r.members.map { it.id }.filter { it != r.myId }.toSet(), r.members.filter { it.mic }.map { it.id }.toSet())
            }
        }
        viewModelScope.launch {
            client.rtc.collect { (from, data) -> ensureVoice().onSignal(from, data) }
        }
    }

    // ---- navigation
    fun go(s: Screen) { stack.add(s) }
    fun reset(s: Screen) { stack.clear(); stack.add(s) }
    fun back() {
        if (screen is Screen.Room) { leave(); return }
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
    fun finishOnboarding() { seenOnboarding = true; reset(if (user != null) Screen.Home else Screen.SignIn) }

    // ---- auth
    private inline fun launchBusy(crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try { block() } catch (e: ApiError) { toasts.emit(e.message ?: "Something went wrong") } finally { busy = false }
        }
    }

    private fun onAuth(r: AuthResult) {
        Backend.token = r.token; prefs.edit().putString("token", r.token).apply()
        user = r.user
        needSetup = r.newUser
        when {
            !acceptedTerms -> reset(Screen.Terms)
            r.newUser -> reset(Screen.Setup)
            else -> { reset(Screen.Home); registerPushIfNeeded(); consumePendingJoin() }
        }
    }
    private var needSetup = false

    fun acceptTerms() { acceptedTerms = true; if (needSetup) reset(Screen.Setup) else { reset(Screen.Home); consumePendingJoin() } }

    fun requestCode(email: String) = launchBusy {
        val dev = Backend.requestCode(email.trim())
        if (screen is Screen.EmailCode) stack.removeAt(stack.lastIndex)
        go(Screen.EmailCode(email.trim(), dev))
    }
    fun verify(email: String, code: String) = launchBusy { onAuth(Backend.verify(email, code.trim())) }
    fun guestSignIn(name: String) = launchBusy { onAuth(Backend.guest(name.ifBlank { "Guest" })) }
    fun googleSignIn(idToken: String) = launchBusy { onAuth(Backend.google(idToken)) }

    fun signOut() {
        client.close(); Backend.token = ""; prefs.edit().remove("token").apply(); user = null; reset(Screen.SignIn)
    }
    fun deleteAccount() = launchBusy {
        Backend.deleteMe(); signOut()
        toasts.emit("Account deleted")
    }

    // ---- profile
    fun saveProfile(name: String, handle: String, bio: String? = null, setupDone: Boolean = false, onDone: () -> Unit = {}) = launchBusy {
        val patch = JSONObject().put("name", name).put("handle", handle)
        bio?.let { patch.put("bio", it) }
        if (setupDone) patch.put("setup", true)
        user = Backend.updateMe(patch)
        onDone()
    }
    fun setFlags(f: Flags) {
        val u = user ?: return
        user = u.copy(flags = f)
        viewModelScope.launch {
            try {
                Backend.updateMe(JSONObject().put("flags", JSONObject().put("quickReaction", f.quickReaction)
                    .put("restrictInvites", f.restrictInvites).put("hideMature", f.hideMature).put("floatingPlayer", f.floatingPlayer)
                    .put("autoTranslate", f.autoTranslate).put("hideLocation", f.hideLocation)
                    .put("hidePresence", f.hidePresence).put("privateActivity", f.privateActivity).put("notifyInvites", f.notifyInvites).put("notifyFriends", f.notifyFriends)))
            } catch (e: ApiError) { toasts.emit(e.message ?: "Could not save") }
        }
    }
    fun uploadAvatar(ctx: Context, uri: Uri) = launchBusy {
        val bytes = withContext(Dispatchers.IO) {
            val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: throw ApiError("Could not read image")
            val side = minOf(src.width, src.height)
            val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(sq, 512, 512, true)
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        }
        user = Backend.uploadAvatar(bytes)
    }
    suspend fun suggestHandles(name: String): List<String> = try { Backend.suggestHandles(name) } catch (e: ApiError) { emptyList() }

    // ---- social: friends, moderation, photos
    fun refreshBadge() { viewModelScope.launch { try { pendingRequests = Backend.friendsState().incoming.size } catch (_: ApiError) {} } }
    fun requestFriend(handle: String) = launchBusy {
        toasts.emit(if (Backend.requestFriend(handle) == "friends") "You are now friends" else "Friend request sent")
    }
    fun block(id: String) = launchBusy { Backend.block(id); toasts.emit("User blocked") }
    fun report(id: String, reason: String) = launchBusy { Backend.report(id, room.value?.code, reason); toasts.emit("Thanks. We'll review your report.") }

    private suspend fun compressJpeg(ctx: Context, uri: Uri, maxSide: Int): ByteArray = withContext(Dispatchers.IO) {
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: throw ApiError("Could not read image")
        val k = maxSide.toFloat() / maxOf(src.width, src.height)
        val bmp = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true) else src
        var q = 85
        var out: ByteArray
        do { out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray(); q -= 10 } while (out.size > 450_000 && q >= 35)
        if (out.size > 550_000) throw ApiError("Image is too large")
        out
    }
    fun sendPhoto(ctx: Context, uri: Uri) = launchBusy { client.chat("img:" + Backend.uploadChatImage(compressJpeg(ctx, uri, 960))) }
    fun addGalleryPhoto(ctx: Context, uri: Uri) = launchBusy { user = Backend.uploadGallery(compressJpeg(ctx, uri, 960)) }
    fun removeGalleryPhoto(path: String) = launchBusy { user = Backend.deleteGallery(path) }
    fun saveExtras(channels: List<String>, socials: Map<String, String>) = launchBusy { user = Backend.updateExtras(channels, socials); toasts.emit("Saved") }

    // ---- voice
    private fun ensureVoice(): VoiceChat = voice ?: VoiceChat(getApplication()) { to, d -> client.rtc(to, d) }.also { voice = it }
    /** Caller must already hold RECORD_AUDIO permission. */
    fun setMic(on: Boolean) {
        val r = room.value ?: return
        if (on && !r.settings.voice) { viewModelScope.launch { toasts.emit("Voice chat is turned off in this room") }; return }
        micOn = on
        client.mic(on)   // announce first so listeners expect the offer
        ensureVoice().setSpeaking(on, r.members.map { it.id }.filter { it != r.myId }.toSet())
    }
    private fun stopVoice() { micOn = false; voice?.release(); voice = null }

    // ---- location & push
    fun shareLocation(lat: Double?, lon: Double?) { viewModelScope.launch { try { Backend.sendLocation(lat, lon) } catch (_: ApiError) {} } }
    fun registerPushIfNeeded() {
        if (user == null) return
        try {
            if (com.google.firebase.FirebaseApp.getApps(getApplication()).isEmpty()) return
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
                viewModelScope.launch { try { Backend.registerPush(t) } catch (_: ApiError) {} }
            }
        } catch (_: Exception) {}
    }
    fun inviteFriend(f: Friend) {
        val code = room.value?.code ?: return
        viewModelScope.launch {
            try { toasts.emit(if (Backend.inviteFriend(code, f.id)) "Invite sent to ${f.name}" else "${f.name} can't get push notifications yet. Share the link instead.") }
            catch (e: ApiError) { toasts.emit(e.message ?: "Could not invite") }
        }
    }
    fun saveInterests(list: List<String>) { viewModelScope.launch { try { user = Backend.setInterests(list) } catch (e: ApiError) { toasts.emit(e.message ?: "Failed") } } }

    // ---- rooms
    fun refreshRooms(q: String) {
        viewModelScope.launch {
            try { rooms = Backend.publicRooms(q); roomsLoaded = true } catch (_: ApiError) { roomsLoaded = true }
        }
    }
    private fun hello(type: String) = JSONObject().put("type", type).put("token", Backend.token)
    /** Pre-flight for YouTube picks: tell the user right away if the owner blocks playback outside YouTube. */
    private suspend fun playable(p: Pick): Boolean {
        if (!p.url.startsWith("yt:")) return true
        val (ok, why) = Backend.ytCheck(p.url.removePrefix("yt:"))
        if (!ok) toasts.emit(if (why == "not_found") "That video is unavailable." else "That video's owner doesn't allow it to play outside YouTube. Pick another one.")
        return ok
    }
    fun createRoom(p: Pick?) {
        viewModelScope.launch {
            val usable = if (p != null && !playable(p)) null else p
            client.open(server, hello("create").put("url", usable?.url ?: "").put("title", usable?.title ?: "")
                .put("thumb", usable?.thumb ?: "").put("privacy", "public"))
            usable?.let { addHistory(it) }
        }
    }
    fun joinRoom(code: String, passcode: String? = null) {
        lastJoin = code.trim().uppercase()
        client.open(server, hello("join").put("code", lastJoin).apply { if (!passcode.isNullOrBlank()) put("passcode", passcode) })
    }
    fun signOutOtherDevices() { viewModelScope.launch { try { val n = Backend.logoutOthers(); toasts.emit(if (n > 0) "Signed out of $n other device(s)" else "No other devices are signed in") } catch (e: ApiError) { toasts.emit(e.message ?: "Failed") } } }
    fun leave() { stopVoice(); client.close() }
    fun consumePendingJoin() { pendingJoin?.let { pendingJoin = null; joinRoom(it) } }
    fun handleDeepLink(code: String?) {
        if (code.isNullOrBlank()) return
        if (user == null) pendingJoin = code else joinRoom(code)
    }

    fun pick(p: Pick) { viewModelScope.launch { if (playable(p)) { client.pick(p); addHistory(p) } } }
    /** Playlists: the first embeddable video plays now, the rest are queued (the server auto-skips any that fail later). */
    fun pickMany(list: List<Pick>) {
        if (list.isEmpty()) return
        viewModelScope.launch {
            val isHost = room.value?.isHost == true
            val rest = list.toMutableList()
            var tries = 0
            while (rest.isNotEmpty() && tries < 5 && !playable(rest.first())) { rest.removeAt(0); tries++ }
            val first = rest.firstOrNull() ?: return@launch
            client.pick(first); addHistory(first)
            rest.drop(1).forEach { client.pick(it, enqueue = isHost) }
        }
    }
    fun enqueue(p: Pick) { viewModelScope.launch { if (playable(p)) client.pick(p, enqueue = true) } }
    fun addHistory(p: Pick) { viewModelScope.launch { try { Backend.addSaved("history", p) } catch (_: ApiError) {} } }
    fun like(p: Pick) {
        viewModelScope.launch {
            try { Backend.addSaved("likes", p); toasts.emit("Added to Likes") } catch (e: ApiError) { toasts.emit(e.message ?: "Failed") }
        }
    }

    fun inviteLink(code: String): String {
        val b = BuildConfig.INVITE_BASE_URL.ifBlank { Backend.base }.trimEnd('/')
        return "$b/r/$code"
    }

    override fun onCleared() { stopVoice(); client.close() }
}

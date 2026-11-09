package com.nova.watchparty.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.animation.core.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.nova.watchparty.*
import com.nova.watchparty.net.Reaction
import com.nova.watchparty.net.RoomState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Panel { None, Invite, Settings }

private val PRIVACY = listOf(
    Triple("public", "PUBLIC", Icons.Default.Public), Triple("nearby", "NEARBY", Icons.Default.LocationOn),
    Triple("friends", "FRIENDS ONLY", Icons.Default.Group), Triple("invite", "INVITE ONLY", Icons.Default.Lock),
)
private val PLAYBACK = listOf(
    Triple("vote", "LET'S VOTE", Icons.Default.HowToVote), Triple("play", "JUST PLAY", Icons.Default.PlayArrow),
    Triple("autoplay", "AUTOPLAY", Icons.Default.FastForward), Triple("leader", "LEADER'S CHOICE", Icons.Default.EmojiEvents),
)
private val EMOJI_SET = ("😀😃😄😁😆😅😂🤣🥲😊😇🙂😉😍🥰😘😋😛😜🤪😎🤩🥳😏😒😞😔😟😕🙁😣😖😫😩🥺😢😭😤😠😡🤬🤯😳🥵🥶😱😨😰😥😓🤗🤔🤭🤫😶😐😑😬🙄😯😦😮😲🥱😴🤤😪😵🤐🥴🤢🤮🤧😷🤒🤕🤑🤠😈👿💀👻👽🤖💩" +
    "👍👎👏🙌🤝🙏💪👌✌️🤞🤟🤘👋🤙👀💃🕺🎉🎊🎵🎶🔥✨💯💥❤️🧡💛💚💙💜🖤🤍💔💕💖🍿🎬🎤🎧🏆🌹☕🍕🍔").let { str ->
    val out = ArrayList<String>(); var i = 0
    while (i < str.length) { val cp = str.codePointAt(i); var n = Character.charCount(cp)
        while (i + n < str.length && (str[i + n] == '\uFE0F' || str[i + n] == '\u200D')) n++
        out.add(str.substring(i, i + n)); i += n }
    out
}
private val REACTIONS = listOf("😂", "😮", "❤️", "👏", "🔥", "😢", "🎉", "👍")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RoomScreen(vm: AppViewModel) {
    val room by vm.room.collectAsState()
    val r = room ?: return
    val ctx = LocalContext.current
    val chat by vm.chat.collectAsState()
    val queue by vm.queue.collectAsState()
    val me = vm.user
    var text by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf(if (r.isHost) Panel.Invite else Panel.None) }
    var showMembers by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showGif by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val requests by vm.client.requests.collectAsState()
    var mentionMenu by remember { mutableStateOf(false) }
    var userSheet by remember { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.sendPhoto(ctx, it); showGif = false } }
    val floating = remember { mutableStateListOf<Reaction>() }
    val listState = rememberLazyListState()
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    LaunchedEffect(Unit) { try { friends = com.nova.watchparty.net.Backend.friends() } catch (_: Exception) {} }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.setMic(true) else Toast.makeText(ctx, "Microphone permission is needed for voice chat", Toast.LENGTH_SHORT).show()
    }
    val flags = me?.flags ?: Flags()
    val link = vm.inviteLink(r.code)
    val shareText = "Join me on Nova Watch Party! $link  (room code ${r.code})"

    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(Unit) { vm.client.reactions.collect { floating.add(it) } }
    LaunchedEffect(chat.size) { if (chat.isNotEmpty()) listState.animateScrollToItem(chat.lastIndex) }
    BackHandler2(enabled = true) { if (panel != Panel.None) panel = Panel.None else showLeave = true }

    val player: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(Color.Black)) {
            when {
                r.url.startsWith("yt:") -> YouTubeSync(r, { a, p -> vm.client.control(a, p) }, { u, why -> vm.client.unplayable(u, why) }, Modifier.fillMaxSize())
                r.url.startsWith("web:") -> WebSync(r, { a, p -> vm.client.control(a, p) }, Modifier.fillMaxSize())
                r.url.isNotBlank() -> ExoSynced(r, { a, p -> vm.client.control(a, p) }, Modifier.fillMaxSize())
            }
            if (r.url.isBlank()) Text(if (r.isHost) "Tap the magnifier to pick a video" else "Waiting for the host to pick a video…",
                Modifier.align(Alignment.Center).padding(16.dp), color = SubtleText, textAlign = TextAlign.Center)
            floating.forEach { rc -> key(rc.key) { FloatingEmoji(rc) { floating.remove(rc) } } }
        }
    }

    if (vm.inPip) { player(Modifier.fillMaxSize()); return }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // top bar
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ showLeave = true }) { Icon(Icons.Default.Close, "Leave", tint = Color.White) }
                IconButton({ panel = if (panel == Panel.Settings) Panel.None else Panel.Settings }) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
                Box(Modifier.weight(1f), Alignment.Center) { NovaLogo(28.dp) }
                IconButton({ vm.go(Screen.Sources(SourceMode.Pick)) }) { Icon(Icons.Default.Search, "Pick a video", tint = Color.White) }
                IconButton({ showMembers = !showMembers }) {
                    BadgedBox({ Badge(containerColor = NovaCoral) { Text(r.members.size.toString()) } }) { Icon(Icons.Default.Group, "People", tint = Color.White) }
                }
            }
            player(Modifier.fillMaxWidth().aspectRatio(16f / 9f))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                    contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(chat) { m ->
                        if (m.system) Text(m.text, fontSize = 12.sp, color = SubtleText, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                        else {
                            val mine = me != null && m.handle == me.handle
                            Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { if (!mine && m.handle.isNotBlank()) userSheet = m.handle }, onDoubleClick = { vm.client.react(me?.flags?.quickReaction ?: "❤️") }),
                                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
                                if (!mine) { Avatar(m.avatar, m.from, 30.dp, Modifier.clickable { if (m.handle.isNotBlank()) userSheet = m.handle }); Spacer(Modifier.width(8.dp)) }
                                Column(Modifier.widthIn(max = 280.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                                    Text(m.from, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    ChatText(m.text)
                                    if (flags.autoTranslate && !mine && !isGifMessage(m.text) && !isImageMessage(m.text)) TranslatedLine(m.text)
                                }
                                if (mine) { Spacer(Modifier.width(8.dp)); Avatar(m.avatar, m.from, 30.dp) }
                            }
                        }
                    }
                }
                when (panel) {
                    Panel.Invite -> InvitePanel(r, link, shareText, friends, onInvite = { vm.inviteFriend(it) }, onPrivacy = { vm.client.settings(privacy = it) })
                    Panel.Settings -> SettingsPanel(r, flags, onSettings = { p, b -> vm.client.settings(privacy = p, playback = b) }, onVoice = { vm.client.settings(voice = it) }, onFlags = { l, a, c, m -> vm.client.settings(locked = l, approval = a, chat = c, max = m) }, onPasscode = { vm.client.settings(passcode = it) }, onTranslate = { vm.setFlags(flags.copy(autoTranslate = it)) }, onClose = { panel = Panel.None })
                    Panel.None -> {}
                }
            }

            // now playing
            if (r.title.isNotBlank()) Row(Modifier.fillMaxWidth().background(Color.Black.copy(0.35f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r.thumb.isNotBlank()) AsyncImage(r.thumb, null, Modifier.size(width = 44.dp, height = 28.dp).clip(RoundedCornerShape(3.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Now playing", fontSize = 10.sp, color = SubtleText)
                    Text(r.title, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ vm.like(Pick(r.url, r.title, r.thumb)) }) { Icon(Icons.Default.FavoriteBorder, "Like", tint = Color.White) }
                IconButton({ showQueue = true }) {
                    BadgedBox({ if (queue.isNotEmpty()) Badge(containerColor = NovaCoral) { Text("${queue.size}") } }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Up next", tint = Color.White) }
                }
            }
            // chat bar
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(0.45f)).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(if (vm.micOn) NovaCoral else Color.White).clickable {
                    if (vm.micOn) vm.setMic(false)
                    else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.setMic(true)
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }, Alignment.Center) {
                    Icon(if (vm.micOn) Icons.Default.Mic else Icons.Default.MicOff, if (vm.micOn) "Mute" else "Talk", tint = if (vm.micOn) Color.White else Color.Black)
                }
                Spacer(Modifier.width(10.dp))
                val send = { if (text.isNotBlank()) { vm.client.chat(text.trim()); text = "" } }
                BasicTextField(text, { text = it.take(500) }, singleLine = true, modifier = Modifier.weight(1f),
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner -> if (text.isEmpty()) Text("Chat", color = Color.White.copy(0.6f), fontSize = 15.sp); inner() })
                Box {
                    IconButton({ mentionMenu = true }, Modifier.size(34.dp)) { Icon(Icons.Default.AlternateEmail, "Mention", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    DropdownMenu(mentionMenu, { mentionMenu = false }) {
                        r.members.filter { it.handle != me?.handle }.forEach { m -> DropdownMenuItem({ Text("@${m.handle}") }, { text += "@${m.handle} "; mentionMenu = false }) }
                    }
                }
                IconButton({ showGif = true }, Modifier.size(34.dp)) { Icon(Icons.Default.Image, "GIFs and reactions", tint = Color.White, modifier = Modifier.size(20.dp)) }
                IconButton({ panel = if (panel == Panel.Invite) Panel.None else Panel.Invite }, Modifier.size(34.dp)) { Icon(Icons.Default.PersonAdd, "Invite", tint = Color.White, modifier = Modifier.size(20.dp)) }
                IconButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) }, "Invite friends")) }, Modifier.size(34.dp)) {
                    Icon(Icons.Default.Share, "Share", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                IconButton({
                    if (r.isHost) { val order = PRIVACY.map { it.first }; vm.client.settings(privacy = order[(order.indexOf(r.settings.privacy) + 1) % order.size]) }
                    else Toast.makeText(ctx, "Only the host can change who can join", Toast.LENGTH_SHORT).show()
                }, Modifier.size(34.dp)) { Icon(PRIVACY.first { it.first == r.settings.privacy }.third, "Privacy", tint = Color.White, modifier = Modifier.size(20.dp)) }
            }
        }

        // members slide-over
        if (showMembers) Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showMembers = false }) {
            Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(270.dp).background(Color.Black.copy(0.82f)).statusBarsPadding().padding(top = 60.dp)) {
                Text("In this room · ${r.members.size}", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
                LazyColumn {
                    items(r.members, key = { it.id }) { m ->
                        Row(Modifier.fillMaxWidth().clickable { showMembers = false; userSheet = m.handle }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(m.avatar, m.name, 38.dp); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.name + if (m.id == r.myId) " (you)" else "", color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("@${m.handle}", color = SubtleText, fontSize = 11.sp, maxLines = 1)
                            }
                            if (m.mic) Icon(Icons.Default.Mic, "Speaking", tint = NovaTeal, modifier = Modifier.padding(end = 6.dp))
                            if (m.host) Icon(Icons.Default.WorkspacePremium, "Host", tint = NovaGold)
                            else if (r.isHost) Box {
                                IconButton({ menuFor = m.id }) { Icon(Icons.Default.MoreVert, "Actions for ${m.name}", tint = SubtleText) }
                                DropdownMenu(menuFor == m.id, { menuFor = null }) {
                                    DropdownMenuItem({ Text("Make host") }, { vm.client.transfer(m.id); menuFor = null })
                                    DropdownMenuItem({ Text("Mute chat 10 min") }, { vm.client.mute(m.id, 10); menuFor = null })
                                    DropdownMenuItem({ Text("Unmute chat") }, { vm.client.mute(m.id, 0); menuFor = null })
                                    DropdownMenuItem({ Text("Remove and block from room") }, { vm.client.kick(m.id); menuFor = null })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    userSheet?.let { h -> UserSheet(vm, h, r.code) { userSheet = null } }

    if (showLeave) AlertDialog(onDismissRequest = { showLeave = false }, containerColor = Color(0xFF1C1838),
        title = { Text("Leaving?", color = Color.White) },
        confirmButton = { TextButton({ showLeave = false; vm.leave() }) { Text("YES") } },
        text = { if (r.isHost) TextButton({ showLeave = false; vm.client.closeRoom() }) { Text("End room for everyone") } },
        dismissButton = { TextButton({ showLeave = false }) { Text("NO") } })

    if (r.isHost) requests.firstOrNull()?.let { q ->
        AlertDialog(onDismissRequest = {}, containerColor = Color(0xFF1C1838),
            title = { Text("Join request", color = Color.White) },
            text = { Text("${q.name} (@${q.handle}) wants to join.", color = Color.White) },
            confirmButton = { TextButton({ vm.client.approve(q.rid) }) { Text("APPROVE") } },
            dismissButton = { TextButton({ vm.client.deny(q.rid) }) { Text("DENY") } })
    }

    if (showGif) ModalBottomSheet({ showGif = false }, containerColor = Color(0xFF1C1838)) {
        var tab by remember { mutableIntStateOf(0) }
        Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 24.dp)) {
            TabRow(tab, containerColor = Color.Transparent) {
                Tab(tab == 0, { tab = 0 }, text = { Text("GIFs") }); Tab(tab == 1, { tab = 1 }, text = { Text("Reactions") }); Tab(tab == 2, { tab = 2 }, text = { Text("Photo") }); Tab(tab == 3, { tab = 3 }, text = { Text("Emoji") })
            }
            Spacer(Modifier.height(10.dp))
            when (tab) {
                0 -> GifPane { vm.client.chat("gif:$it"); showGif = false }
                2 -> Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Share a photo with the room (kept for 24 hours)", color = SubtleText, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    PillButton("Choose photo", { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                }
                3 -> LazyColumn(Modifier.height(260.dp)) {
                    items(EMOJI_SET.chunked(8)) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { e -> Text(e, fontSize = 28.sp, modifier = Modifier.clickable { text += e }.padding(6.dp)) }
                        }
                    }
                }
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 14.dp)) {
                    items(REACTIONS) { e -> Text(e, fontSize = 34.sp, modifier = Modifier.clickable { vm.client.react(e); showGif = false }) }
                }
            }
        }
    }

    if (showQueue) ModalBottomSheet({ showQueue = false }, containerColor = Color(0xFF1C1838)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Up next", color = Color.White, fontSize = 18.sp)
            Text(when (r.settings.playback) { "vote" -> "Voting: the most-voted video plays next"; "autoplay" -> "Autoplay: videos play in order"
                "play" -> "Anyone's pick plays right away"; else -> "Only the host picks videos" }, color = SubtleText, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (queue.isEmpty()) Text("Nothing queued yet.", color = SubtleText, modifier = Modifier.padding(vertical = 18.dp))
            queue.forEach { q ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(q.thumb, null, Modifier.size(width = 80.dp, height = 45.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(q.title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("added by ${q.by}", color = SubtleText, fontSize = 11.sp)
                    }
                    TextButton({ vm.client.vote(q.id) }) { Icon(Icons.Default.ThumbUp, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("${q.votes}") }
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Add video", { showQueue = false; vm.go(Screen.Sources(SourceMode.Pick)) })
                if (r.isHost) PillButton("Skip", { vm.client.control("ended", 0.0); showQueue = false })
            }
        }
    }
}

@Composable
private fun BackHandler2(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

@Composable
private fun IconChoice(label: String, icon: ImageVector, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.width(76.dp).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.5f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (selected) Color.White else Color.Transparent), Alignment.Center) {
            Icon(icon, label, tint = if (selected) Color.Black else Color.White.copy(0.75f), modifier = Modifier.size(28.dp))
        }
        Text(label, fontSize = 9.sp, color = if (selected) Color.White else Color.White.copy(0.65f), textAlign = TextAlign.Center, lineHeight = 11.sp, maxLines = 2)
    }
}

@Composable
private fun PrivacyChoices(r: RoomState, onPrivacy: (String) -> Unit) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
    PRIVACY.forEach { (k, label, icon) -> IconChoice(label, icon, r.settings.privacy == k, enabled = r.isHost) { onPrivacy(k) } }
}

@Composable
private fun InvitePanel(r: RoomState, link: String, shareText: String, friends: List<Friend>, onInvite: (Friend) -> Unit, onPrivacy: (String) -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val apps = remember {
        listOf("Messages" to "com.google.android.apps.messaging", "WhatsApp" to "com.whatsapp", "Telegram" to "org.telegram.messenger", "Snapchat" to "com.snapchat.android")
            .filter { pm.getLaunchIntentForPackage(it.second) != null }
    }
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(0.5f)).padding(vertical = 10.dp)) {
        Text(if (r.isHost) "Who should be able to join this nova?" else "Anyone with the link can join this nova", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        PrivacyChoices(r, onPrivacy)
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Color.White.copy(0.15f))
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Link, null, tint = Color.White); Spacer(Modifier.width(10.dp))
            Text("Invite link: ", color = Color.White, fontSize = 13.sp)
            Text(link, color = Color(0xFF8AB4FF), fontSize = 13.sp, textDecoration = TextDecoration.Underline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", link))
                Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
            })
        }
        Text("Invite friends to watch with you!", color = SubtleText, fontSize = 12.sp, modifier = Modifier.padding(start = 48.dp, top = 6.dp, bottom = 8.dp))
        if (friends.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            items(friends, key = { it.id }) { f ->
                Column(Modifier.width(60.dp).clickable { onInvite(f) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(f.avatar, f.name, 40.dp); Text(f.name, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(apps) { (label, pkg) ->
                Column(Modifier.width(64.dp).clickable {
                    ctx.startActivity(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; setPackage(pkg); putExtra(Intent.EXTRA_TEXT, shareText) })
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    val bmp = remember(pkg) { try { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null } }
                    bmp?.let { Image(it, label, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) }
                    Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1)
                }
            }
            item {
                Column(Modifier.width(64.dp).clickable { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) }, "Invite friends")) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(0.15f)), Alignment.Center) { Icon(Icons.Default.MoreHoriz, "More", tint = Color.White) }
                    Text("More", color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(r: RoomState, flags: Flags, onSettings: (String?, String?) -> Unit, onVoice: (Boolean) -> Unit, onFlags: (Boolean?, Boolean?, Boolean?, Int?) -> Unit, onPasscode: (String) -> Unit, onTranslate: (Boolean) -> Unit, onClose: () -> Unit) {
    var pcDialog by remember { mutableStateOf(false) }
    var pc by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) }
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(0.65f), Color.Black.copy(0.35f)))).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White); Spacer(Modifier.width(6.dp)); Text("SETTINGS", color = Color.White, fontSize = 12.sp)
        }
        SettingRow("PRIVACY", "Who can join this nova") { PRIVACY.forEach { (k, l, i) -> IconChoice(l, i, r.settings.privacy == k, r.isHost) { onSettings(k, null) } } }
        SettingRow("PLAYBACK", when (r.settings.playback) { "vote" -> "Voting occurs at the end of the video"; "play" -> "Anyone's pick plays immediately"
            "autoplay" -> "Queued videos play automatically"; else -> "Only the host chooses" }) {
            PLAYBACK.forEach { (k, l, i) -> IconChoice(l, i, r.settings.playback == k, r.isHost) { onSettings(null, k) } }
        }
        SettingRow("VOICE", if (r.settings.voice) "Microphones enabled" else "Microphones disabled") {
            IconChoice("ON", Icons.Default.Mic, r.settings.voice, r.isHost) { onVoice(true) }; IconChoice("OFF", Icons.Default.MicOff, !r.settings.voice, r.isHost) { onVoice(false) }
        }
        SettingRow("LOCK ROOM", if (r.settings.locked) "No new people can join" else "People can join") { Switch(r.settings.locked, { onFlags(it, null, null, null) }, enabled = r.isHost) }
        SettingRow("APPROVE JOINS", if (r.settings.approval) "You approve each person" else "People join right away") { Switch(r.settings.approval, { onFlags(null, it, null, null) }, enabled = r.isHost) }
        SettingRow("CHAT", if (r.settings.chat) "Everyone can type" else "Only the host can type") { Switch(r.settings.chat, { onFlags(null, null, it, null) }, enabled = r.isHost) }
        SettingRow("PASSCODE", if (r.settings.passcode) "People need a passcode to join" else "No passcode") {
            TextButton({ pc = ""; pcDialog = true }, enabled = r.isHost) { Text(if (r.settings.passcode) "CHANGE" else "SET", color = Color.White) }
            if (r.settings.passcode) TextButton({ onPasscode("") }, enabled = r.isHost) { Text("REMOVE", color = SubtleText) }
        }
        if (pcDialog) AlertDialog(onDismissRequest = { pcDialog = false }, containerColor = Color(0xFF1C1838), title = { Text("Room passcode", color = Color.White) },
            text = { OutlinedTextField(pc, { pc = it.take(12) }, singleLine = true, label = { Text("4-12 characters") }) },
            confirmButton = { TextButton({ onPasscode(pc); pcDialog = false }, enabled = pc.length >= 4) { Text("SAVE") } },
            dismissButton = { TextButton({ pcDialog = false }) { Text("CANCEL") } })
        SettingRow("MAX PEOPLE", "Room capacity") {
            listOf(10, 25, 50, 100).forEach { n -> TextButton({ onFlags(null, null, null, n) }, enabled = r.isHost) { Text("$n", color = if (r.settings.max == n) Color.White else SubtleText) } }
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White)
            Slider(vol, { vol = it; am.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).roundToInt(), 0) }, Modifier.weight(1f).padding(horizontal = 10.dp),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-translate chat", color = Color.White, fontSize = 12.sp); Spacer(Modifier.width(10.dp))
            Switch(flags.autoTranslate, onTranslate)
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun SettingRow(title: String, sub: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontSize = 13.sp); Text(sub, color = SubtleText, fontSize = 10.sp, lineHeight = 12.sp) }
        Row(Modifier.weight(2f), horizontalArrangement = Arrangement.End, content = content)
    }
}

@Composable
private fun BoxScope.FloatingEmoji(r: Reaction, onDone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val xFrac = remember { ((r.key * 37) % 70 + 15) / 100f }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(2200, easing = LinearEasing)); onDone() }
    Box(Modifier.matchParentSize().onGloballyPositioned { size = it.size }) {
        if (size != IntSize.Zero) {
            Column(Modifier.offset { IntOffset((size.width * xFrac).roundToInt(), (size.height * (0.85f - 0.7f * progress.value)).roundToInt()) }.alpha(1f - progress.value * 0.8f),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(r.emoji, fontSize = 32.sp); Text(r.from, fontSize = 10.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun ExoSynced(room: RoomState, onControl: (String, Double) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    val isHost = room.isHost
    val currentOnControl by rememberUpdatedState(onControl)
    var loadedUrl by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { player.release() } }

    LaunchedEffect(room.version, room.url) {
        if (room.url.isBlank()) return@LaunchedEffect
        if (room.url != loadedUrl) {
            loadedUrl = room.url
            player.setMediaItem(MediaItem.fromUri(room.url)); player.prepare(); player.playWhenReady = room.playing
            if (!isHost || room.position > 1.0) player.seekTo((room.position * 1000).toLong())
        } else if (!isHost) {
            player.playWhenReady = room.playing
            if (abs(player.currentPosition / 1000.0 - room.position) > 1.5) player.seekTo((room.position * 1000).toLong())
        }
    }
    DisposableEffect(isHost) {
        val l = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (isHost && loadedUrl.isNotBlank()) currentOnControl(if (playWhenReady) "play" else "pause", player.currentPosition / 1000.0)
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (isHost && reason == Player.DISCONTINUITY_REASON_SEEK) currentOnControl("seek", new.positionMs / 1000.0)
            }
            override fun onPlaybackStateChanged(state: Int) { if (isHost && state == Player.STATE_ENDED) currentOnControl("ended", 0.0) }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (isHost && loadedUrl.isNotBlank()) currentOnControl("ended", 0.0)
            }
        }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    LaunchedEffect(isHost) {
        while (isHost) { delay(4000); if (loadedUrl.isNotBlank()) currentOnControl(if (player.playWhenReady) "play" else "pause", player.currentPosition / 1000.0) }
    }
    AndroidView(modifier = modifier, factory = { c -> PlayerView(c).apply { this.player = player; useController = isHost } }, update = { it.useController = isHost })
}

@Composable
private fun TranslatedLine(text: String) {
    var t by remember(text) { mutableStateOf<String?>(null) }
    LaunchedEffect(text) { t = ChatTranslator.translate(text) }
    t?.let { Text("🌐 $it", color = Color.White.copy(0.75f), fontSize = 12.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
}

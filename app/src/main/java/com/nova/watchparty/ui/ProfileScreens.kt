package com.nova.watchparty.ui

import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nova.watchparty.*
import com.nova.watchparty.net.ApiError
import com.nova.watchparty.net.Backend
import kotlinx.coroutines.launch
import java.util.Date

@Composable
private fun Section(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().glass(RoundedCornerShape(12.dp), 0.10f).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp)); content()
    }
}

@Composable
fun ProfileScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val u = vm.user ?: return
    var bio by remember(u.bio) { mutableStateOf(u.bio) }
    var name by remember(u.name) { mutableStateOf(u.name) }
    var history by remember { mutableStateOf<List<SavedItem>>(emptyList()) }
    var friends by remember { mutableStateOf<List<Friend>>(emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.uploadAvatar(ctx, it) } }
    LaunchedEffect(Unit) {
        try { history = Backend.saved("history").take(8); friends = Backend.friends() } catch (_: ApiError) {}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBarBack({ vm.back() }, title = { NovaLogo(28.dp) })
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Avatar(u.avatar, u.name, 120.dp)
                Box(Modifier.align(Alignment.BottomEnd).size(32.dp).clip(CircleShape).background(Color.White), Alignment.Center) { Icon(Icons.Default.Edit, "Change photo", tint = Color.Black, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(name, { name = it.take(24) }, singleLine = true, modifier = Modifier.width(220.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                trailingIcon = { if (name != u.name && name.isNotBlank()) IconButton({ vm.saveProfile(name, u.handle) }) { Icon(Icons.Default.Check, "Save", tint = NovaTeal) } })
            Text("@${u.handle}", color = SubtleText, fontSize = 14.sp)
        }
        Section(Icons.Default.Notes, "Bio") {
            OutlinedTextField(bio, { bio = it.take(300) }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Enter your bio here…") }, minLines = 2)
            if (bio != u.bio) PillButton("Save bio", { vm.saveProfile(u.name, u.handle, bio) }, Modifier.padding(top = 8.dp))
        }
        Section(Icons.Default.BarChart, "Stats") {
            StatRow("Online", null, dot = true)
            StatRow("Join date", DateFormat.getMediumDateFormat(ctx).format(Date(u.joined)))
            StatRow("Nova time", "${u.minutes} minutes")
        }
        Section(Icons.Default.Interests, "Interests") {
            var text by remember(u.interests) { mutableStateOf(u.interests.joinToString(", ")) }
            OutlinedTextField(text, { text = it.take(200) }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("music, anime, cricket…") })
            val parsed = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parsed != u.interests) PillButton("Save interests", { vm.saveInterests(parsed) }, Modifier.padding(top = 8.dp))
        }
        Section(Icons.Default.PhotoLibrary, "Gallery") {
            val add = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.addGalleryPhoto(ctx, it) } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                u.gallery.forEach { g ->
                    Box { AsyncImage(Backend.absolute(g), null, Modifier.size(90.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(0.6f)).clickable { vm.removeGalleryPhoto(g) }, Alignment.Center) {
                            Icon(Icons.Default.Close, "Remove", tint = Color.White, modifier = Modifier.size(14.dp)) } }
                }
                if (u.gallery.size < 6) Box(Modifier.size(90.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.12f)).clickable { add.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Alignment.Center) {
                    Icon(Icons.Default.Add, "Add photo", tint = Color.White, modifier = Modifier.size(32.dp)) }
            }
        }
        Section(Icons.Default.Link, "Channels & Socials") {
            var channels by remember(u.channels) { mutableStateOf(u.channels.joinToString(", ")) }
            val keys = listOf("instagram", "x", "youtube", "tiktok", "twitch", "discord", "website")
            val socials = remember(u.socials) { mutableStateMapOf<String, String>().apply { putAll(u.socials) } }
            OutlinedTextField(channels, { channels = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("Favourite channels (comma separated)") })
            keys.forEach { k -> OutlinedTextField(socials[k] ?: "", { socials[k] = it.take(120) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, label = { Text(k.replaceFirstChar { c -> c.uppercase() }) }) }
            val parsed = channels.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val clean = socials.filterValues { it.isNotBlank() }
            if (parsed != u.channels || clean != u.socials) PillButton("Save", { vm.saveExtras(parsed, clean) }, Modifier.padding(top = 8.dp))
        }
        Section(Icons.Default.Group, "Friends") {
            if (friends.isEmpty()) Text("No friends yet. Add people by their @handle.", color = SubtleText, fontSize = 13.sp)
            friends.take(6).forEach { f -> Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Avatar(f.avatar, f.name, 32.dp); Spacer(Modifier.width(10.dp)); Text("${f.name}  @${f.handle}", color = Color.White, fontSize = 13.sp) } }
            PillButton("Manage friends", { vm.go(Screen.Friends) }, Modifier.padding(top = 8.dp))
        }
        Section(Icons.Default.PlayArrow, "Videos") {
            if (history.isEmpty()) Text("Videos you watch show up here.", color = SubtleText, fontSize = 13.sp)
            history.forEach { h ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (h.thumb.isNotBlank()) AsyncImage(h.thumb, null, Modifier.size(width = 80.dp, height = 45.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(10.dp)); Text(h.title, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun StatRow(label: String, value: String?, dot: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot) Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF4ADE80))) else Icon(Icons.Default.Info, null, tint = SubtleText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp)); Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        value?.let { Text(it, color = Color.White, fontSize = 14.sp) }
    }
}

@Composable
fun FriendsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf(FriendsState(emptyList(), emptyList(), emptyList())) }
    var handle by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf<String?>(null) }
    fun reload() { scope.launch { try { st = Backend.friendsState(); vm.pendingRequests = st.incoming.size } catch (e: ApiError) { vm.toasts.tryEmit(e.message ?: "Failed") } } }
    LaunchedEffect(Unit) { reload() }
    Column(Modifier.fillMaxSize()) {
        TopBarBack({ vm.back() }, title = { Text("Friends", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light) })
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(handle, { handle = it.lowercase().filter { c -> c.isLetterOrDigit() || c in "_.-@" } }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("@handle") })
            Spacer(Modifier.width(8.dp))
            PillButton("Add", {
                scope.launch {
                    try { vm.toasts.emit(if (Backend.requestFriend(handle) == "friends") "You are now friends" else "Friend request sent"); handle = ""; reload() }
                    catch (e: ApiError) { vm.toasts.emit(e.message ?: "Failed") }
                }
            }, enabled = handle.length > 2)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
            PillButton("Invite friends to Nova", {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Watch videos with me on Nova Watch Party! My handle is @${vm.user?.handle}")
                }, "Invite friends"))
            })
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (st.incoming.isNotEmpty()) {
                item { Header("Requests (${st.incoming.size})") }
                items(st.incoming, key = { "in" + it.id }) { f ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(f.avatar, f.name, 44.dp, Modifier.clickable { sheet = f.handle }); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(f.name, color = Color.White, fontSize = 15.sp); Text("@${f.handle}", color = SubtleText, fontSize = 12.sp) }
                        IconButton({ scope.launch { try { Backend.acceptFriend(f.id); reload() } catch (_: ApiError) {} } }) { Icon(Icons.Default.Check, "Accept", tint = NovaTeal) }
                        IconButton({ scope.launch { try { Backend.declineFriend(f.id); reload() } catch (_: ApiError) {} } }) { Icon(Icons.Default.Close, "Decline", tint = SubtleText) }
                    }
                }
            }
            item { Header("Friends (${st.friends.size})") }
            if (st.friends.isEmpty()) item { Text("No friends yet. Add people by their @handle.", color = SubtleText, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
            items(st.friends, key = { "f" + it.id }) { f ->
                Row(Modifier.fillMaxWidth().clickable { sheet = f.handle }.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(f.avatar, f.name, 44.dp); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(f.name, color = Color.White, fontSize = 15.sp); Text("@${f.handle}", color = SubtleText, fontSize = 12.sp) }
                    IconButton({ scope.launch { try { Backend.removeFriend(f.id); reload() } catch (_: ApiError) {} } }) { Icon(Icons.Default.PersonRemove, "Remove", tint = SubtleText) }
                }
            }
            if (st.outgoing.isNotEmpty()) {
                item { Header("Sent requests") }
                items(st.outgoing, key = { "out" + it.id }) { f ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(f.avatar, f.name, 36.dp); Spacer(Modifier.width(12.dp)); Text("${f.name}  @${f.handle}", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("pending", color = SubtleText, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    sheet?.let { h -> UserSheet(vm, h, null) { sheet = null; reload() } }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val u = vm.user ?: return
    val f = u.flags
    var emojiDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var blockedDialog by remember { mutableStateOf(false) }
    fun open(path: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Backend.base + path)))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBarBack({ vm.back() }, title = { NovaLogo(28.dp) })
        Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Profile) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(u.avatar, u.name, 56.dp); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(u.name, color = Color.White, fontSize = 18.sp); Text("@${u.handle}", color = SubtleText, fontSize = 13.sp) }
            Icon(Icons.Default.ChevronRight, null, tint = Color.White)
        }
        Header("Connected accounts")
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (u.guest) "Guest" else "Account", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.CheckCircle, null, tint = NovaTeal, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(if (u.guest) "Signed in as guest" else "Signed in as ${u.email.ifBlank { u.name }}", color = Color.White, fontSize = 13.sp)
        }
        Header("Settings")
        Item("Quick Reaction", "Change your double-tap reaction to chat messages", onClick = { emojiDialog = true }) { Text(f.quickReaction, fontSize = 24.sp) }
        Item("Hide Mature Content", "Do not display explicit content", onClick = { vm.setFlags(f.copy(hideMature = !f.hideMature)) }) {
            Checkbox(f.hideMature, { vm.setFlags(f.copy(hideMature = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black))
        }
        Item("Floating Video Player", "Keep the video playing in a small window when you leave the app", onClick = { vm.setFlags(f.copy(floatingPlayer = !f.floatingPlayer)) }) {
            Checkbox(f.floatingPlayer, { vm.setFlags(f.copy(floatingPlayer = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black))
        }
        Item("Restrict Invitations", "Only allow invitations from friends", onClick = { vm.setFlags(f.copy(restrictInvites = !f.restrictInvites)) }) {
            Checkbox(f.restrictInvites, { vm.setFlags(f.copy(restrictInvites = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black))
        }
        Item("Auto-translate Chat Messages", "Translate chat that doesn't match your language (on-device)", onClick = { vm.setFlags(f.copy(autoTranslate = !f.autoTranslate)) }) {
            Checkbox(f.autoTranslate, { vm.setFlags(f.copy(autoTranslate = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black))
        }
        Item("Hide Location", "You will not appear in Nearby rooms", onClick = { vm.setFlags(f.copy(hideLocation = !f.hideLocation)) }) {
            Checkbox(f.hideLocation, { vm.setFlags(f.copy(hideLocation = it)) }, colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black))
        }
        Item("Privacy", "Privacy policy", onClick = { open("/privacy") }) { Icon(Icons.Default.ChevronRight, null, tint = Color.White) }
        Item("Terms of Service", "Your rights and obligations", onClick = { open("/terms") }) { Icon(Icons.Default.ChevronRight, null, tint = Color.White) }
        Item("Blocked users", "People you blocked can't chat with you or send requests", onClick = { blockedDialog = true }) { Icon(Icons.Default.ChevronRight, null, tint = Color.White) }
        Header("Feedback")
        Item("Review Nova", "If you enjoy using Nova, please take a moment to review it", onClick = {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${ctx.packageName}"))) }
            catch (e: Exception) { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=${ctx.packageName}"))) }
        }) {}
        Item("Share Nova", "Tell your friends", onClick = {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Watch videos together with me on Nova Watch Party! https://play.google.com/store/apps/details?id=${ctx.packageName}") }, "Share"))
        }) {}
        Header("Help")
        Item("Replay the tutorial", "Learn about Nova's features", onClick = { vm.go(Screen.Onboarding) }) {}
        Item("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", onClick = {}) {}
        Item("Sign out", "", onClick = { vm.signOut() }) {}
        Item("Delete Account", "Permanently deletes your account and data", onClick = { deleteDialog = true }) { Icon(Icons.Default.DeleteForever, null, tint = Color(0xFFFF6B6B)) }
        Spacer(Modifier.height(40.dp))
    }

    if (emojiDialog) AlertDialog(onDismissRequest = { emojiDialog = false }, containerColor = Color(0xFF1C1838), title = { Text("Quick Reaction", color = Color.White) }, text = {
        val list = listOf("❤️", "😂", "😮", "👏", "🔥", "😢", "🎉", "👍", "😍", "🤯", "🥳", "💯")
        Column { list.chunked(4).forEach { row -> Row { row.forEach { e -> Text(e, fontSize = 32.sp, modifier = Modifier.padding(10.dp).clickable { vm.setFlags(f.copy(quickReaction = e)); emojiDialog = false }) } } } }
    }, confirmButton = { TextButton({ emojiDialog = false }) { Text("Close") } })
    if (blockedDialog) {
        var list by remember { mutableStateOf<List<Friend>?>(null) }
        val sc = rememberCoroutineScope()
        LaunchedEffect(Unit) { list = try { Backend.blocks() } catch (_: ApiError) { emptyList() } }
        AlertDialog(onDismissRequest = { blockedDialog = false }, containerColor = Color(0xFF1C1838), title = { Text("Blocked users", color = Color.White) }, text = {
            val l = list
            if (l == null) Spinner() else if (l.isEmpty()) Text("You haven't blocked anyone.", color = SubtleText)
            else Column { l.forEach { b -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${b.name}  @${b.handle}", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton({ sc.launch { try { Backend.unblock(b.id); list = l - b } catch (_: ApiError) {} } }) { Text("Unblock") } } } }
        }, confirmButton = { TextButton({ blockedDialog = false }) { Text("Close") } })
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, containerColor = Color(0xFF1C1838),
        title = { Text("Delete account?", color = Color.White) }, text = { Text("This permanently removes your profile, history, likes and friends. This cannot be undone.", color = SubtleText) },
        confirmButton = { TextButton({ deleteDialog = false; vm.deleteAccount() }) { Text("Delete", color = Color(0xFFFF6B6B)) } },
        dismissButton = { TextButton({ deleteDialog = false }) { Text("Cancel") } })
}

@Composable
private fun Header(t: String) = Text(t, color = Color.White, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(Color.White.copy(0.12f)).padding(horizontal = 16.dp, vertical = 5.dp))

@Composable
private fun Item(title: String, sub: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            if (sub.isNotBlank()) Text(sub, color = SubtleText, fontSize = 11.sp, lineHeight = 14.sp)
        }
        trailing()
    }
}
